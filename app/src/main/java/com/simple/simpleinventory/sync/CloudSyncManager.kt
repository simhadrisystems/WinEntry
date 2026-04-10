package com.simple.simpleinventory.sync

import android.content.Context
import android.util.Log
import com.simple.simpleinventory.data.entity.DailyStock
import com.simple.simpleinventory.data.entity.Product
import com.simple.simpleinventory.data.entity.stockCode
import com.simple.simpleinventory.data.entity.DayReconciliation
import com.simple.simpleinventory.util.DateUtils
import com.simple.simpleinventory.data.entity.Purchase
import com.simple.simpleinventory.data.entity.SyncStatus
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.SheetsScopes
import com.google.api.services.sheets.v4.model.BatchUpdateSpreadsheetRequest
import com.google.api.services.sheets.v4.model.BatchUpdateValuesRequest
import com.google.api.services.sheets.v4.model.DeleteDimensionRequest
import com.google.api.services.sheets.v4.model.DimensionRange
import com.google.api.services.sheets.v4.model.Request
import com.google.api.services.sheets.v4.model.ValueRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Google Sheets Sync Manager — v3 (quota-safe batching)
 *
 * Google Sheets API quota: 60 write requests per minute per user.
 * Previous versions made one API call per row — 400 rows = 400 requests,
 * triggering 429 RATE_LIMIT_EXCEEDED after the first 60 rows.
 *
 * This version batches all operations so that syncing any number of rows
 * costs at most 3 API write requests total:
 *   1 x batchUpdate  — all rows that already exist in the sheet (UPDATE)
 *   1 x append       — all new rows (INSERT)
 *   1 x batchUpdate  — row deletions (DELETE, purchases only)
 *
 * ── Purchases sheet column layout ────────────────────────────────────────────
 * A=TxnId  B=Date  C=ProductCode  D=ProductName  E=InvoiceNo  F=Supplier
 * G=QQ_Boxes  H=QQ_Loose  I=QQ_Total  J=QQ_Price  K=QQ_Cost
 * L=PP_Boxes  M=PP_Loose  N=PP_Total  O=PP_Price  P=PP_Cost
 * Q=NN_Boxes  R=NN_Loose  S=NN_Total  T=NN_Price  U=NN_Cost
 * V=DD_Boxes  W=DD_Loose  X=DD_Total  Y=DD_Price  Z=DD_Cost
 * AA=TotalCost  AB=Notes
 *
 * ── DailyStock sheet column layout ───────────────────────────────────────────
 * A=date          B=productCode
 * C=openQq        D=openPp        E=openNn        F=openDd
 * G=closeQq       H=closePp       I=closeNn       J=closeDd
 * K=saleQq        L=salePp        M=saleNn        N=saleDd
 * O=priceQq       P=pricePp       Q=priceNn       R=priceDd
 * S=amountQq      T=amountPp      U=amountNn      V=amountDd
 * W=saleAmount    X=isCommitted
 */
