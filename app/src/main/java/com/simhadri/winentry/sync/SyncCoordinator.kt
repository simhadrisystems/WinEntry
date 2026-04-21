package com.simhadri.winentry.sync

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.work.*
import com.simhadri.winentry.ui.auth.ErrorLogger
import com.simhadri.winentry.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Coordinates syncing between local database and Google Sheets.
 *
 * Purchase sync (v2 — txnId aware):
 *   Instead of filtering by createdAt timestamp and blindly appending,
 *   we now ask the DAO for all rows with a pending sync status
 *   (PENDING_INSERT / PENDING_UPDATE / PENDING_DELETE / SYNC_ERROR)
 *   and let CloudSyncManager decide the correct Sheets operation for each.
 *
 *   After sync, local rows are updated:
 *     - synced rows       → SYNCED
 *     - deleted rows      → hard-deleted from local DB
 *     - failed rows       → SYNC_ERROR  (retried on next sync)
 *
 * Sheet routing:
 *   - Products sync  → MASTER_SPREADSHEET_ID  (read-only, admin-managed)
 *   - All other sync → user sheet (getSpreadsheetId(), set by AuthViewModel on login)
 */
class SyncCoordinator(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("SyncPrefs", Context.MODE_PRIVATE)
    private val database = AppDatabase.getInstance(context)

    companion object {
        private const val TAG = "SyncCoordinator"
        private const val PREF_SPREADSHEET_ID    = "spreadsheet_id"
        private const val PREF_LAST_SYNC         = "last_sync_time"
        private const val PREF_AUTO_SYNC_ENABLED = "auto_sync_enabled"
        private const val SYNC_WORK_NAME         = "inventory_sync_work"

        // Workspace request state — set when user submits a workspace request to admin
        private const val PREF_WORKSPACE_REQUESTED = "workspace_requested"

        // Master sheet — products only, admin-managed, never changes per-user
        const val MASTER_SPREADSHEET_ID = "1KQavYNe_uVk5GnUk8UOGKzQEEgC76I5aeVWP7RrcwaQ"

        /**
         * Admin User Registry spreadsheet ID.
         *
         * Admin creates a dedicated Google Sheet for user registrations, adds a tab
         * named "UserRegistry", and shares the sheet with "Can edit" access for all
         * app users.  Set this ID here before deploying.
         *
         * Column layout (A–P):
         *   A=Row#  B=Registered At  C=Email  D=Owner Name  E=Business Name
         *   F=Display Name  G=Location  H=Phone  I=UID  J=Device  K=Status
         *   L=Sheet ID  M=Sheet URL  N=Processed At  O=Role  P=Notes (admin free text)
         *
         * Leave blank to skip sheet-based registration (Firestore-only).
         *
         * ── Tab ownership ────────────────────────────────────────────────────────
         * "UserRegistry"  — written ONLY by this app (appendUserRegistration).
         * "CFRequests"    — written ONLY by the Cloud Function (service account).
         *
         * Keeping writers on separate tabs avoids cross-writer dedup collisions.
         * The Cloud Function must target tab "CFRequests"; see CF_REQUESTS_TAB_HEADERS.
         *
         * TODO: Set this to your admin user registry sheet ID before release.
         */
        const val ADMIN_USER_REGISTRY_SPREADSHEET_ID = "1L4PpNtS2AxfhP2XwnPYVD8ltUSPUJSUcAZbVm7yn5LM"

        /**
         * Column headers for the "CFRequests" tab — Cloud Function writes here.
         *
         * Tell whoever manages the CF to create a tab named exactly "CFRequests"
         * in the same registry spreadsheet and use this row as the header (row 1):
         *
         *   A=Row#  B=Requested At  C=UID  D=Email  E=Display Name
         *   F=Owner Name  G=Business Name  H=Phone  I=Location  J=Device
         *   K=Status  L=Sheet ID  M=Sheet URL  N=Processed At  O=Role  P=Notes
         *
         * The CF should append one row per new admin_request document and update
         * in-place (match by UID in col C) when the sheet is provisioned.
         */
        val CF_REQUESTS_TAB_HEADERS = listOf(
            "Row#", "Requested At", "UID", "Email", "Display Name",
            "Owner Name", "Business Name", "Phone", "Location", "Device",
            "Status", "Sheet ID", "Sheet URL", "Processed At", "Role", "Notes"
        )
    }

    // ── Configuration ─────────────────────────────────────────────────────────

    fun setSpreadsheetId(spreadsheetId: String) =
        prefs.edit().putString(PREF_SPREADSHEET_ID, spreadsheetId).apply()

    fun getSpreadsheetId(): String? {
        val syncId = prefs.getString(PREF_SPREADSHEET_ID, null)
        if (!syncId.isNullOrBlank()) return syncId
        // SyncPrefs may have been cleared (reinstall, sign-out/in race) while
        // inventory_prefs still holds the sheet ID — recover and backfill.
        val authId = context
            .getSharedPreferences("inventory_prefs", Context.MODE_PRIVATE)
            .getString("user_sheet_id", null)
        if (!authId.isNullOrBlank()) {
            prefs.edit().putString(PREF_SPREADSHEET_ID, authId).apply()
        }
        return authId
    }

    fun setAutoSyncEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(PREF_AUTO_SYNC_ENABLED, enabled).apply()
        if (enabled) schedulePeriodicSync() else cancelPeriodicSync()
    }

    fun isAutoSyncEnabled(): Boolean =
        prefs.getBoolean(PREF_AUTO_SYNC_ENABLED, true)

    fun isUserSheetReady(): Boolean =
        !getSpreadsheetId().isNullOrBlank()

    fun getLastSyncTime(): Long =
        prefs.getLong(PREF_LAST_SYNC, 0)

    // ── Workspace request state ───────────────────────────────────────────────

    /**
     * Returns true once the user has submitted a workspace request to admin.
     * Used to update the Sync Settings card description.
     */
    fun isWorkspaceRequested(): Boolean =
        prefs.getBoolean(PREF_WORKSPACE_REQUESTED, false)

    fun setWorkspaceRequested(requested: Boolean) =
        prefs.edit().putBoolean(PREF_WORKSPACE_REQUESTED, requested).apply()

    /** Clears the active spreadsheet ID — disables cloud sync without signing out. */
    fun clearSpreadsheetId() =
        prefs.edit().remove(PREF_SPREADSHEET_ID).apply()

    // ── Full sync ─────────────────────────────────────────────────────────────

    /**
     * Perform a full sync:
     *   1. Push pending purchase operations (insert / update / delete)
     *   2. Push committed daily stock entries
     *   3. Push day-end reconciliation rows
     *
     * Products are NOT synced here — they are on-demand via syncProductsOnly(),
     * triggered from the Products menu. This keeps daily sync fast.
     */
    suspend fun performFullSync(): SyncResult = withContext(Dispatchers.IO) {

        val spreadsheetId = getSpreadsheetId()
            ?: return@withContext SyncResult.Error("User sheet not configured. Please sign in again.")

        val syncManager = CloudSyncManager(context, spreadsheetId)

        if (!syncManager.initialize())
            return@withContext SyncResult.Error("Not signed in to Google")

        if (!syncManager.isNetworkAvailable())
            return@withContext SyncResult.Error("No internet connection")

        try {
            val purchasesCount = syncPendingPurchases(syncManager)
            val stockCount     = syncDailyStock(syncManager)
            syncDaySummary(syncManager)

            prefs.edit().putLong(PREF_LAST_SYNC, System.currentTimeMillis()).apply()
            SyncResult.Success(0, purchasesCount, stockCount)

        } catch (e: Exception) {
            Log.e(TAG, "Full sync failed: ${e.message}")
            ErrorLogger.log(context, "Sync", "Full sync failed", e)
            SyncResult.Error(e.message ?: "Unknown error")
        }
    }

    // Alias called by HomeFragment via MainActivity.performSyncPublic()
    suspend fun performSyncPublic(): SyncResult = performFullSync()

    // ── Purchase sync (txnId-aware) ───────────────────────────────────────────

    private suspend fun syncPendingPurchases(syncManager: CloudSyncManager): Int {
        val purchaseDao = database.purchaseDao()

        val pending = purchaseDao.getPendingSyncPurchases()
        if (pending.isEmpty()) {
            Log.d(TAG, "No pending purchase sync operations")
            return 0
        }

        Log.d(TAG, "Syncing ${pending.size} pending purchase operations")
        val result = syncManager.syncPendingPurchases(pending)

        result.synced.forEach     { id -> purchaseDao.markAsSynced(id) }
        result.hardDelete.forEach { id -> purchaseDao.hardDeleteAfterSync(id) }
        result.errors.forEach     { id -> purchaseDao.markSyncError(id) }

        val successCount = result.synced.size + result.hardDelete.size
        Log.d(TAG, "Purchase sync done — synced: ${result.synced.size}, " +
                "deleted: ${result.hardDelete.size}, errors: ${result.errors.size}")
        if (result.hasErrors) {
            Log.w(TAG, "${result.errors.size} purchase(s) failed to sync — will retry")
            ErrorLogger.log(context, "Sync/Purchases",
                "${result.errors.size} purchase(s) failed to sync to cloud — will retry on next sync")
        }

        return successCount
    }

    // ── Daily stock sync ──────────────────────────────────────────────────────

    private suspend fun syncDailyStock(syncManager: CloudSyncManager): Int {
        val dailyStockDao = database.dailyStockDao()

        val pending = dailyStockDao.getPendingSyncStock()
        if (pending.isEmpty()) {
            Log.d(TAG, "No pending daily stock sync operations")
            return 0
        }

        Log.d(TAG, "Syncing ${pending.size} pending daily stock rows")
        val result = syncManager.syncPendingDailyStock(pending)

        result.synced.forEach { (date, productCode) ->
            dailyStockDao.markStockAsSynced(date, productCode)
        }
        result.errors.forEach { (date, productCode) ->
            dailyStockDao.markStockSyncError(date, productCode)
        }

        if (result.hasErrors) {
            Log.w(TAG, "${result.errors.size} daily stock row(s) failed to sync — will retry")
            ErrorLogger.log(context, "Sync/DailyStock",
                "${result.errors.size} daily stock row(s) failed to sync to cloud — will retry on next sync")
        }

        return result.synced.size
    }

    // ── Day summary sync ──────────────────────────────────────────────────────

    private suspend fun syncDaySummary(syncManager: CloudSyncManager) {
        val dao = database.dayReconciliationDao()
        val pending = dao.getPendingSync()
        if (pending.isEmpty()) {
            Log.d(TAG, "No pending day summary sync operations")
            return
        }
        Log.d(TAG, "Syncing ${pending.size} pending day summary rows")
        val result = syncManager.syncPendingDaySummary(pending)
        result.synced.forEach { date -> dao.markAsSynced(date) }
        result.errors.forEach { date -> dao.markSyncError(date) }
        if (result.hasErrors) {
            Log.w(TAG, "${result.errors.size} day summary row(s) failed — will retry")
            ErrorLogger.log(context, "Sync/DaySummary",
                "${result.errors.size} day summary row(s) failed to sync to cloud — will retry on next sync")
        }
        Log.d(TAG, "Day summary sync done — synced: ${result.synced.size}")
    }

    // ── Purchase down-sync (cloud → local) ───────────────────────────────────

    suspend fun previewPurchaseDownSync(
        products: List<com.simhadri.winentry.data.entity.Product>
    ): SyncResult = withContext(Dispatchers.IO) {

        val spreadsheetId = getSpreadsheetId()
            ?: return@withContext SyncResult.Error("User sheet not configured. Please sign in again.")

        val syncManager = CloudSyncManager(context, spreadsheetId)
        if (!syncManager.initialize())
            return@withContext SyncResult.Error("Not signed in to Google")
        if (!syncManager.isNetworkAvailable())
            return@withContext SyncResult.Error("No internet connection")

        return@withContext try {
            val purchaseDao = database.purchaseDao()

            val productsByAliasMap = com.simhadri.winentry.util.ProductCodeResolver
                .buildFullLookupMap(products)

            val existingKeys = purchaseDao.getAllPurchasesForDedup().map { row ->
                val canonical = productsByAliasMap[row.productCode]
                val code = if (canonical != null)
                    com.simhadri.winentry.util.ProductCodeResolver.primaryCode(canonical)
                else row.productCode
                "${row.invoiceNumber}|$code|${row.purchaseDate}"
            }.toHashSet()

            val (allPurchases, notFoundCodes) =
                syncManager.readPurchasesFromImportSheet(products, emptySet())

            val newRows  = mutableListOf<com.simhadri.winentry.data.entity.Purchase>()
            val dupRows  = mutableListOf<com.simhadri.winentry.data.entity.Purchase>()
            val seenKeys = mutableSetOf<String>()

            for (p in allPurchases) {
                val key = "${p.invoiceNumber}|${p.productCode}|${p.purchaseDate}"
                if (key in seenKeys) continue
                seenKeys.add(key)
                if (key in existingKeys) dupRows.add(p) else newRows.add(p)
            }

            Log.d(TAG, "Preview: ${newRows.size} new, ${dupRows.size} duplicates, " +
                "${notFoundCodes.size} not found")

            SyncResult.PurchaseDownSyncPreview(newRows, dupRows, notFoundCodes)

        } catch (e: Exception) {
            Log.e(TAG, "Purchase down-sync preview failed: ${e.message}")
            ErrorLogger.log(context, "Sync/DownSync", "Purchase down-sync preview failed", e)
            SyncResult.Error(e.message ?: "Unknown error")
        }
    }

    suspend fun commitPurchaseDownSync(
        toInsert:  List<com.simhadri.winentry.data.entity.Purchase>,
        toReplace: List<com.simhadri.winentry.data.entity.Purchase>
    ): SyncResult = withContext(Dispatchers.IO) {
        return@withContext try {
            val purchaseDao = database.purchaseDao()
            var inserted = 0
            var replaced = 0

            for (p in toInsert) {
                try {
                    purchaseDao.deleteByProductIdInvoiceDate(
                        p.productId, p.invoiceNumber, p.purchaseDate
                    )
                    purchaseDao.insert(p)
                    inserted++
                } catch (e: Exception) {
                    Log.w(TAG, "Insert failed ${p.invoiceNumber}/${p.productCode}: ${e.message}")
                }
            }

            for (p in toReplace) {
                try {
                    purchaseDao.deleteByInvoiceProductDate(
                        p.invoiceNumber, p.productCode, p.purchaseDate
                    )
                    purchaseDao.insert(p)
                    replaced++
                } catch (e: Exception) {
                    Log.w(TAG, "Replace failed ${p.invoiceNumber}/${p.productCode}: ${e.message}")
                }
            }

            Log.d(TAG, "Commit: $inserted inserted, $replaced replaced")
            SyncResult.PurchaseDownSync(inserted, replaced, emptySet())

        } catch (e: Exception) {
            Log.e(TAG, "commitPurchaseDownSync failed: ${e.message}")
            SyncResult.Error(e.message ?: "Unknown error")
        }
    }

    // Backward compat — delegates to preview+commit with skip behaviour
    suspend fun downloadPurchasesFromCloud(
        products: List<com.simhadri.winentry.data.entity.Product>
    ): SyncResult {
        return when (val preview = previewPurchaseDownSync(products)) {
            is SyncResult.PurchaseDownSyncPreview ->
                commitPurchaseDownSync(preview.newRows, emptyList())
            else -> preview
        }
    }

    // ── Products-only sync (master sheet → local DB) ──────────────────────────

    /**
     * @param preserveUserSettings  true  (default) — keep each product's local isActive and
     *                                                  dailySortKey; update everything else.
     *                              false           — full reset: overwrite all fields from
     *                                                  master sheet (isActive / dailySortKey
     *                                                  revert to cloud values).
     * In both modes the local Room `id` is preserved so that purchase/stock foreign-key
     * lookups by productId remain stable across syncs.
     */
    suspend fun syncProductsOnly(preserveUserSettings: Boolean = true): SyncResult =
        withContext(Dispatchers.IO) {
            val syncManager = CloudSyncManager(context, MASTER_SPREADSHEET_ID)

            if (!syncManager.initialize())
                return@withContext SyncResult.Error("Not signed in to Google")

            return@withContext try {
                val productsResult = syncManager.syncProductsFromCloud()
                if (productsResult.isSuccess) {
                    val cloudProducts = productsResult.getOrNull() ?: emptyList()
                    if (cloudProducts.isEmpty())
                        return@withContext SyncResult.Error("No products found in sheet")

                    // Build lookup of existing local products keyed by brandCode
                    val existing = database.productDao().getAllProductsSync()
                        .associateBy { it.brandCode }

                    val merged = cloudProducts.map { cloud ->
                        val local = existing[cloud.brandCode]
                        if (local != null) {
                            // Existing product — always preserve id; optionally preserve user settings
                            cloud.copy(
                                id           = local.id,
                                isActive     = if (preserveUserSettings) local.isActive     else cloud.isActive,
                                dailySortKey = if (preserveUserSettings) local.dailySortKey else cloud.dailySortKey
                            )
                        } else {
                            cloud  // new product — insert as-is
                        }
                    }

                    database.productDao().insertProducts(merged)
                    SyncResult.Success(merged.size, 0, 0)
                } else {
                    SyncResult.Error(productsResult.exceptionOrNull()?.message ?: "Unknown error")
                }
            } catch (e: Exception) {
                SyncResult.Error(e.message ?: "Unknown error")
            }
        }

    // ── Daily stock down-sync (cloud → local DB) ──────────────────────────────

    suspend fun downloadDailyStockFromCloud(): SyncResult =
        downloadDailyStockFromCloud(dateFrom = null, dateTo = null)

    suspend fun downloadDailyStockFromCloud(
        dateFrom: String?,
        dateTo:   String?
    ): SyncResult = withContext(Dispatchers.IO) {

        val spreadsheetId = getSpreadsheetId()
            ?: return@withContext SyncResult.Error("User sheet not configured. Please sign in again.")

        val syncManager = CloudSyncManager(context, spreadsheetId)

        if (!syncManager.initialize())
            return@withContext SyncResult.Error("Not signed in to Google")
        if (!syncManager.isNetworkAvailable())
            return@withContext SyncResult.Error("No internet connection")

        try {
            val allRows = syncManager.readDailyStockFromSheet()
                ?: return@withContext SyncResult.Error("Failed to read from cloud")

            val rows = when {
                dateFrom == null && dateTo == null -> allRows
                else -> allRows.filter { row ->
                    (dateFrom == null || row.date >= dateFrom) &&
                    (dateTo   == null || row.date <= dateTo)
                }
            }

            if (rows.isEmpty()) return@withContext SyncResult.DailyStockDownSync(0)

            database.dailyStockDao().insertOrReplaceAll(rows)
            Log.d(TAG, "Daily stock down-sync: restored ${rows.size} rows")
            SyncResult.DailyStockDownSync(rows.size)

        } catch (e: Exception) {
            Log.e(TAG, "downloadDailyStockFromCloud failed: ${e.message}")
            SyncResult.Error(e.message ?: "Unknown error")
        }
    }

    // ── Reconciliation down-sync (cloud → local DB) ───────────────────────────

    suspend fun downloadReconciliationFromCloud(): SyncResult =
        downloadReconciliationFromCloud(dateFrom = null, dateTo = null)

    suspend fun downloadReconciliationFromCloud(
        dateFrom: String?,
        dateTo:   String?
    ): SyncResult = withContext(Dispatchers.IO) {

        val spreadsheetId = getSpreadsheetId()
            ?: return@withContext SyncResult.Error("User sheet not configured. Please sign in again.")

        val syncManager = CloudSyncManager(context, spreadsheetId)

        if (!syncManager.initialize())
            return@withContext SyncResult.Error("Not signed in to Google")
        if (!syncManager.isNetworkAvailable())
            return@withContext SyncResult.Error("No internet connection")

        try {
            val allRows = syncManager.readDaySummaryFromSheet()
                ?: return@withContext SyncResult.Error("Failed to read reconciliation from cloud")

            val rows = when {
                dateFrom == null && dateTo == null -> allRows
                else -> allRows.filter { row ->
                    (dateFrom == null || row.date >= dateFrom) &&
                    (dateTo   == null || row.date <= dateTo)
                }
            }

            if (rows.isEmpty()) return@withContext SyncResult.ReconciliationDownSync(0)

            database.dayReconciliationDao().insertOrReplaceAll(rows)
            // Mark all as PENDING_UPSERT so the next up-sync rewrites them to the
            // cloud with date in column A, migrating the old empty-first-column layout.
            database.dayReconciliationDao().markAllAsPending()
            Log.d(TAG, "Reconciliation down-sync: restored ${rows.size} rows, marked pending for re-sync")
            SyncResult.ReconciliationDownSync(rows.size)

        } catch (e: Exception) {
            Log.e(TAG, "downloadReconciliationFromCloud failed: ${e.message}")
            SyncResult.Error(e.message ?: "Unknown error")
        }
    }

    // ── WorkManager scheduling ────────────────────────────────────────────────

    private fun schedulePeriodicSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncWorkRequest = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            SYNC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            syncWorkRequest
        )
        Log.d(TAG, "Scheduled periodic sync")
    }

    private fun cancelPeriodicSync() {
        WorkManager.getInstance(context).cancelUniqueWork(SYNC_WORK_NAME)
        Log.d(TAG, "Cancelled periodic sync")
    }

    // ── Result types ──────────────────────────────────────────────────────────

    sealed class SyncResult {
        data class Success(
            val productsCount:  Int,
            val purchasesCount: Int,
            val stockCount:     Int
        ) : SyncResult()

        data class PurchaseDownSyncPreview(
            val newRows:       List<com.simhadri.winentry.data.entity.Purchase>,
            val dupRows:       List<com.simhadri.winentry.data.entity.Purchase>,
            val notFoundCodes: Set<String> = emptySet()
        ) : SyncResult()

        data class PurchaseDownSync(
            val inserted:      Int,
            val replaced:      Int,
            val notFoundCodes: Set<String> = emptySet()
        ) : SyncResult()

        data class Error(val message: String) : SyncResult()

        data class DailyStockDownSync(val count: Int) : SyncResult()

        data class ReconciliationDownSync(val count: Int) : SyncResult()
    }
}


