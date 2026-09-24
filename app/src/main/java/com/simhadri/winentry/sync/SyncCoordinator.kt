package com.simhadri.winentry.sync

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.work.*
import com.simhadri.winentry.ui.auth.ErrorLogger
import com.simhadri.winentry.ui.auth.UserRole
import com.simhadri.winentry.utils.DbSnapshot
import com.simhadri.winentry.utils.StrictDate
import com.simhadri.winentry.data.AppDatabase
import com.simhadri.winentry.data.repository.CommittedSaleRefresher
import com.simhadri.winentry.data.entity.DailyStock
import com.simhadri.winentry.data.entity.DayReconciliation
import com.simhadri.winentry.data.entity.PendingCloudDelete
import com.simhadri.winentry.data.entity.Product
import com.simhadri.winentry.data.entity.Purchase
import com.simhadri.winentry.data.entity.SyncStatus
import com.simhadri.winentry.data.entity.stockCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

        const val SYNC_BUSY = "Sync already running"

        /** Rows per Cloud Function call; keeps each call well inside the CF timeout. */
        private const val CF_BATCH = 300

        /** Serialises every cloud read/write in the process; the CF upserts by sheet row position. */
        val syncLock = Mutex()

        // Workspace request state — set when user submits a workspace request to admin
        private const val PREF_WORKSPACE_REQUESTED = "workspace_requested"

        // Master sheet — products only, admin-managed, never changes per-user
        const val MASTER_SPREADSHEET_ID = "1rOd-l13Vs1LRa764lM40sjDeN70FWOArCn6NpfHUggs"

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
         * "UserRegistry" (12 cols A–L) — written ONLY by Cloud Function (CF format).
         * "AppRequests"  (16 cols A–P) — written ONLY by Cloud Function (Android format).
         *
         * All registration writes go through the Cloud Function service account.
         * The app does not write directly to the registry sheet.
         */
        const val ADMIN_USER_REGISTRY_SPREADSHEET_ID = "1zw5Xek9lW4cohUbjaX6468ZqGF7m7--mdoplByv6Rps"
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
     * Perform a full sync via the syncUserSheet Cloud Function (service account auth).
     *   1. Push pending purchase operations (insert / update / delete)
     *   2. Push committed daily stock entries
     *   3. Push day-end reconciliation rows
     *
     * Products are NOT synced here — on-demand via syncProductsOnly().
     */
    suspend fun performFullSync(): SyncResult = withContext(Dispatchers.IO) {

        if (UserRole.isViewer(context))
            return@withContext SyncResult.Success(0, 0, 0)

        if (!isNetworkAvailable())
            return@withContext SyncResult.Error("No internet connection")

        if (getSpreadsheetId().isNullOrBlank())
            return@withContext SyncResult.Error("User sheet not configured. Please sign in again.")

        if (!syncLock.tryLock()) return@withContext SyncResult.Error(SYNC_BUSY)

        val cfClient = CloudFunctionClient()

        try {
            val purchasesCount = syncPendingPurchases(cfClient)
            // Deletes go first: a queued date/range delete sent after a re-entered row's write would remove it
            if (!syncCloudDeletes(cfClient))
                return@withContext SyncResult.Error("Cloud delete failed — will retry on next sync")
            val stockCount     = syncDailyStock(cfClient)
            syncDaySummary(cfClient)

            prefs.edit().putLong(PREF_LAST_SYNC, System.currentTimeMillis()).apply()
            SyncResult.Success(0, purchasesCount, stockCount)

        } catch (e: CancellationException) {
            // The caller's scope (Activity/Fragment lifecycleScope, or the
            // WorkManager CoroutineWorker job) was cancelled mid-sync — e.g. a
            // rotation, backgrounding, or the OS reclaiming the worker. Not a
            // real failure; must propagate, not be logged as one.
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Full sync failed: ${e.message}")
            ErrorLogger.log(context, "Sync", "Full sync failed", e)
            SyncResult.Error(e.message ?: "Unknown error")
        } finally {
            syncLock.unlock()
        }
    }

    /** Local changes (including queued deletes) the cloud has not received yet. */
    suspend fun pendingChangeCount(): Int = withContext(Dispatchers.IO) {
        database.purchaseDao().getPendingSyncPurchases().size +
            database.dailyStockDao().getPendingSyncStock().size +
            database.dayReconciliationDao().getPendingSync().size +
            database.pendingCloudDeleteDao().count()
    }

    /**
     * Settings → Re-upload All: queues every live row and syncs. For a sheet that is missing
     * rows the device already marked SYNCED (e.g. after a sheet was replaced).
     */
    suspend fun reuploadAll(): SyncResult {
        withContext(Dispatchers.IO) {
            DbSnapshot.take(context, "reupload")
            database.dailyStockDao().markAllCommittedAsPending()
            database.purchaseDao().markAllActivePending()
            database.dayReconciliationDao().markAllAsPending()
        }
        return performFullSync()
    }

    /**
     * Why sample data must not be loaded now, or null when it is safe. Loading it wipes local
     * stock, so unsynced changes block it; a cloud sheet that already holds stock means the
     * user should restore instead.
     */
    suspend fun sampleDataBlocker(): String? = withContext(Dispatchers.IO) {
        val pending = pendingChangeCount()
        if (pending > 0) return@withContext "$pending change(s) on this device are not synced yet. " +
            "Sync first; loading sample data replaces the stock on this device."
        if (!isUserSheetReady()) return@withContext null
        when (val r = syncLock.withLock { CloudFunctionClient().syncUserSheet("read_all") }) {
            is SyncSheetResult.AllRead -> if (r.dailyStock.isNotEmpty())
                "Your cloud sheet already has daily stock. Use Restore from Cloud instead of sample data." else null
            is SyncSheetResult.NoSheet -> null
            else -> "Could not check your cloud sheet (no internet?). Try again when online."
        }
    }

    /** Deletes all inventory data on this device (products are kept). The cloud is not touched. */
    suspend fun wipeLocalInventory(reason: String) = withContext(Dispatchers.IO) {
        DbSnapshot.take(context, reason)
        database.dailyStockDao().deleteAll()
        database.purchaseDao().deleteAll()
        database.dayReconciliationDao().deleteAll()
        database.pendingCloudDeleteDao().clear()
    }

    // Alias called by HomeFragment via MainActivity.performSyncPublic()
    suspend fun performSyncPublic(): SyncResult = performFullSync()

    // ── Purchase sync (via CF) ────────────────────────────────────────────────

    private suspend fun syncPendingPurchases(cfClient: CloudFunctionClient): Int {
        val purchaseDao = database.purchaseDao()
        val pending = purchaseDao.getPendingSyncPurchases()
        if (pending.isEmpty()) {
            Log.d(TAG, "No pending purchase sync operations")
            return 0
        }

        Log.d(TAG, "Syncing ${pending.size} pending purchase operations")

        // By isDeleted, not status: a failed delete is SYNC_ERROR and must retry as a delete
        val toWrite  = pending.filter { !it.isDeleted }
        val toDelete = pending.filter { it.isDeleted }
        var successCount = 0

        for (chunk in toWrite.chunked(CF_BATCH)) {
            val rows = chunk.map { it.toSheetRow() }
            when (cfClient.syncUserSheet("write_purchases", rows = rows)) {
                is SyncSheetResult.Written -> {
                    purchaseDao.markSyncedIfUnchanged(chunk)
                    successCount += chunk.size
                    Log.d(TAG, "CF wrote ${chunk.size} purchase rows")
                }
                is SyncSheetResult.NoSheet -> {
                    Log.e(TAG, "Purchase write failed — user sheet not found in CF")
                    chunk.forEach { purchaseDao.markSyncError(it.id) }
                }
                else -> {
                    Log.e(TAG, "CF write_purchases failed")
                    chunk.forEach { purchaseDao.markSyncError(it.id) }
                    ErrorLogger.log(context, "Sync/Purchases",
                        "${chunk.size} purchase(s) failed to sync to cloud — will retry on next sync")
                }
            }
        }

        if (toDelete.isNotEmpty()) {
            val txnIds = toDelete.map { it.txnId }
            when (cfClient.syncUserSheet("delete_purchases", txnIds = txnIds)) {
                is SyncSheetResult.Deleted -> {
                    toDelete.forEach { purchaseDao.hardDeleteAfterSync(it.id) }
                    successCount += toDelete.size
                    Log.d(TAG, "CF deleted ${toDelete.size} purchase rows")
                }
                else -> {
                    Log.e(TAG, "CF delete_purchases failed")
                    toDelete.forEach { purchaseDao.markSyncError(it.id) }
                    ErrorLogger.log(context, "Sync/Purchases",
                        "${toDelete.size} purchase deletion(s) failed — will retry on next sync")
                }
            }
        }

        return successCount
    }

    // ── Queued deletes (daily stock / day summary) ────────────────────────────

    /** Sends queued local deletes; true when the queue is empty afterwards. */
    private suspend fun syncCloudDeletes(cfClient: CloudFunctionClient): Boolean {
        val outboxDao = database.pendingCloudDeleteDao()
        val queued = outboxDao.getAll()
        if (queued.isEmpty()) return true
        val stock = queued.filter { it.kind.startsWith("STOCK") }
        val summary = queued.filter { it.kind.startsWith("SUMMARY") }
        var ok = true
        if (stock.isNotEmpty()) {
            val res = cfClient.syncUserSheet("delete_daily_stock",
                keys = stock.filter { it.kind == PendingCloudDelete.STOCK }.map { "${it.date}|${it.productCode}" },
                dates = stock.filter { it.kind == PendingCloudDelete.STOCK_DATE }.map { it.date },
                ranges = stock.filter { it.kind == PendingCloudDelete.STOCK_RANGE }.map { it.date to it.dateTo })
            if (res is SyncSheetResult.Deleted) outboxDao.deleteAll(stock) else ok = false
        }
        if (summary.isNotEmpty()) {
            val res = cfClient.syncUserSheet("delete_day_summary",
                dates = summary.filter { it.kind == PendingCloudDelete.SUMMARY }.map { it.date },
                ranges = summary.filter { it.kind == PendingCloudDelete.SUMMARY_RANGE }.map { it.date to it.dateTo })
            if (res is SyncSheetResult.Deleted) outboxDao.deleteAll(summary) else ok = false
        }
        if (!ok) ErrorLogger.log(context, "Sync/Deletes", "Queued cloud deletes failed — will retry on next sync")
        return ok
    }

    // ── Daily stock sync (via CF) ─────────────────────────────────────────────

    private suspend fun syncDailyStock(cfClient: CloudFunctionClient): Int {
        val dailyStockDao = database.dailyStockDao()
        val pending = dailyStockDao.getPendingSyncStock()
        if (pending.isEmpty()) {
            Log.d(TAG, "No pending daily stock sync operations")
            return 0
        }

        Log.d(TAG, "Syncing ${pending.size} pending daily stock rows")
        var written = 0
        for (chunk in pending.chunked(CF_BATCH)) {
            when (cfClient.syncUserSheet("write_daily_stock", rows = chunk.map { it.toSheetRow() })) {
                is SyncSheetResult.Written -> {
                    dailyStockDao.markStockSyncedIfUnchanged(chunk)
                    written += chunk.size
                    Log.d(TAG, "CF wrote ${chunk.size} daily stock rows")
                }
                else -> {
                    chunk.forEach { dailyStockDao.markStockSyncError(it.date, it.productCode) }
                    Log.e(TAG, "CF write_daily_stock failed — will retry")
                    ErrorLogger.log(context, "Sync/DailyStock",
                        "${chunk.size} daily stock row(s) failed to sync to cloud — will retry on next sync")
                }
            }
        }
        return written
    }

    // ── Day summary sync (via CF) ─────────────────────────────────────────────

    private suspend fun syncDaySummary(cfClient: CloudFunctionClient) {
        val dao = database.dayReconciliationDao()
        val pending = dao.getPendingSync()
        if (pending.isEmpty()) {
            Log.d(TAG, "No pending day summary sync operations")
            return
        }

        Log.d(TAG, "Syncing ${pending.size} pending day summary rows")
        for (chunk in pending.chunked(CF_BATCH)) {
            when (cfClient.syncUserSheet("write_day_summary", rows = chunk.map { it.toDaySummaryRow() })) {
                is SyncSheetResult.Written -> {
                    dao.markSyncedIfUnchanged(chunk)
                    Log.d(TAG, "CF wrote ${chunk.size} day summary rows")
                }
                else -> {
                    chunk.forEach { dao.markSyncError(it.date) }
                    Log.e(TAG, "CF write_day_summary failed — will retry")
                    ErrorLogger.log(context, "Sync/DaySummary",
                        "${chunk.size} day summary row(s) failed to sync to cloud — will retry on next sync")
                }
            }
        }
    }

    // ── Purchase down-sync (cloud → local) ───────────────────────────────────

    /** Preview import from the PurchaseImport tab (vendor invoice format, one-way). */
    suspend fun previewPurchaseDownSync(
        products: List<Product>
    ): SyncResult = withContext(Dispatchers.IO) {

        if (!isNetworkAvailable())
            return@withContext SyncResult.Error("No internet connection")

        return@withContext try {
            val cfClient = CloudFunctionClient()
            val sheetResult = cfClient.syncUserSheet("read_all")

            val allRead = sheetResult as? SyncSheetResult.AllRead
            val importRows = when (sheetResult) {
                is SyncSheetResult.AllRead -> sheetResult.purchaseImport
                is SyncSheetResult.NoSheet -> return@withContext SyncResult.Error(
                    "User sheet not configured. Please sign in again.")
                else -> return@withContext SyncResult.Error("Failed to read from cloud")
            }
            if (importRows.isEmpty()) return@withContext SyncResult.PurchaseDownSyncPreview(
                emptyList(), emptyList(), emptySet())

            // Build dedup keys from currently active (non-deleted) local purchases.
            // Key format must match what parseImportSheetRows generates: "invoice|productCode|date"
            val activeLocal = database.purchaseDao().getAllPurchasesSync().filter { !it.isDeleted }
            val existingKeys = activeLocal.map {
                "${it.invoiceNumber}|${it.productCode}|${it.purchaseDate}"
            }.toHashSet()

            val (parsedRows, notFoundCodes) =
                CloudSyncManager.parseImportSheetRows(importRows, products, emptySet())

            // A line already in the Purchases tab keeps its TxnId, so syncing the import updates
            // that row instead of appending another copy (re-imports after a reinstall used to
            // add a full copy each time). Its ReceivedDate wins because it carries in-app edits.
            val cloudLines = CloudSyncManager.cloudLineIndex(allRead?.purchases.orEmpty(), products)
            val allRows = parsedRows.map { p ->
                val cloud = cloudLines[CloudSyncManager.lineKey(p.productCode, p.invoiceNumber, p.purchaseDate)]
                    ?: return@map p
                p.copy(txnId = cloud.txnId, receivedDate = cloud.receivedDate.ifBlank { p.receivedDate })
            }

            val newRows = allRows.filter {
                "${it.invoiceNumber}|${it.productCode}|${it.purchaseDate}" !in existingKeys
            }
            val dupRows = allRows.filter {
                "${it.invoiceNumber}|${it.productCode}|${it.purchaseDate}" in existingKeys
            }

            Log.d(TAG, "PurchaseImport preview: ${newRows.size} new, ${dupRows.size} dup, ${notFoundCodes.size} not found")
            SyncResult.PurchaseDownSyncPreview(newRows, dupRows, notFoundCodes)

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Purchase down-sync preview failed: ${e.message}")
            ErrorLogger.log(context, "Sync/DownSync", "Purchase down-sync preview failed", e)
            SyncResult.Error(e.message ?: "Unknown error")
        }
    }

    /** Preview restore from the Purchases tab (normalised app format, already synced). */
    suspend fun previewPurchasesTabDownSync(
        products: List<Product>
    ): SyncResult = withContext(Dispatchers.IO) {

        if (!isNetworkAvailable())
            return@withContext SyncResult.Error("No internet connection")

        return@withContext try {
            val cfClient = CloudFunctionClient()
            val sheetResult = cfClient.syncUserSheet("read_all")

            val purchasesRows = when (sheetResult) {
                is SyncSheetResult.AllRead -> sheetResult.purchases
                is SyncSheetResult.NoSheet -> return@withContext SyncResult.Error(
                    "User sheet not configured. Please sign in again.")
                else -> return@withContext SyncResult.Error("Failed to read from cloud")
            }
            if (purchasesRows.isEmpty()) return@withContext SyncResult.Error("CLOUD_EMPTY")

            val purchaseDao = database.purchaseDao()
            val existingTxnIds = purchaseDao.getAllTxnIds().toHashSet()

            val productMap = CloudSyncManager.buildProductLookupMap(products)
            val localLines = purchaseDao.getAllPurchasesSync()
                .filter { !it.isDeleted }
                .map { with(CloudSyncManager) { it.lineKey(productMap) } }
                .toHashSet()
            val newRows = CloudSyncManager.newestPerLine(
                CloudSyncManager.parsePurchasesTabRows(purchasesRows, products, existingTxnIds)
                    .filter { with(CloudSyncManager) { it.lineKey(productMap) } !in localLines },
                productMap
            )

            // Earlier restores dropped column AC; put the cloud ReceivedDate back on existing rows
            var repaired = 0
            CloudSyncManager.parseReceivedDates(purchasesRows).forEach { (txnId, rd) ->
                if (txnId in existingTxnIds) repaired += purchaseDao.repairReceivedDate(txnId, rd)
            }
            if (repaired > 0) Log.d(TAG, "Purchases tab: restored ReceivedDate on $repaired row(s)")

            Log.d(TAG, "Purchases tab preview: ${newRows.size} new (cloud has ${purchasesRows.size})")
            SyncResult.PurchaseDownSyncPreview(newRows, emptyList(), emptySet())

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Purchases tab down-sync preview failed: ${e.message}")
            ErrorLogger.log(context, "Sync/DownSync", "Purchases tab down-sync preview failed", e)
            SyncResult.Error(e.message ?: "Unknown error")
        }
    }

    suspend fun commitPurchaseDownSync(
        toInsert:  List<Purchase>,
        toReplace: List<Purchase>
    ): SyncResult = withContext(Dispatchers.IO) {
        return@withContext try { syncLock.withLock {
            val purchaseDao = database.purchaseDao()
            var inserted = 0
            var replaced = 0

            for (p in toInsert) {
                try {
                    purchaseDao.replaceLine(p)
                    inserted++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Insert failed ${p.invoiceNumber}/${p.productCode}: ${e.message}")
                }
            }

            for (p in toReplace) {
                try {
                    purchaseDao.replaceLine(p)
                    replaced++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Replace failed ${p.invoiceNumber}/${p.productCode}: ${e.message}")
                }
            }

            // Auto-activate any inactive products that received new purchases
            val activateIds = (toInsert + toReplace)
                .map { it.productId }.filter { it > 0 }.toSet()
            if (activateIds.isNotEmpty()) {
                database.productDao().activateByIds(activateIds.toList())
            }

            CommittedSaleRefresher(database).refresh((toInsert + toReplace)
                .flatMap { listOf(CommittedSaleRefresher.effectiveDate(it), it.purchaseDate) })
            Log.d(TAG, "Commit: $inserted inserted, $replaced replaced")
            SyncResult.PurchaseDownSync(inserted, replaced, emptySet())

        } } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "commitPurchaseDownSync failed: ${e.message}")
            SyncResult.Error(e.message ?: "Unknown error")
        }
    }

    // Backward compat — delegates to preview+commit with skip behaviour
    suspend fun downloadPurchasesFromCloud(
        products: List<Product>
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
            if (!isNetworkAvailable())
                return@withContext SyncResult.Error("No internet connection")

            return@withContext try {
                val masterResult = CloudFunctionClient().getMasterProducts()
                if (masterResult is MasterSheetResult.NotInvited)
                    return@withContext SyncResult.Error(
                        "Your account has not been activated yet.\n\nAsk the admin to add your email to the invited users list."
                    )
                if (masterResult is MasterSheetResult.Error)
                    return@withContext SyncResult.Error("Failed to fetch master products from cloud")

                val cloudProducts = CloudSyncManager.parseProductRows(
                    (masterResult as MasterSheetResult.Success).data.products
                )
                if (cloudProducts.isEmpty())
                    return@withContext SyncResult.Error("No products found in master sheet")

                val existing = database.productDao().getAllProductsSync()
                    .associateBy { it.brandCode.trim().uppercase() }

                val merged = cloudProducts.map { cloud ->
                    val local = existing[cloud.brandCode]
                    if (local != null) {
                        // The type is part of the stock key: keep it once history exists
                        val hasHistory = local.productType != cloud.productType && (
                            database.purchaseDao().countForProduct(local.id, local.stockCode) > 0 ||
                                database.dailyStockDao().countForProduct(local.stockCode) > 0)
                        val type = if (hasHistory) local.productType else cloud.productType
                        val aliases = (local.aliases.split(",") + cloud.aliases.split(","))
                            .map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(",")
                        cloud.copy(
                            id           = local.id,
                            productType  = type,
                            brandCode    = local.brandCode,
                            qqCode       = "${type}${local.brandCode}QQ",
                            ppCode       = "${type}${local.brandCode}PP",
                            nnCode       = "${type}${local.brandCode}NN",
                            ddCode       = "${type}${local.brandCode}DD",
                            aliases      = aliases,
                            isActive     = if (preserveUserSettings) local.isActive     else cloud.isActive,
                            dailySortKey = if (preserveUserSettings) local.dailySortKey else cloud.dailySortKey,
                            qqSalePrice  = if (preserveUserSettings) local.qqSalePrice  else cloud.qqSalePrice,
                            ppSalePrice  = if (preserveUserSettings) local.ppSalePrice  else cloud.ppSalePrice,
                            nnSalePrice  = if (preserveUserSettings) local.nnSalePrice  else cloud.nnSalePrice,
                            ddSalePrice  = if (preserveUserSettings) local.ddSalePrice  else cloud.ddSalePrice
                        )
                    } else {
                        cloud
                    }
                }

                database.productDao().insertProducts(merged)
                SyncResult.Success(merged.size, 0, 0)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SyncResult.Error(e.message ?: "Unknown error")
            }
        }

    // ── Daily stock down-sync (cloud → local DB, via CF) ─────────────────────

    suspend fun downloadDailyStockFromCloud(): SyncResult =
        downloadDailyStockFromCloud(dateFrom = null, dateTo = null)

    suspend fun downloadDailyStockFromCloud(
        dateFrom: String?,
        dateTo:   String?
    ): SyncResult = withContext(Dispatchers.IO) {

        if (!isNetworkAvailable())
            return@withContext SyncResult.Error("No internet connection")

        try { syncLock.withLock {
            val sheetResult = CloudFunctionClient().syncUserSheet("read_all")

            val rawRows = when (sheetResult) {
                is SyncSheetResult.AllRead -> sheetResult.dailyStock
                is SyncSheetResult.NoSheet -> return@withContext SyncResult.Error(
                    "User sheet not configured. Please sign in again.")
                else -> return@withContext SyncResult.Error("Failed to read from cloud")
            }

            val allRows = parseDailyStockRows(rawRows)
            val rows = when {
                dateFrom == null && dateTo == null -> allRows
                else -> allRows.filter { row ->
                    (dateFrom == null || row.date >= dateFrom) &&
                    (dateTo   == null || row.date <= dateTo)
                }
            }

            if (rows.isEmpty()) return@withContext SyncResult.DailyStockDownSync(0)

            val kept = database.dailyStockDao().mergeFromCloud(rows)
            Log.d(TAG, "Daily stock down-sync: restored ${rows.size - kept} rows, kept $kept local")
            SyncResult.DailyStockDownSync(rows.size - kept, kept)

        } } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "downloadDailyStockFromCloud failed: ${e.message}")
            SyncResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Parse a date string from any format the cloud/Sheets may return into "yyyy-MM-dd".
     *
     * Priority order:
     *   1. ISO  "yyyy-MM-dd" or non-padded "yyyy-M-d"          (YYYY first → unambiguous)
     *   2. Slash "d/M/yyyy", "d/M/yy", "dd/MM/yyyy", etc.     (always DD/MM — India-only)
     *   3. Dash  "d-M-yyyy", "dd-MM-yyyy" etc. where year > 31 (DD-MM-YYYY)
     *   4. Excel / Sheets numeric date serial
     *
     * Returns null → row is silently skipped.
     */
    private fun parseSyncDate(raw: String): String? = StrictDate.parse(raw)

    private fun parseDailyStockRows(rawRows: List<List<Any>>): List<DailyStock> {
        val result = mutableListOf<DailyStock>()
        rawRows.forEachIndexed { idx, row ->
            try {
                val rawDate = row.getOrNull(0)?.toString().orEmpty().trim()
                val productCode = row.getOrNull(1)?.toString().orEmpty().trim()
                if (rawDate.isEmpty() || productCode.isEmpty()) return@forEachIndexed

                val parsedDate = parseSyncDate(rawDate) ?: return@forEachIndexed

                val isCommitted = row.getOrNull(23)?.toString().orEmpty().trim().uppercase() == "YES"
                if (!isCommitted) return@forEachIndexed

                fun int(col: Int) = row.getOrNull(col)?.toString()?.toDoubleOrNull()?.toInt() ?: 0
                fun dbl(col: Int) = row.getOrNull(col)?.toString()?.toDoubleOrNull() ?: 0.0

                // Column Y may be entirely absent on a sheet uploaded before this
                // column existed — default false rather than guess per-row; a
                // per-row open/sale check can't tell "this day is the baseline"
                // from "this one product had zero sales today". Recovery instead
                // goes through DailyStockRepository.getLatestOpeningStockDateOrHeal(),
                // which uses a whole-day aggregate to find and re-flag the real date.
                val openingStockCell = row.getOrNull(24)?.toString()?.trim()
                val isOpeningStock = openingStockCell?.uppercase() == "YES"

                result.add(DailyStock(
                    date = parsedDate, productCode = productCode,
                    openQq  = int(2),  openPp  = int(3),  openNn  = int(4),  openDd  = int(5),
                    closeQq = int(6),  closePp = int(7),  closeNn = int(8),  closeDd = int(9),
                    saleQq  = int(10), salePp  = int(11), saleNn  = int(12), saleDd  = int(13),
                    priceQq = dbl(14), pricePp = dbl(15), priceNn = dbl(16), priceDd = dbl(17),
                    amountQq = dbl(18), amountPp = dbl(19), amountNn = dbl(20), amountDd = dbl(21),
                    saleAmount = dbl(22), isCommitted = true, syncStatus = SyncStatus.SYNCED,
                    isOpeningStock = isOpeningStock
                ))
            } catch (e: Exception) {
                Log.w(TAG, "Skipping malformed DailyStock row $idx: ${e.message}")
            }
        }
        return result
    }

    // ── Reconciliation down-sync (cloud → local DB, via CF) ──────────────────

    suspend fun downloadReconciliationFromCloud(): SyncResult =
        downloadReconciliationFromCloud(dateFrom = null, dateTo = null)

    suspend fun downloadReconciliationFromCloud(
        dateFrom: String?,
        dateTo:   String?
    ): SyncResult = withContext(Dispatchers.IO) {

        if (!isNetworkAvailable())
            return@withContext SyncResult.Error("No internet connection")

        try { syncLock.withLock {
            val sheetResult = CloudFunctionClient().syncUserSheet("read_all")

            val rawRows = when (sheetResult) {
                is SyncSheetResult.AllRead -> sheetResult.daySummary
                is SyncSheetResult.NoSheet -> return@withContext SyncResult.Error(
                    "User sheet not configured. Please sign in again.")
                else -> return@withContext SyncResult.Error("Failed to read reconciliation from cloud")
            }

            val allRows = parseDaySummaryRows(rawRows)
            val rows = when {
                dateFrom == null && dateTo == null -> allRows
                else -> allRows.filter { row ->
                    (dateFrom == null || row.date >= dateFrom) &&
                    (dateTo   == null || row.date <= dateTo)
                }
            }

            if (rows.isEmpty()) return@withContext SyncResult.ReconciliationDownSync(0)

            val kept = database.dayReconciliationDao().mergeFromCloud(rows)
            Log.d(TAG, "Reconciliation down-sync: restored ${rows.size - kept} rows, kept $kept local")
            SyncResult.ReconciliationDownSync(rows.size - kept, kept)

        } } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "downloadReconciliationFromCloud failed: ${e.message}")
            SyncResult.Error(e.message ?: "Unknown error")
        }
    }

    private fun parseDaySummaryRows(rawRows: List<List<Any>>): List<DayReconciliation> {
        val result = mutableListOf<DayReconciliation>()
        rawRows.forEachIndexed { idx, row ->
            try {
                val colA = row.getOrNull(0)?.toString().orEmpty().trim()
                val rawDate: String
                val colOffset: Int
                if (colA.isNotEmpty()) { rawDate = colA; colOffset = 0 }
                else { rawDate = row.getOrNull(1)?.toString().orEmpty().trim(); colOffset = 1 }
                if (rawDate.isEmpty()) return@forEachIndexed

                val date = parseSyncDate(rawDate) ?: return@forEachIndexed

                fun dbl(col: Int) = row.getOrNull(col + colOffset)?.toString()?.toDoubleOrNull() ?: 0.0

                result.add(DayReconciliation(
                    date           = date,
                    totalDaySales  = dbl(1),
                    upiReceipts    = dbl(2),
                    dayExpenses    = dbl(3),
                    cashForDeposit = dbl(4),
                    notes          = row.getOrNull(5 + colOffset)?.toString().orEmpty(),
                    // Legacy rows (date in col B) are re-sent so the sheet gets the date in col A
                    syncStatus     = if (colOffset == 1) SyncStatus.PENDING_UPSERT else SyncStatus.SYNCED,
                    lastModified   = System.currentTimeMillis()
                ))
            } catch (e: Exception) {
                Log.w(TAG, "Skipping malformed DaySummary row $idx: ${e.message}")
            }
        }
        return result
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

    // ── Account deletion ─────────────────────────────────────────────────────

    /**
     * Clears all user inventory rows from the cloud worksheet via the syncUserSheet CF.
     * Header rows are preserved. Called during account deletion.
     */
    /** The CF resolves the sheet from Firestore, so this runs even when no sheet id is cached locally. */
    suspend fun deleteAllCloudData(): Boolean = withContext(Dispatchers.IO) {
        if (UserRole.isViewer(context)) return@withContext true
        return@withContext when (syncLock.withLock { CloudFunctionClient().syncUserSheet("clear_all") }) {
            is SyncSheetResult.Deleted -> { Log.i(TAG, "Cloud data cleared via CF"); true }
            is SyncSheetResult.NoSheet -> { Log.i(TAG, "No sheet to clear"); true }
            else -> { Log.e(TAG, "deleteAllCloudData via CF failed"); false }
        }
    }

    /**
     * Soft-deletes the user's rows in both registry tabs (UserRegistry + AppRequests)
     * via the deleteUserRegistration Cloud Function, and records a deletion event in
     * the admin-side account_deletions Firestore collection for repeat-registration
     * detection.
     *
     * Non-fatal: failure here does not block account deletion.
     */
    suspend fun deleteRegistrationRecord(email: String): Boolean = withContext(Dispatchers.IO) {
        val user = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
            ?: return@withContext false.also { Log.w(TAG, "deleteRegistrationRecord: no current user") }
        val ok = CloudFunctionClient().deleteUserRegistration(user.uid, email)
        if (ok) Log.i(TAG, "deleteRegistrationRecord: registry soft-deleted")
        else    Log.w(TAG, "deleteRegistrationRecord: CF call failed (non-fatal)")
        ok
    }

    // ── Network check ─────────────────────────────────────────────────────────

    fun isNetworkAvailable(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                             caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
        } catch (e: Exception) { false }
    }

    // ── Result types ──────────────────────────────────────────────────────────

    sealed class SyncResult {
        data class Success(
            val productsCount:  Int,
            val purchasesCount: Int,
            val stockCount:     Int
        ) : SyncResult()

        data class PurchaseDownSyncPreview(
            val newRows:       List<Purchase>,
            val dupRows:       List<Purchase>,
            val notFoundCodes: Set<String> = emptySet()
        ) : SyncResult()

        data class PurchaseDownSync(
            val inserted:      Int,
            val replaced:      Int,
            val notFoundCodes: Set<String> = emptySet()
        ) : SyncResult()

        data class Error(val message: String) : SyncResult()

        data class DailyStockDownSync(val count: Int, val kept: Int = 0) : SyncResult()

        data class ReconciliationDownSync(val count: Int, val kept: Int = 0) : SyncResult()
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
                    if (result.message == SyncCoordinator.SYNC_BUSY) return Result.retry()
                    Log.e("SyncWorker", "Background sync failed: ${result.message}")
                    ErrorLogger.log(applicationContext, "Sync/Background",
                        "Background sync failed: ${result.message}")
                    Result.retry()
                }
                else -> Result.success()
            }
        } catch (e: CancellationException) {
            // WorkManager cancelled this job itself (constraints no longer met,
            // stop requested, timed out, or superseded) — it already knows the
            // outcome; rethrow instead of masking it as a retry-able error.
            throw e
        } catch (e: Exception) {
            Log.e("SyncWorker", "Background sync error: ${e.message}")
            ErrorLogger.log(applicationContext, "Sync/Background",
                "Background sync exception", e)
            Result.retry()
        }
    }
}