class CloudSyncManager(
    private val context: Context,
    private val spreadsheetId: String
) {

    private var sheetsService: Sheets? = null

    companion object {
        private const val TAG = "CloudSyncManager"
        private const val TAB_PURCHASES       = "Purchases"
        private const val TAB_PRODUCTS        = "Products"
        private const val TAB_DAILYSTOCK      = "DailyStock"
        private const val TAB_DAYSUMMARY      = "DaySummary"
        // User-maintained import inbox — same format as the Excel import file.
        // User fills rows in from desktop; app reads and clears after import.
        private const val TAB_PURCHASE_IMPORT = "PurchaseImport"
        private const val FULL_RANGE     = "$TAB_PURCHASES!A:AB"
        private const val TXNID_SCAN_RANGE = "$TAB_PURCHASES!A:A"
    }

    // Cached numeric sheet ID for the Purchases tab (needed for row deletion).
    private var purchasesSheetId: Int? = null

    // ── Initialisation ────────────────────────────────────────────────────────

    fun initialize(): Boolean {
        return try {
            val account = GoogleSignIn.getLastSignedInAccount(context)
            if (account != null) {
                initializeSheetsService(account)
                true
            } else {
                Log.w(TAG, "No Google account signed in")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize: ${e.message}")
            false
        }
    }

    private fun initializeSheetsService(account: GoogleSignInAccount) {
        val credential = GoogleAccountCredential.usingOAuth2(
            context,
            listOf(SheetsScopes.SPREADSHEETS)
        ).apply { selectedAccount = account.account }

        sheetsService = Sheets.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        ).setApplicationName("Simple Inventory").build()
    }

    // ── Purchase sync (quota-safe batching) ───────────────────────────────────

    /**
     * Process all pending purchases using at most 3 API write requests.
     *
     * Step 1 (READ):   Build txnId index from col A — 1 read request.
     * Step 2 (WRITE):  batch-append all new rows — 1 write request.
     * Step 3 (WRITE):  batchUpdate all changed rows — 1 write request.
     * Step 4 (WRITE):  batchUpdate deletes (row removal) — 1 write request.
     *
     * Total: 1 read + up to 3 writes regardless of how many rows are pending.
     */
    suspend fun syncPendingPurchases(
        pendingPurchases: List<Purchase>
    ): SyncResult = withContext(Dispatchers.IO) {

        val result = SyncResult()
        if (pendingPurchases.isEmpty()) return@withContext result

        val service = sheetsService ?: run {
            Log.w(TAG, "Sheets service not initialised")
            result.failAll(pendingPurchases)
            return@withContext result
        }

        if (purchasesSheetId == null) {
            purchasesSheetId = fetchPurchasesSheetId(service)
        }

        val txnIdIndex: Map<String, Int> = buildTxnIdIndex(service)

        // Partition into three buckets — no API calls yet
        val toInsert = mutableListOf<Purchase>()
        val toUpdate = mutableListOf<Pair<Purchase, Int>>()
        val toDelete = mutableListOf<Pair<Purchase, Int>>()

        for (purchase in pendingPurchases) {
            when (purchase.syncStatus) {
                SyncStatus.PENDING_INSERT,
                SyncStatus.SYNC_ERROR -> {
                    if (txnIdIndex.containsKey(purchase.txnId)) {
                        toUpdate.add(purchase to txnIdIndex[purchase.txnId]!!)
                    } else {
                        toInsert.add(purchase)
                    }
                }
                SyncStatus.PENDING_UPDATE -> {
                    val rowNumber = txnIdIndex[purchase.txnId]
                    if (rowNumber != null) toUpdate.add(purchase to rowNumber)
                    else toInsert.add(purchase)
                }
                SyncStatus.PENDING_DELETE -> {
                    val rowNumber = txnIdIndex[purchase.txnId]
                    if (rowNumber != null) {
                        toDelete.add(purchase to rowNumber)
                    } else {
                        // Never reached Sheets — confirm local hard-delete directly
                        result.hardDelete.add(purchase.id)
                    }
                }
            }
        }

        Log.d(TAG, "Purchases: ${toInsert.size} inserts, ${toUpdate.size} updates, ${toDelete.size} deletes")

        // Batch INSERT — 1 API call for all new rows
        if (toInsert.isNotEmpty()) {
            try {
                val values = toInsert.map { it.toSheetRow() }
                val body = ValueRange().setValues(values)
                service.spreadsheets().values()
                    .append(spreadsheetId, FULL_RANGE, body)
                    .setValueInputOption("USER_ENTERED")
                    .setInsertDataOption("INSERT_ROWS")
                    .execute()
                toInsert.forEach { result.synced.add(it.id) }
                Log.d(TAG, "Batch-inserted ${toInsert.size} purchase rows")
            } catch (e: Exception) {
                Log.e(TAG, "Batch purchase insert failed: ${e.message}")
                toInsert.forEach { result.errors.add(it.id) }
            }
        }

        // Batch UPDATE — 1 API call for all existing rows
        if (toUpdate.isNotEmpty()) {
            try {
                val valueRanges = toUpdate.map { (purchase, rowNumber) ->
                    ValueRange()
                        .setRange("$TAB_PURCHASES!A$rowNumber:AB$rowNumber")
                        .setValues(listOf(purchase.toSheetRow()))
                }
                val body = BatchUpdateValuesRequest()
                    .setValueInputOption("USER_ENTERED")
                    .setData(valueRanges)
                service.spreadsheets().values()
                    .batchUpdate(spreadsheetId, body)
                    .execute()
                toUpdate.forEach { (p, _) -> result.synced.add(p.id) }
                Log.d(TAG, "Batch-updated ${toUpdate.size} purchase rows")
            } catch (e: Exception) {
                Log.e(TAG, "Batch purchase update failed: ${e.message}")
                toUpdate.forEach { (p, _) -> result.errors.add(p.id) }
            }
        }

        // Batch DELETE — 1 API call, rows sorted descending to prevent index shift
        if (toDelete.isNotEmpty()) {
            try {
                val sortedDeletes = toDelete.sortedByDescending { it.second }
                batchDeleteSheetRows(service, sortedDeletes.map { it.second })
                sortedDeletes.forEach { (p, _) -> result.hardDelete.add(p.id) }
                Log.d(TAG, "Batch-deleted ${sortedDeletes.size} purchase rows")
            } catch (e: Exception) {
                Log.e(TAG, "Batch purchase delete failed: ${e.message}")
                toDelete.forEach { (p, _) -> result.errors.add(p.id) }
            }
        }

        result
    }

    // ── Purchase row deletion (existing — unchanged) ──────────────────────────

    /**
     * Delete multiple rows in one batchUpdate call.
     * [rowNumbers] MUST be sorted descending — see delete bug fix notes.
     */
    private fun batchDeleteSheetRows(service: Sheets, rowNumbers: List<Int>) {
        if (rowNumbers.isEmpty()) return

        val sheetId = purchasesSheetId
            ?: throw IllegalStateException("purchasesSheetId not loaded")

        val sorted = rowNumbers.sortedDescending()

        val deleteRequests = sorted.map { rowNumber ->
            Request().setDeleteDimension(
                DeleteDimensionRequest().setRange(
                    DimensionRange()
                        .setSheetId(sheetId)
                        .setDimension("ROWS")
                        .setStartIndex(rowNumber - 1)
                        .setEndIndex(rowNumber)
                )
            )
        }

        service.spreadsheets()
            .batchUpdate(spreadsheetId, BatchUpdateSpreadsheetRequest().setRequests(deleteRequests))
            .execute()

        Log.d(TAG, "Batch-deleted rows (desc): $sorted")
    }

    // ── Purchase index helpers ────────────────────────────────────────────────

    private fun buildTxnIdIndex(service: Sheets): Map<String, Int> {
        return try {
            val response = service.spreadsheets().values()
                .get(spreadsheetId, TXNID_SCAN_RANGE)
                .execute()

            val rows = response.getValues() ?: return emptyMap()
            val index = mutableMapOf<String, Int>()

            rows.forEachIndexed { zeroBasedIndex, row ->
                val txnId = row.getOrNull(0)?.toString().orEmpty().trim()
                if (txnId.isNotEmpty() && zeroBasedIndex > 0) {
                    index[txnId] = zeroBasedIndex + 1
                }
            }
            Log.d(TAG, "TxnId index built: ${index.size} existing rows")
            index
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build txnId index: ${e.message}")
            emptyMap()
        }
    }

    private fun fetchPurchasesSheetId(service: Sheets): Int? {
        return try {
            val spreadsheet = service.spreadsheets().get(spreadsheetId).execute()
            spreadsheet.sheets
                ?.firstOrNull { it.properties.title == TAB_PURCHASES }
                ?.properties?.sheetId
                ?.also { Log.d(TAG, "Purchases sheetId = $it") }
        } catch (e: Exception) {
            Log.e(TAG, "Could not fetch sheet ID: ${e.message}")
            null
        }
    }

    // ── Daily stock sync (quota-safe batching) ────────────────────────────────

    /**
     * Sync pending DailyStock rows using at most 3 API requests total.
     *
     * Request 1 (READ):   GET cols A+B to build the composite key index.
     * Request 2 (WRITE):  batchUpdate — all rows that already exist (UPDATE).
     * Request 3 (WRITE):  append — all new rows in one payload (INSERT).
     *
     * Compared to the previous one-call-per-row approach, this reduces
     * ~400 write requests to 2-3 regardless of how many rows are pending,
     * completely eliminating the 429 quota errors.
     */
    suspend fun syncPendingDailyStock(
        pendingStock: List<DailyStock>
    ): DailyStockSyncResult = withContext(Dispatchers.IO) {

        val result = DailyStockSyncResult()
        if (pendingStock.isEmpty()) return@withContext result

        val service = sheetsService ?: run {
            Log.w(TAG, "Sheets service not initialised")
            result.failAll(pendingStock)
            return@withContext result
        }

        // Request 1 (READ): build key index — null on exception, emptyMap on empty sheet
        val stockKeyIndex: Map<String, Int>? = buildDailyStockKeyIndex(service)
        if (stockKeyIndex == null) {
            Log.e(TAG, "Key index build failed -- aborting to prevent duplicates")
            result.failAll(pendingStock)
            return@withContext result
        }

        // Partition into two buckets — no API calls yet
        val toUpdate = mutableListOf<Pair<DailyStock, Int>>()
        val toInsert = mutableListOf<DailyStock>()

        for (stock in pendingStock) {
            // Use the Sheets date serial as key — matches UNFORMATTED_VALUE read
            val compositeKey = "${DateUtils.dateStringToSerial(stock.date)}|${stock.productCode}"
            val existingRow = stockKeyIndex[compositeKey]
            if (existingRow != null) {
                toUpdate.add(stock to existingRow)
            } else {
                toInsert.add(stock)
            }
        }

        Log.d(TAG, "Daily stock: ${toUpdate.size} updates + ${toInsert.size} inserts")

        // Request 2 (WRITE): batchUpdate all existing rows — 1 API call
        if (toUpdate.isNotEmpty()) {
            try {
                val valueRanges = toUpdate.map { (stock, rowNumber) ->
                    ValueRange()
                        .setRange("$TAB_DAILYSTOCK!A$rowNumber:X$rowNumber")
                        .setValues(listOf(stock.toSheetRow()))
                }
                val body = BatchUpdateValuesRequest()
                    .setValueInputOption("USER_ENTERED")
                    .setData(valueRanges)
                service.spreadsheets().values()
                    .batchUpdate(spreadsheetId, body)
                    .execute()
                toUpdate.forEach { (stock, _) ->
                    result.synced.add(stock.date to stock.productCode)
                }
                Log.d(TAG, "Batch-updated ${toUpdate.size} daily stock rows")
            } catch (e: Exception) {
                Log.e(TAG, "Batch daily stock update failed: ${e.message}")
                toUpdate.forEach { (stock, _) ->
                    result.errors.add(stock.date to stock.productCode)
                }
            }
        }

        // Request 3 (WRITE): append all new rows — 1 API call
        if (toInsert.isNotEmpty()) {
            try {
                val values = toInsert.map { it.toSheetRow() }
                val body = ValueRange().setValues(values)
                service.spreadsheets().values()
                    .append(spreadsheetId, "$TAB_DAILYSTOCK!A:X", body)
                    .setValueInputOption("USER_ENTERED")
                    .setInsertDataOption("INSERT_ROWS")
                    .execute()
                toInsert.forEach { stock ->
                    result.synced.add(stock.date to stock.productCode)
                }
                Log.d(TAG, "Batch-inserted ${toInsert.size} daily stock rows")
            } catch (e: Exception) {
                Log.e(TAG, "Batch daily stock insert failed: ${e.message}")
                toInsert.forEach { stock ->
                    result.errors.add(stock.date to stock.productCode)
                }
            }
        }

        Log.d(TAG, "Daily stock sync done — synced: ${result.synced.size}, errors: ${result.errors.size}")
        result
    }

    private fun buildDailyStockKeyIndex(service: Sheets): Map<String, Int>? {
        return try {
            // UNFORMATTED_VALUE returns the raw underlying cell value.
            // Date cells come back as a Sheets serial number (e.g. 46087.0).
            // We use the serial number directly as the key — no conversion
            // back to a date string needed. The pending rows side converts
            // their yyyy-MM-dd date to the same serial via dateStringToSerial(),
            // so both sides of the key comparison always match.
            val response = service.spreadsheets().values()
                .get(spreadsheetId, "$TAB_DAILYSTOCK!A:B")
                .setValueRenderOption("UNFORMATTED_VALUE")
                .execute()

            val rows = response.getValues() ?: return emptyMap()
            val index = mutableMapOf<String, Int>()

            rows.forEachIndexed { zeroBasedIndex, row ->
                if (zeroBasedIndex == 0) return@forEachIndexed  // skip header
                val dateKey     = row.getOrNull(0)?.toString().orEmpty().trim()
                val productCode = row.getOrNull(1)?.toString().orEmpty().trim()
                // dateKey is the serial number as a string e.g. "46087.0"
                // We normalise to integer string "46087" by dropping decimals.
                val normalisedKey = DateUtils.normaliseSheetDateKey(dateKey)
                if (normalisedKey.isNotEmpty() && productCode.isNotEmpty()) {
                    index["$normalisedKey|$productCode"] = zeroBasedIndex + 1
                }
            }
            Log.d(TAG, "Daily stock key index built: ${index.size} existing rows")
            index
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build daily stock key index: ${e.message}")
            null  // null = API error; caller aborts. emptyMap = empty sheet; safe.
        }
    }


    // ── Daily stock DOWN-sync (sheet → local DB) ─────────────────────────────

    /**
     * Read committed DailyStock rows from the cloud sheet and return them
     * as a list of DailyStock objects ready to upsert into the local DB.
     * Date range filtering is handled by the caller (SyncCoordinator).
     *
     * Sheet column layout (A–X, 24 cols):
     *   A=date          B=productCode
     *   C=openQq        D=openPp        E=openNn        F=openDd
     *   G=closeQq       H=closePp       I=closeNn       J=closeDd
     *   K=saleQq        L=salePp        M=saleNn        N=saleDd
     *   O=priceQq       P=pricePp       Q=priceNn       R=priceDd
     *   S=amountQq      T=amountPp      U=amountNn      V=amountDd
     *   W=saleAmount    X=isCommitted
     *
     * Rows where isCommitted != "YES" are skipped.
     * Returns null on API error.
     */
    suspend fun readDailyStockFromSheet(): List<DailyStock>? =
        withContext(Dispatchers.IO) {
            val service = sheetsService ?: run {
                Log.w(TAG, "Sheets service not initialised")
                return@withContext null
            }
            try {
                val response = service.spreadsheets().values()
                    .get(spreadsheetId, "$TAB_DAILYSTOCK!A:X")
                    .setValueRenderOption("UNFORMATTED_VALUE")
                    .execute()

                val rows = response.getValues() ?: return@withContext emptyList()
                val result = mutableListOf<DailyStock>()

                rows.forEachIndexed { idx, row ->
                    if (idx == 0) return@forEachIndexed  // skip header
                    try {
                        val date        = row.getOrNull(0)?.toString().orEmpty().trim()
                        val productCode = row.getOrNull(1)?.toString().orEmpty().trim()
                        if (date.isEmpty() || productCode.isEmpty()) return@forEachIndexed

                        // Parse date — may come back as Sheets serial number
                        val parsedDate = if (date.contains("-")) date else {
                            val serial = date.toDoubleOrNull()?.toLong() ?: return@forEachIndexed
                            val cal = java.util.Calendar.getInstance(
                                java.util.TimeZone.getTimeZone("UTC"))
                            cal.set(1899, 11, 30, 0, 0, 0)
                            cal.set(java.util.Calendar.MILLISECOND, 0)
                            cal.add(java.util.Calendar.DATE, serial.toInt())
                            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                                .format(cal.time)
                        }

                        // Skip non-committed rows
                        val isCommitted = row.getOrNull(23)?.toString()
                            .orEmpty().trim().uppercase() == "YES"
                        if (!isCommitted) return@forEachIndexed

                        fun int(col: Int)    = row.getOrNull(col)?.toString()?.toDoubleOrNull()?.toInt() ?: 0
                        fun dbl(col: Int)    = row.getOrNull(col)?.toString()?.toDoubleOrNull() ?: 0.0

                        result.add(DailyStock(
                            date        = parsedDate,
                            productCode = productCode,
                            openQq  = int(2),  openPp  = int(3),  openNn  = int(4),  openDd  = int(5),
                            closeQq = int(6),  closePp = int(7),  closeNn = int(8),  closeDd = int(9),
                            saleQq  = int(10), salePp  = int(11), saleNn  = int(12), saleDd  = int(13),
                            priceQq = dbl(14), pricePp = dbl(15), priceNn = dbl(16), priceDd = dbl(17),
                            amountQq = dbl(18), amountPp = dbl(19),
                            amountNn = dbl(20), amountDd = dbl(21),
                            saleAmount  = dbl(22),
                            isCommitted = true,
                            syncStatus  = SyncStatus.SYNCED
                        ))
                    } catch (e: Exception) {
                        Log.w(TAG, "Skipping malformed DailyStock row $idx: ${e.message}")
                    }
                }
                Log.d(TAG, "Read ${result.size} DailyStock rows from sheet")
                result
            } catch (e: Exception) {
                Log.e(TAG, "readDailyStockFromSheet failed: ${e.message}")
                null
            }
        }

    // ── Day summary down-sync (cloud → local) ────────────────────────────────

    /**
     * Read all rows from the DaySummary tab and return them as DayReconciliation
     * objects ready for local DB insert.
     *
     * Column layout: A=Date  B=TotalDaySales  C=UPIReceipts  D=DayExpenses
     *                E=CashForDeposit  F=Notes
     *
     * Date column may come back as a Sheets serial number (same pattern as DailyStock).
     * Returns null on API error.
     */
    suspend fun readDaySummaryFromSheet(): List<DayReconciliation>? =
        withContext(Dispatchers.IO) {
            val service = sheetsService ?: run {
                Log.w(TAG, "Sheets service not initialised")
                return@withContext null
            }
            try {
                val response = service.spreadsheets().values()
                    .get(spreadsheetId, "$TAB_DAYSUMMARY!A:F")
                    .setValueRenderOption("UNFORMATTED_VALUE")
                    .execute()

                val rows = response.getValues() ?: return@withContext emptyList()
                val result = mutableListOf<DayReconciliation>()

                rows.forEachIndexed { idx, row ->
                    if (idx == 0) return@forEachIndexed  // skip header
                    try {
                        // Column A holds date in new-format rows; old-format rows have an
                        // empty column A with the date in column B — fall back accordingly.
                        val rawDate: String
                        val colOffset: Int
                        val colA = row.getOrNull(0)?.toString().orEmpty().trim()
                        if (colA.isNotEmpty()) {
                            rawDate   = colA
                            colOffset = 0
                        } else {
                            rawDate   = row.getOrNull(1)?.toString().orEmpty().trim()
                            colOffset = 1
                        }
                        if (rawDate.isEmpty()) return@forEachIndexed

                        // Date may arrive as Sheets serial number or yyyy-MM-dd string
                        val date = if (rawDate.contains("-")) rawDate else {
                            val serial = rawDate.toDoubleOrNull()?.toLong()
                                ?: return@forEachIndexed
                            val cal = java.util.Calendar.getInstance(
                                java.util.TimeZone.getTimeZone("UTC"))
                            cal.set(1899, 11, 30, 0, 0, 0)
                            cal.set(java.util.Calendar.MILLISECOND, 0)
                            cal.add(java.util.Calendar.DATE, serial.toInt())
                            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                                .format(cal.time)
                        }

                        fun dbl(col: Int) =
                            row.getOrNull(col + colOffset)?.toString()?.toDoubleOrNull() ?: 0.0

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
                Log.d(TAG, "Read ${result.size} DaySummary rows from sheet")
                result
            } catch (e: Exception) {
                Log.e(TAG, "readDaySummaryFromSheet failed: ${e.message}")
                null
            }
        }

    // ── Day summary sync (quota-safe batching) ───────────────────────────────

    /**
     * Sync pending DayReconciliation rows to the DaySummary tab.
     *
     * DaySummary sheet column layout:
     *   A=Date  B=TotalDaySales  C=UPIReceipts  D=DayExpenses
     *   E=CashForDeposit  F=Notes
     *
     * Uses the same batch pattern as DailyStock:
     *   1 read (key index) + 1 batchUpdate (existing) + 1 append (new) = max 3 requests.
     * Date col A is written USER_ENTERED so it stores as a real Sheets date.
     * Index is read with UNFORMATTED_VALUE and converted via dateStringToSerial().
     */
    suspend fun syncPendingDaySummary(
        pendingRows: List<DayReconciliation>
    ): DaySummarySyncResult = withContext(Dispatchers.IO) {

        val result = DaySummarySyncResult()
        if (pendingRows.isEmpty()) return@withContext result

        val service = sheetsService ?: run {
            Log.w(TAG, "Sheets service not initialised")
            result.failAll(pendingRows)
            return@withContext result
        }

        val keyIndex: Map<String, Int>? = buildDaySummaryKeyIndex(service)
        if (keyIndex == null) {
            Log.e(TAG, "DaySummary key index build failed -- aborting")
            result.failAll(pendingRows)
            return@withContext result
        }

        val toUpdate = mutableListOf<Pair<DayReconciliation, Int>>()
        val toInsert = mutableListOf<DayReconciliation>()

        for (row in pendingRows) {
            val key = DateUtils.dateStringToSerial(row.date)
            val existingRow = keyIndex[key]
            if (existingRow != null) toUpdate.add(row to existingRow)
            else toInsert.add(row)
        }

        Log.d(TAG, "DaySummary: ${toUpdate.size} updates + ${toInsert.size} inserts")

        if (toUpdate.isNotEmpty()) {
            try {
                val valueRanges = toUpdate.map { (row, rowNumber) ->
                    ValueRange()
                        .setRange("$TAB_DAYSUMMARY!A$rowNumber:F$rowNumber")
                        .setValues(listOf(row.toDaySummaryRow()))
                }
                val body = BatchUpdateValuesRequest()
                    .setValueInputOption("USER_ENTERED")
                    .setData(valueRanges)
                service.spreadsheets().values().batchUpdate(spreadsheetId, body).execute()
                toUpdate.forEach { (row, _) -> result.synced.add(row.date) }
                Log.d(TAG, "Batch-updated ${toUpdate.size} DaySummary rows")
            } catch (e: Exception) {
                Log.e(TAG, "DaySummary batch update failed: ${e.message}")
                toUpdate.forEach { (row, _) -> result.errors.add(row.date) }
            }
        }

        if (toInsert.isNotEmpty()) {
            try {
                val values = toInsert.map { it.toDaySummaryRow() }
                val body = ValueRange().setValues(values)
                service.spreadsheets().values()
                    .append(spreadsheetId, "$TAB_DAYSUMMARY!A:F", body)
                    .setValueInputOption("USER_ENTERED")
                    .setInsertDataOption("INSERT_ROWS")
                    .execute()
                toInsert.forEach { result.synced.add(it.date) }
                Log.d(TAG, "Batch-inserted ${toInsert.size} DaySummary rows")
            } catch (e: Exception) {
                Log.e(TAG, "DaySummary batch insert failed: ${e.message}")
                toInsert.forEach { result.errors.add(it.date) }
            }
        }

        Log.d(TAG, "DaySummary sync done — synced: ${result.synced.size}, errors: ${result.errors.size}")
        result
    }

    private fun buildDaySummaryKeyIndex(service: Sheets): Map<String, Int>? {
        return try {
            // Read A:B — date may be in column A (new format) or column B (old format
            // where column A was left empty by the original sheet structure).
            val response = service.spreadsheets().values()
                .get(spreadsheetId, "$TAB_DAYSUMMARY!A:B")
                .setValueRenderOption("UNFORMATTED_VALUE")
                .execute()
            val rows = response.getValues() ?: return emptyMap()
            val index = mutableMapOf<String, Int>()
            rows.forEachIndexed { zeroIndex, row ->
                if (zeroIndex == 0) return@forEachIndexed
                // Try column A first; fall back to column B for old-format rows
                val rawDate = row.getOrNull(0)?.toString().orEmpty().trim()
                    .takeIf { it.isNotEmpty() }
                    ?: row.getOrNull(1)?.toString().orEmpty().trim()
                val key = DateUtils.normaliseSheetDateKey(rawDate)
                if (key.isNotEmpty()) index[key] = zeroIndex + 1
            }
            Log.d(TAG, "DaySummary key index built: ${index.size} rows")
            index
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build DaySummary key index: ${e.message}")
            null
        }
    }

        // ── Products sync (unchanged) ─────────────────────────────────────────────

    /**
     * Read all rows from the Purchases tab in Sheets and return those whose
     * txnId is NOT in [existingTxnIds] as new [Purchase] objects ready for insert.
     *
     * Column layout (matches Purchase.toSheetRow()):
     *   A=txnId  B=purchaseDate  C=productCode  D=productName  E=invoiceNumber
     *   F=supplierName
     *   G=qqBoxes  H=qqLoose  I=qqTotalUnits  J=qqUnitPrice  K=qqTotalCost
     *   L=ppBoxes  M=ppLoose  N=ppTotalUnits  O=ppUnitPrice  P=ppTotalCost
     *   Q=nnBoxes  R=nnLoose  S=nnTotalUnits  T=nnUnitPrice  U=nnTotalCost
     *   V=ddBoxes  W=ddLoose  X=ddTotalUnits  Y=ddUnitPrice  Z=ddTotalCost
     *   AA=totalCost  AB=notes
     *
     * Alias codes in productCode are resolved to primary codes via [ProductCodeResolver].
     */
    /**
     * Read new purchases from the [TAB_PURCHASE_IMPORT] sheet tab.
     *
     * The tab uses exactly the same format as the Excel import file the user
     * already knows:
     *
     *   Row N+0:  INVOICE NUMBER  |  TP08726
     *   Row N+1:  DATE            |  5/2/26   (any of: d/M/yy, dd/MM/yyyy, yyyy-MM-dd)
     *   Row N+2:  Header row      (S.NO, Brand Code, Product Name, …)
     *   Row N+3+: Data rows
     *
     * Multiple shipments in one tab are supported — each starts with a row
     * whose first cell contains "INVOICE".
     *
     * After all shipments are read the DATA rows (not the headers) are cleared
     * from the sheet so the same rows are never re-imported on the next call.
     * The header/invoice/date rows of the last shipment are preserved as a
     * template so the user can start filling in the next shipment immediately.
     *
     * Deduplication: if a purchase with the same invoiceNumber + productCode +
     * purchaseDate already exists in [existingKeys] it is skipped.
     *
     * @param products     Active product list for alias resolution.
     * @param existingKeys Set of "invoiceNumber|productCode|date" already in DB.
     * @return Pair of (list of new Purchase objects, number of rows cleared).
     */
    suspend fun readPurchasesFromImportSheet(
        products:     List<Product>,
        existingKeys: Set<String>
    ): Pair<List<Purchase>, Set<String>> = withContext(Dispatchers.IO) {

        val service = sheetsService ?: return@withContext Pair(emptyList<Purchase>(), emptySet<String>())

        return@withContext try {
            // Read all rows from the import tab
            val response = service.spreadsheets().values()
                .get(spreadsheetId, "$TAB_PURCHASE_IMPORT!A1:L")
                .setValueRenderOption("FORMATTED_VALUE")
                .execute()

            val rows = response.getValues()
            if (rows.isNullOrEmpty()) return@withContext Pair(emptyList<Purchase>(), emptySet<String>())

            val productMap = buildProductLookupMap(products)
            val newPurchases    = mutableListOf<Purchase>()
            val notFoundCodes   = mutableSetOf<String>() // product codes not in DB

            // ── Parse shipments (same logic as PurchaseExcelHelper.detectShipments) ──
            data class Shipment(
                val invoiceNumber: String,
                val date: String,
                val dataStartIdx: Int,   // index in rows list
                val headerRowNum: Int    // 1-based sheet row number of INVOICE row
            )

            val shipments = mutableListOf<Shipment>()
            var i = 0
            while (i < rows.size) {
                val cell0 = rows[i].getOrNull(0)?.toString()?.trim() ?: ""
                if (cell0.uppercase().contains("INVOICE")) {
                    val invoice = rows[i].getOrNull(1)?.toString()?.trim() ?: ""
                    val dateRaw = if (i + 1 < rows.size)
                        rows[i + 1].getOrNull(1)?.toString()?.trim() ?: "" else ""
                    val date    = parseDateStr(dateRaw)
                    // data starts at i+3 (skip invoice, date, header rows)
                    shipments.add(Shipment(invoice, date, i + 3, i + 1))
                    i += 3
                } else {
                    i++
                }
            }

            // ── Process each shipment ─────────────────────────────────────────
            for ((sIdx, shipment) in shipments.withIndex()) {
                val dataEnd = if (sIdx + 1 < shipments.size)
                    shipments[sIdx + 1].headerRowNum - 2   // row before next INVOICE
                else
                    rows.size

                // Group data rows by RESOLVED PRIMARY code — not the alias from the sheet.
                // This ensures that rows with different alias codes (e.g. WC360 QQ and
                // WA360 PP) for the same product are merged into one group so all sizes
                // end up in a single Purchase object.
                val groups = mutableMapOf<String, MutableList<Map<String,Any>>>()

                for (ri in shipment.dataStartIdx until dataEnd) {
                    if (ri >= rows.size) break
                    val row   = rows[ri]
                    val brand = row.getOrNull(1)?.toString()?.trim() ?: continue
                    if (brand.isBlank()) continue
                    val type     = row.getOrNull(3)?.toString()?.trim() ?: ""
                    val rawCode  = "$type$brand"
                    // Resolve alias → primary now, so grouping always uses primary code
                    val product  = productMap[rawCode]
                    if (product == null) {
                        notFoundCodes.add(rawCode)
                        Log.w(TAG, "PurchaseImport: product not found for code=$rawCode row $ri")
                        continue
                    }
                    val groupKey = product.stockCode
                    val entry = mapOf<String, Any>(
                        "size"  to (row.getOrNull(5)?.toString()?.trim()?.uppercase() ?: ""),
                        "boxes" to (row.getOrNull(7)?.toString()?.toDoubleOrNull()?.toInt() ?: 0),
                        "units" to (row.getOrNull(8)?.toString()?.toDoubleOrNull()?.toInt() ?: 0),
                        "price" to (row.getOrNull(9)?.toString()?.toDoubleOrNull() ?: 0.0),
                        "product" to product  // carry resolved product so lookup below is trivial
                    )
                    groups.getOrPut(groupKey) { mutableListOf() }.add(entry)
                }

                // Create Purchase per product — productCode is already the primary code
                for ((productCode, sizeRows) in groups) {
                    // product is guaranteed non-null here (null rows were skipped above)
                    val product  = sizeRows.first()["product"]
                        as com.simple.simpleinventory.data.entity.Product
                    val dedupKey = "${shipment.invoiceNumber}|${productCode}|${shipment.date}"
                    if (dedupKey in existingKeys) continue

                    var qqB = 0; var qqU = 0; var qqP = 0.0
                    var ppB = 0; var ppU = 0; var ppP = 0.0
                    var nnB = 0; var nnU = 0; var nnP = 0.0
                    var ddB = 0; var ddU = 0; var ddP = 0.0

                    for (r in sizeRows) {
                        val boxes = r["boxes"] as Int
                        val units = r["units"] as Int
                        val price = r["price"] as Double
                        when (r["size"] as String) {
                            "QQ" -> { qqB += boxes; qqU += units; if (price > 0) qqP = price }
                            "PP" -> { ppB += boxes; ppU += units; if (price > 0) ppP = price }
                            "NN" -> { nnB += boxes; nnU += units; if (price > 0) nnP = price }
                            "DD" -> { ddB += boxes; ddU += units; if (price > 0) ddP = price }
                        }
                    }

                    fun tot(b: Int, u: Int, upb: Int, p: Double) =
                        com.simple.simpleinventory.data.entity.Purchase.calculateTotalUnits(b, u, upb) *
                        (if (p > 0) p else 0.0)

                    val qqTu = com.simple.simpleinventory.data.entity.Purchase.calculateTotalUnits(qqB, qqU, product.qqUnitsPerBox)
                    val ppTu = com.simple.simpleinventory.data.entity.Purchase.calculateTotalUnits(ppB, ppU, product.ppUnitsPerBox)
                    val nnTu = com.simple.simpleinventory.data.entity.Purchase.calculateTotalUnits(nnB, nnU, product.nnUnitsPerBox)
                    val ddTu = com.simple.simpleinventory.data.entity.Purchase.calculateTotalUnits(ddB, ddU, product.ddUnitsPerBox)
                    val qqFP = if (qqP > 0) qqP else product.qqPurchasePrice
                    val ppFP = if (ppP > 0) ppP else product.ppPurchasePrice
                    val nnFP = if (nnP > 0) nnP else product.nnPurchasePrice
                    val ddFP = if (ddP > 0) ddP else product.ddPurchasePrice

                    // Normalise productCode to primary
                    val primary = product.stockCode

                    val purchase = Purchase(
                        purchaseDate   = shipment.date,
                        productId      = product.id,
                        productCode    = primary,
                        productName    = product.displayName,
                        qqBoxes = qqB, qqLoose = qqU, qqUnitsPerBox = product.qqUnitsPerBox,
                        qqTotalUnits   = qqTu,
                        qqUnitPrice    = qqFP, qqTotalCost = qqTu * qqFP,
                        ppBoxes = ppB, ppLoose = ppU, ppUnitsPerBox = product.ppUnitsPerBox,
                        ppTotalUnits   = ppTu,
                        ppUnitPrice    = ppFP, ppTotalCost = ppTu * ppFP,
                        nnBoxes = nnB, nnLoose = nnU, nnUnitsPerBox = product.nnUnitsPerBox,
                        nnTotalUnits   = nnTu,
                        nnUnitPrice    = nnFP, nnTotalCost = nnTu * nnFP,
                        ddBoxes = ddB, ddLoose = ddU, ddUnitsPerBox = product.ddUnitsPerBox,
                        ddTotalUnits   = ddTu,
                        ddUnitPrice    = ddFP, ddTotalCost = ddTu * ddFP,
                        totalCost      = qqTu*qqFP + ppTu*ppFP + nnTu*nnFP + ddTu*ddFP,
                        invoiceNumber  = shipment.invoiceNumber,
                        supplierName   = "",
                        notes          = "Imported from cloud sheet",
                        syncStatus     = com.simple.simpleinventory.data.entity.SyncStatus.SYNCED,
                        isProcessed    = false,
                        isDeleted      = false
                    )
                    newPurchases.add(purchase)
                }
            }

            // Sheet rows are NEVER cleared — the PurchaseImport tab is a permanent
            // record. Deduplication by invoiceNumber|productCode|purchaseDate ensures
            // already-imported rows are silently skipped on every subsequent run.

            Log.d(TAG, "readPurchasesFromImportSheet: ${newPurchases.size} new, ${notFoundCodes.size} not found")
            Pair(newPurchases, notFoundCodes)

        } catch (e: Exception) {
            Log.e(TAG, "readPurchasesFromImportSheet failed: ${e.message}")
            Pair(emptyList<Purchase>(), emptySet<String>())
        }
    }

    /** Parse a date string in any common format to yyyy-MM-dd. */
    private fun parseDateStr(raw: String): String {
        if (raw.isBlank()) return java.text.SimpleDateFormat("yyyy-MM-dd",
            java.util.Locale.getDefault()).format(java.util.Date())
        val formats = listOf("d/M/yy","dd/MM/yy","d/M/yyyy","dd/MM/yyyy","yyyy-MM-dd","MM/dd/yy")
        for (fmt in formats) {
            try {
                val sdf = java.text.SimpleDateFormat(fmt, java.util.Locale.getDefault())
                sdf.isLenient = false
                val date = sdf.parse(raw) ?: continue
                return java.text.SimpleDateFormat("yyyy-MM-dd",
                    java.util.Locale.getDefault()).format(date)
            } catch (_: Exception) {}
        }
        return raw
    }

    /** Build product lookup map including all alias codes (mirrors PurchaseExcelHelper). */
    private fun buildProductLookupMap(products: List<Product>): Map<String, Product> {
        val map = mutableMapOf<String, Product>()
        for (p in products) {
            map[p.stockCode] = p
            if (p.aliases.isNotBlank()) {
                p.aliases.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { alias ->
                    map["${p.productType}$alias"] = p
                }
            }
        }
        return map
    }


    suspend fun syncProductsFromCloud(): Result<List<Product>> = withContext(Dispatchers.IO) {
        try {
            val service = sheetsService ?: return@withContext Result.failure(
                Exception("Sheets service not initialized")
            )

            val range = "$TAB_PRODUCTS!A2:U"
            val response = service.spreadsheets().values()
                .get(spreadsheetId, range)
                .setValueRenderOption("UNFORMATTED_VALUE")
                .execute()

            val values = response.getValues() ?: return@withContext Result.success(emptyList())

            val products = values.map { row ->
                val productType = row.getOrNull(1)?.toString() ?: ""
                val brandCode   = row.getOrNull(3)?.toString() ?: ""
                Product(
                    productName     = row.getOrNull(0)?.toString() ?: "",
                    productType     = productType,
                    category        = row.getOrNull(2)?.toString() ?: "",
                    brandCode       = brandCode,
                    qqCode          = "${productType}${brandCode}QQ",
                    ppCode          = "${productType}${brandCode}PP",
                    nnCode          = "${productType}${brandCode}NN",
                    ddCode          = "${productType}${brandCode}DD",
                    displayName     = row.getOrNull(4)?.toString() ?: "",
                    serialNo        = row.getOrNull(5)?.toString()?.toIntOrNull() ?: 1,
                    qqPurchasePrice = row.getOrNull(6)?.toString()?.toDoubleOrNull() ?: 0.0,
                    qqSalePrice     = row.getOrNull(7)?.toString()?.toDoubleOrNull() ?: 0.0,
                    ppPurchasePrice = row.getOrNull(8)?.toString()?.toDoubleOrNull() ?: 0.0,
                    ppSalePrice     = row.getOrNull(9)?.toString()?.toDoubleOrNull() ?: 0.0,
                    nnPurchasePrice = row.getOrNull(10)?.toString()?.toDoubleOrNull() ?: 0.0,
                    nnSalePrice     = row.getOrNull(11)?.toString()?.toDoubleOrNull() ?: 0.0,
                    ddPurchasePrice = row.getOrNull(12)?.toString()?.toDoubleOrNull() ?: 0.0,
                    ddSalePrice     = row.getOrNull(13)?.toString()?.toDoubleOrNull() ?: 0.0,
                    qqUnitsPerBox   = row.getOrNull(14)?.toString()?.toIntOrNull() ?: 12,
                    ppUnitsPerBox   = row.getOrNull(15)?.toString()?.toIntOrNull() ?: 24,
                    nnUnitsPerBox   = row.getOrNull(16)?.toString()?.toIntOrNull() ?: 48,
                    ddUnitsPerBox   = row.getOrNull(17)?.toString()?.toIntOrNull() ?: 96,
                    isActive        = (row.getOrNull(20)?.toString()?.toIntOrNull() ?: 1) == 1,
                    dailySortKey    = row.getOrNull(18)?.toString()?.toIntOrNull() ?: 999,
                    aliases         = row.getOrNull(19)?.toString() ?: ""
                )
            }
            Log.d(TAG, "Downloaded ${products.size} products from cloud")
            Result.success(products)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync products from cloud: ${e.message}")
            Result.failure(e)
        }
    }

    // ── Network / connection helpers ──────────────────────────────────────────

    fun isNetworkAvailable(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                    as android.net.ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            caps != null &&
                (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                 caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR))
        } catch (e: Exception) { false }
    }

    suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        try {
            val service = sheetsService ?: return@withContext false
            service.spreadsheets().values()
                .get(spreadsheetId, "$TAB_PRODUCTS!A1")
                .execute()
            Log.d(TAG, "Connection test successful")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Connection test failed: ${e.message}")
            false
        }
    }
}