// ── Background worker ─────────────────────────────────────────────────────────

class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val coordinator = SyncCoordinator(applicationContext)
            // Skip silently if auto-sync is off or cloud backup has not been set up.
            // "Not configured" is not an error — the user simply hasn't requested
            // a workspace yet. Logging it as an error causes spurious red sync buttons.
            if (!coordinator.isAutoSyncEnabled()) return Result.success()
            if (!coordinator.isUserSheetReady()) return Result.success()

            when (val result = coordinator.performFullSync()) {
                is SyncCoordinator.SyncResult.Success -> {
                    Log.d("SyncWorker", "Background sync successful — " +
                            "products: ${result.productsCount}, " +
                            "purchases: ${result.purchasesCount}, " +
                            "stock: ${result.stockCount}")
                    Result.success()
                }
                is SyncCoordinator.SyncResult.Error -> {
                    Log.e("SyncWorker", "Background sync failed: ${result.message}")
                    ErrorLogger.log(applicationContext, "Sync/Background",
                        "Background sync failed: ${result.message}")
                    Result.retry()
                }
                else -> Result.success()
            }
        } catch (e: Exception) {
            Log.e("SyncWorker", "Background sync error: ${e.message}")
            ErrorLogger.log(applicationContext, "Sync/Background",
                "Background sync exception", e)
            Result.retry()
        }
    }
}
