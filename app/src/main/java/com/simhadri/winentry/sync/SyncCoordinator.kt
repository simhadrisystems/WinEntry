package com.simhadri.winentry.sync

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.work.*
import com.simhadri.winentry.ui.auth.ErrorLogger
import com.simhadri.winentry.data.AppDatabase
import com.simhadri.winentry.data.entity.DailyStock
import com.simhadri.winentry.data.entity.DayReconciliation
import com.simhadri.winentry.data.entity.Product
import com.simhadri.winentry.data.entity.Purchase
import com.simhadri.winentry.data.entity.SyncStatus
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

        if (!isNetworkAvailable())
            return@withContext SyncResult.Error("No internet connection")

        if (getSpreadsheetId().isNullOrBlank())
            return@withContext SyncResult.Error("User sheet not configured. Please sign in again.")

        val cfClient = CloudFunctionClient()

        try {
            val purchasesCount = syncPendingPurchases(cfClient)
            val stockCount     = syncDailyStock(cfClient)
            syncDaySummary(cfClient)

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

    // ── Purchase sync (via CF) ────────────────────────────────────────────────

    private suspend fun syncPendingPurchases(cfClient: CloudFunctionClient): Int {
        val purchaseDao = database.purchaseDao()
        val pending = purchaseDao.getPendingSyncPurchases()
        if (pending.isEmpty()) {
            Log.d(TAG, "No pending purchase sync operations")
            return 0
        }

        Log.d(TAG, "Syncing ${pending.size} pending purchase operations")

        val toWrite  = pending.filter { it.syncStatus != SyncStatus.PENDING_DELETE }
        val toDelete = pending.filter { it.syncStatus == SyncStatus.PENDING_DELETE }
        var successCount = 0

        if (toWrite.isNotEmpty()) {
            val rows = toWrite.map { it.toSheetRow() }
            when (cfClient.syncUserSheet("write_purchases", rows = rows)) {
                is SyncSheetResult.Written -> {
                    toWrite.forEach { purchaseDao.markAsSynced(it.id) }
                    successCount += toWrite.size
                    Log.d(TAG, "CF wrote ${toWrite.size} purchase rows")
                }
                is SyncSheetResult.NoSheet -> {
                    Log.e(TAG, "Purchase write failed — user sheet not found in CF")
                    toWrite.forEach { purchaseDao.markSyncError(it.id) }
                }
                else -> {
                    Log.e(TAG, "CF write_purchases failed")
                    toWrite.forEach { purchaseDao.markSyncError(it.id) }
                    ErrorLogger.log(context, "Sync/Purchases",
                        "${toWrite.size} purchase(s) failed to sync to cloud — will retry on next sync")
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

    // ── Daily stock sync (via CF) ─────────────────────────────────────────────

    private suspend fun syncDailyStock(cfClient: CloudFunctionClient): Int {
        val dailyStockDao = database.dailyStockDao()
        val pending = dailyStockDao.getPendingSyncStock()
        if (pending.isEmpty()) {
            Log.d(TAG, "No pending daily stock sync operations")
            return 0
        }

        Log.d(TAG, "Syncing ${pending.size} pending daily stock rows")
        val rows = pending.map { it.toSheetRow() }

        return when (cfClient.syncUserSheet("write_daily_stock", rows = rows)) {
            is SyncSheetResult.Written -> {
                pending.forEach { dailyStockDao.markStockAsSynced(it.date, it.productCode) }
                Log.d(TAG, "CF wrote ${pending.size} daily stock rows")
                pending.size
            }
            else -> {
                pending.forEach { dailyStockDao.markStockSyncError(it.date, it.productCode) }
                Log.e(TAG, "CF write_daily_stock failed — will retry")
                ErrorLogger.log(context, "Sync/DailyStock",
                    "${pending.size} daily stock row(s) failed to sync to cloud — will retry on next sync")
                0
            }
        }
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
        val rows = pending.map { it.toDaySummaryRow() }

        when (cfClient.syncUserSheet("write_day_summary", rows = rows)) {
            is SyncSheetResult.Written -> {
                pending.forEach { dao.markAsSynced(it.date) }
                Log.d(TAG, "CF wrote ${pending.size} day summary rows")
            }
            else -> {
                pending.forEach { dao.markSyncError(it.date) }
                Log.e(TAG, "CF write_day_summary failed — will retry")
                ErrorLogger.log(context, "Sync/DaySummary",
                    "${pending.size} day summary row(s) failed to sync to cloud — will retry on next sync")
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

            val (allRows, notFoundCodes) =
                CloudSyncManager.parseImportSheetRows(importRows, products, emptySet())

            val newRows = allRows.filter {
                "${it.invoiceNumber}|${it.productCode}|${it.purchaseDate}" !in existingKeys
            }
            val dupRows = allRows.filter {
                "${it.invoiceNumber}|${it.productCode}|${it.purchaseDate}" in existingKeys
            }

            Log.d(TAG, "PurchaseImport preview: ${newRows.size} new, ${dupRows.size} dup, ${notFoundCodes.size} not found")
            SyncResult.PurchaseDownSyncPreview(newRows, dupRows, notFoundCodes)

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

            val newRows = CloudSyncManager.parsePurchasesTabRows(purchasesRows, products, existingTxnIds)

            Log.d(TAG, "Purchases tab preview: ${newRows.size} new (cloud has ${purchasesRows.size})")
            SyncResult.PurchaseDownSyncPreview(newRows, emptyList(), emptySet())

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
        return@withContext try {
            val purchaseDao = database.purchaseDao()
            var inserted = 0
            var replaced = 0

            for (p in toInsert) {
                try {
                    purchaseDao.deleteByProductIdInvoiceDate(
                        p.productId, p.invoiceNumber, p.purchaseDate
                    )
                    // Preserve txnId from cloud (Purchases tab restore); generate one only
                    // if blank (PurchaseImport admin import path).
                    val withTxnId = if (p.txnId.isBlank())
                        p.copy(txnId = Purchase.generateTxnId())
                    else p
                    // If a soft-deleted tombstone exists with the same txnId, cancel it so
                    // the next sync does not delete the just-restored cloud row.
                    if (withTxnId.txnId.isNotBlank()) {
                        purchaseDao.cancelPendingDeleteByTxnId(withTxnId.txnId)
                    }
                    purchaseDao.insert(withTxnId)
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

            // Auto-activate any inactive products that received new purchases
            val activateIds = (toInsert + toReplace)
                .map { it.productId }.filter { it > 0 }.toSet()
            if (activateIds.isNotEmpty()) {
                database.productDao().activateByIds(activateIds.toList())
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
                    .associateBy { it.brandCode }

                val merged = cloudProducts.map { cloud ->
                    val local = existing[cloud.brandCode]
                    if (local != null) {
                        cloud.copy(
                            id           = local.id,
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

        try {
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

            database.dailyStockDao().insertOrReplaceAll(rows)
            Log.d(TAG, "Daily stock down-sync: restored ${rows.size} rows")
            SyncResult.DailyStockDownSync(rows.size)

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
    private fun parseSyncDate(raw: String): String? {
        if (raw.isBlank()) return null
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)

        fun toIso(day: Int, month: Int, year: Int): String? {
            if (year < 2000 || year > 2099) return null
            if (month < 1 || month > 12) return null
            if (day < 1 || day > 31) return null
            return String.format(java.util.Locale.US, "%04d-%02d-%02d", year, month, day)
        }

        // 1. Dash-separated: determine order by size of first part
        if (raw.contains('-') && !raw.contains('/')) {
            val parts = raw.split('-').map { it.trim() }
            if (parts.size == 3) {
                val a = parts[0].toIntOrNull() ?: return null
                val b = parts[1].toIntOrNull() ?: return null
                val c = parts[2].toIntOrNull() ?: return null
                return when {
                    a > 31  -> toIso(c, b, a)    // yyyy-MM-dd or yyyy-M-d
                    c > 31  -> toIso(a, b, c)    // dd-MM-yyyy or d-M-yyyy
                    else    -> toIso(a, b, if (c < 100) 2000 + c else c)  // dd-MM-yy
                }
            }
        }

        // 2. Slash-separated: always DD/MM (India-only — never MM/DD)
        if (raw.contains('/')) {
            val parts = raw.split('/').map { it.trim() }
            if (parts.size == 3) {
                val a = parts[0].toIntOrNull() ?: return null
                val b = parts[1].toIntOrNull() ?: return null
                val c = parts[2].toIntOrNull() ?: return null
                val year = if (c < 100) 2000 + c else c
                // Disambiguate DD/MM vs MM/DD:
                //   first part > 12  → must be day   (DD/MM: only valid interpretation)
                //   second part > 12 → must be day   (MM/DD: month can't be >12)
                //   both ≤ 12        → ambiguous; Google Sheets auto-formats in MM/DD (US locale)
                val (day, month) = when {
                    a > 12  -> Pair(a, b)   // DD/MM confirmed: a is day
                    b > 12  -> Pair(b, a)   // MM/DD confirmed: b is day, a is month
                    else    -> Pair(b, a)   // ambiguous → assume MM/DD (Sheets US locale)
                }
                return toIso(day, month, year)
            }
        }

        // 3. Excel / Sheets numeric date serial
        val serial = raw.toDoubleOrNull()
        if (serial != null && serial > 40000 && serial < 60000) {
            val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            cal.set(1899, 11, 30, 0, 0, 0); cal.set(java.util.Calendar.MILLISECOND, 0)
            cal.add(java.util.Calendar.DATE, serial.toInt())
            return fmt.apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(cal.time)
        }

        return null
    }

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

        try {
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

            database.dayReconciliationDao().insertOrReplaceAll(rows)
            // Mark all as PENDING_UPSERT so next up-sync rewrites with date in col A
            database.dayReconciliationDao().markAllAsPending()
            Log.d(TAG, "Reconciliation down-sync: restored ${rows.size} rows, marked pending for re-sync")
            SyncResult.ReconciliationDownSync(rows.size)

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
                    syncStatus     = SyncStatus.SYNCED,
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
    suspend fun deleteAllCloudData(): Boolean = withContext(Dispatchers.IO) {
        if (getSpreadsheetId().isNullOrBlank()) return@withContext true
        return@withContext when (CloudFunctionClient().syncUserSheet("clear_all")) {
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