// ── Extension: Purchase -> sheet row ─────────────────────────────────────────

private fun Purchase.toSheetRow(): List<Any> = listOf(
    txnId, purchaseDate, productCode, productName, invoiceNumber, supplierName,
    qqBoxes, qqLoose, qqTotalUnits, qqUnitPrice, qqTotalCost,
    ppBoxes, ppLoose, ppTotalUnits, ppUnitPrice, ppTotalCost,
    nnBoxes, nnLoose, nnTotalUnits, nnUnitPrice, nnTotalCost,
    ddBoxes, ddLoose, ddTotalUnits, ddUnitPrice, ddTotalCost,
    totalCost, notes
)

// ── Extension: DailyStock -> sheet row ───────────────────────────────────────

private fun DailyStock.toSheetRow(): List<Any> = listOf(
    date, productCode,
    openQq, openPp, openNn, openDd,
    closeQq, closePp, closeNn, closeDd,
    saleQq, salePp, saleNn, saleDd,
    priceQq, pricePp, priceNn, priceDd,
    amountQq, amountPp, amountNn, amountDd,
    saleAmount,
    if (isCommitted) "YES" else "NO"
)
// Sheet columns A..X (24 cols):
// A=date          B=productCode
// C=openQq        D=openPp        E=openNn        F=openDd
// G=closeQq       H=closePp       I=closeNn       J=closeDd
// K=saleQq        L=salePp        M=saleNn        N=saleDd
// O=priceQq       P=pricePp       Q=priceNn       R=priceDd
// S=amountQq      T=amountPp      U=amountNn      V=amountDd
// W=saleAmount    X=isCommitted

// ── SyncResult ────────────────────────────────────────────────────────────────

data class SyncResult(
    val synced:     MutableList<Long> = mutableListOf(),
    val hardDelete: MutableList<Long> = mutableListOf(),
    val errors:     MutableList<Long> = mutableListOf()
) {
    fun failAll(purchases: List<Purchase>) { errors.addAll(purchases.map { it.id }) }
    val totalProcessed get() = synced.size + hardDelete.size + errors.size
    val hasErrors       get() = errors.isNotEmpty()
}

private fun DayReconciliation.toDaySummaryRow(): List<Any> = listOf(
    date, totalDaySales, upiReceipts, dayExpenses, cashForDeposit, notes
)

data class DaySummarySyncResult(
    val synced: MutableList<String> = mutableListOf(),
    val errors: MutableList<String> = mutableListOf()
) {
    fun failAll(rows: List<DayReconciliation>) { errors.addAll(rows.map { it.date }) }
    val hasErrors get() = errors.isNotEmpty()
}

// ── DailyStockSyncResult ──────────────────────────────────────────────────────

data class DailyStockSyncResult(
    val synced: MutableList<Pair<String, String>> = mutableListOf(),
    val errors: MutableList<Pair<String, String>> = mutableListOf()
) {
    fun failAll(stocks: List<DailyStock>) {
        errors.addAll(stocks.map { it.date to it.productCode })
    }
    val hasErrors get() = errors.isNotEmpty()
}
