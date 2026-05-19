package com.simhadri.winentry.sync

import android.content.Context
import android.util.Log
import com.simhadri.winentry.data.entity.DailyStock
import com.simhadri.winentry.data.entity.Product
import com.simhadri.winentry.data.entity.SyncStatus
import com.simhadri.winentry.data.entity.stockCode
import com.simhadri.winentry.data.repository.DailyStockRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Downloads test data from the master sheet (TestOB and TestCB tabs) and
 * saves it as committed DailyStock rows in the local database.
 *
 * Sheet tab column layouts (header row 1 skipped by A2: range):
 *
 * TestOB — Opening Balances (no date column; same format as Opening Stock Excel import)
 *   A=PRODUCT_TYPE  B=BRAND_CODE  C=PRODUCT_NAME(ignored)
 *   D=QQ_OB  E=PP_OB  F=NN_OB  G=DD_OB
 *
 * TestCB — Closing Balances (same columns as Daily Stock closing import; dates in col A)
 *   A=DATE_CLOSING  B=PRODUCT_TYPE  C=BRAND_CODE  D=PRODUCT_NAME(ignored)
 *   E=QQ_CLOSING  F=PP_CLOSING  G=NN_CLOSING  H=DD_CLOSING
 *
 * TestCB may have any number of products per day and blank rows between date groups.
 * The actual dates in column A are ignored for absolute values — only their sort order
 * matters. The first distinct date → obDate + 1, second → obDate + 2, etc.
 *
 * Important — trailing-zero handling:
 *   Google Sheets API omits trailing empty cells from each row.  A product row where the
 *   last quantity column is 0 may have fewer than the maximum columns.  All quantity
 *   reads use getOrNull() with a 0 default so no product is silently dropped.
 */
object TestDataImportManager {

    private const val TAG = "TestDataImport"
    private const val TAB_TEST_OB = "TestOB"
    private const val TAB_TEST_CB = "TestCB"
    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    // ── Data models ───────────────────────────────────────────────────────────

    data class TestOBRow(
        val productType: String,
        val brandCode:   String,
        val openQq: Int, val openPp: Int, val openNn: Int, val openDd: Int
    )

    /** day is a 1-based sequential offset derived from sorted unique dates in TestCB. */
    data class TestCBRow(
        val day:         Int,
        val productType: String,
        val brandCode:   String,
        val closeQq: Int, val closePp: Int, val closeNn: Int, val closeDd: Int
    )

    data class TestData(val obRows: List<TestOBRow>, val cbRows: List<TestCBRow>)

    sealed class ImportResult {
        data class Success(val obCount: Int, val cbDays: Int, val cbRows: Int) : ImportResult()
        data class Failure(val message: String) : ImportResult()
    }

    // ── Network fetch — via getMasterProducts Cloud Function ─────────────────

    suspend fun fetchTestData(context: Context): Result<TestData> = withContext(Dispatchers.IO) {
        try {
            val masterResult = CloudFunctionClient().getMasterProducts()
            if (masterResult is MasterSheetResult.NotInvited) {
                return@withContext Result.failure(
                    Exception("Your account has not been activated yet. Ask the admin to add your email to the invited users list.")
                )
            }
            if (masterResult is MasterSheetResult.Error) {
                return@withContext Result.failure(
                    Exception("Unable to reach the master sheet. Check your internet connection.")
                )
            }
            val masterData = (masterResult as MasterSheetResult.Success).data
            val obRaw = masterData.testOb
            val cbRaw = masterData.testCb

            // ── TestOB parsing ────────────────────────────────────────────────
            // Columns: A=PRODUCT_TYPE  B=BRAND_CODE  C=PRODUCT_NAME(skip)
            //          D=QQ_OB  E=PP_OB  F=NN_OB  G=DD_OB
            // getOrNull() used for all qty columns so trailing zeros don't drop the row.
            val obRows = obRaw.mapNotNull { row ->
                if (row.size < 2) return@mapNotNull null   // need at minimum type + code
                val pType = row[0].toString().trim().ifBlank { return@mapNotNull null }
                val bCode = row[1].toString().trim().ifBlank { return@mapNotNull null }
                TestOBRow(
                    productType = pType,
                    brandCode   = bCode,
                    openQq = row.getOrNull(3)?.toString()?.toIntOrNull() ?: 0,
                    openPp = row.getOrNull(4)?.toString()?.toIntOrNull() ?: 0,
                    openNn = row.getOrNull(5)?.toString()?.toIntOrNull() ?: 0,
                    openDd = row.getOrNull(6)?.toString()?.toIntOrNull() ?: 0
                )
            }

            // ── TestCB parsing ────────────────────────────────────────────────
            // Columns: A=DATE_CLOSING  B=PRODUCT_TYPE  C=BRAND_CODE  D=PRODUCT_NAME(skip)
            //          E=QQ_CLOSING  F=PP_CLOSING  G=NN_CLOSING  H=DD_CLOSING
            //
            // Col A contains real calendar dates (Google Sheets returns them as Excel serial
            // numbers with UNFORMATTED_VALUE, e.g. 46121 for 10-Apr-2026).
            // We parse each date, collect distinct dates in order, and map them to
            // sequential day offsets (1st distinct date → day 1, 2nd → day 2, …).
            // The actual calendar values are irrelevant — only their sort order matters.
            //
            // Blank rows (zero-length or empty col A) are silently skipped.

            // Step 1: parse each row into a (rawDateStr, productType, brandCode, quantities) tuple
            data class RawCB(
                val parsedDate: String,
                val productType: String,
                val brandCode: String,
                val closeQq: Int, val closePp: Int, val closeNn: Int, val closeDd: Int
            )

            val rawCbList = cbRaw.mapNotNull { row ->
                if (row.size < 3) return@mapNotNull null   // blank row or too short
                val dateRaw = row[0].toString().trim().ifBlank { return@mapNotNull null }
                val parsedDate = parseSheetDate(dateRaw) ?: return@mapNotNull null
                val pType = row[1].toString().trim().ifBlank { return@mapNotNull null }
                val bCode = row[2].toString().trim().ifBlank { return@mapNotNull null }
                RawCB(
                    parsedDate  = parsedDate,
                    productType = pType,
                    brandCode   = bCode,
                    closeQq = row.getOrNull(4)?.toString()?.toIntOrNull() ?: 0,
                    closePp = row.getOrNull(5)?.toString()?.toIntOrNull() ?: 0,
                    closeNn = row.getOrNull(6)?.toString()?.toIntOrNull() ?: 0,
                    closeDd = row.getOrNull(7)?.toString()?.toIntOrNull() ?: 0
                )
            }

            // Step 2: map each unique sorted date to a sequential day offset (1, 2, 3…)
            val dateToDay: Map<String, Int> = rawCbList
                .map { it.parsedDate }
                .distinct()
                .sorted()
                .withIndex()
                .associate { (idx, date) -> date to (idx + 1) }

            Log.d(TAG, "TestCB: ${dateToDay.size} distinct dates → ${dateToDay}")

            val cbRows = rawCbList.map { raw ->
                TestCBRow(
                    day         = dateToDay.getValue(raw.parsedDate),
                    productType = raw.productType,
                    brandCode   = raw.brandCode,
                    closeQq     = raw.closeQq,
                    closePp     = raw.closePp,
                    closeNn     = raw.closeNn,
                    closeDd     = raw.closeDd
                )
            }

            if (obRows.isEmpty()) {
                return@withContext Result.failure(
                    Exception("TestOB tab has no data rows. " +
                        "Ensure the 'TestOB' tab exists and has product opening balances from row 2.")
                )
            }

            Log.d(TAG, "Fetched: ${obRows.size} OB rows, ${cbRows.size} CB rows across ${dateToDay.size} days")
            Result.success(TestData(obRows, cbRows))
        } catch (e: Exception) {
            Log.e(TAG, "fetchTestData: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Parses a date value returned by Google Sheets API with UNFORMATTED_VALUE.
     *
     * Sheets returns date cells as Excel serial numbers (e.g. 46121 for 10-Apr-2026).
     * Also handles common string formats as a fallback.
     * Returns yyyy-MM-dd string or null if the value cannot be interpreted as a date.
     */
    private fun parseSheetDate(raw: String): String? {
        // Excel serial: Sheets UNFORMATTED_VALUE returns dates as numbers.
        // Excel epoch: Dec 30, 1899 (accounting for Excel's 1900 leap-year bug).
        val num = raw.toDoubleOrNull()
        if (num != null) {
            val serial = num.toLong()
            // Sanity range: 36526 = 2000-01-01, 54787 = 2050-01-01
            if (serial in 36526..54787) {
                return try {
                    LocalDate.of(1899, 12, 30).plusDays(serial).format(dateFmt)
                } catch (_: Exception) { null }
            }
            return null  // number outside plausible date range — skip row
        }

        // String date formats fallback (d/M/yyyy, dd-MM-yyyy, yyyy-MM-dd, …)
        val formats = listOf("d/M/yyyy", "dd/MM/yyyy", "d/M/yy", "dd/MM/yy",
                             "d-M-yyyy", "dd-MM-yyyy", "yyyy-MM-dd", "MM/dd/yyyy")
        for (fmt in formats) {
            try {
                val parsed = LocalDate.parse(raw, DateTimeFormatter.ofPattern(fmt, java.util.Locale.getDefault()))
                return parsed.format(dateFmt)
            } catch (_: Exception) {}
        }
        return null
    }

    // ── Save to Room DB ───────────────────────────────────────────────────────

    /**
     * Saves test data as committed DailyStock rows.
     *
     * OB rows are saved on [obDate] (open = close, sale = 0 — standard opening stock format).
     * CB day 1 → obDate + 1 day, day 2 → obDate + 2 days, … irrespective of the original
     * calendar dates stored in the sheet.
     */
    suspend fun importTestData(
        testData:   TestData,
        obDate:     LocalDate,
        products:   List<Product>,
        repository: DailyStockRepository
    ): ImportResult = withContext(Dispatchers.IO) {
        try {
            val productMap = buildProductMap(products)
            val now = System.currentTimeMillis()

            // ── Opening Balance rows ─────────────────────────────────────────
            val obRows = testData.obRows.mapNotNull { ob ->
                val product = resolveProduct(ob.productType, ob.brandCode, productMap) ?: run {
                    Log.w(TAG, "TestOB: no product match for type='${ob.productType}' code='${ob.brandCode}'")
                    return@mapNotNull null
                }
                DailyStock(
                    date        = obDate.format(dateFmt),
                    productCode = product.stockCode,
                    openQq = ob.openQq, openPp = ob.openPp, openNn = ob.openNn, openDd = ob.openDd,
                    closeQq = ob.openQq, closePp = ob.openPp, closeNn = ob.openNn, closeDd = ob.openDd,
                    saleQq = 0, salePp = 0, saleNn = 0, saleDd = 0,
                    priceQq = product.qqSalePrice, pricePp = product.ppSalePrice,
                    priceNn = product.nnSalePrice, priceDd = product.ddSalePrice,
                    amountQq = 0.0, amountPp = 0.0, amountNn = 0.0, amountDd = 0.0,
                    saleAmount   = 0.0,
                    isCommitted  = true,
                    syncStatus   = SyncStatus.PENDING_UPSERT,
                    lastModified = now
                )
            }
            repository.saveAllEntries(obRows)

            if (testData.cbRows.isEmpty()) {
                return@withContext ImportResult.Success(obRows.size, 0, 0)
            }

            // ── Closing Balance rows ─────────────────────────────────────────
            // Group by day offset, then process in ascending day order.
            val cbByDay = testData.cbRows.groupBy { it.day }.toSortedMap()

            // Start from OB closing values so day-1 opening = OB closing
            val prevClose: MutableMap<String, IntArray> = obRows
                .associate { it.productCode to intArrayOf(it.closeQq, it.closePp, it.closeNn, it.closeDd) }
                .toMutableMap()

            var totalCbRows = 0

            for ((dayOffset, cbList) in cbByDay) {
                val cbDate = obDate.plusDays(dayOffset.toLong()).format(dateFmt)
                val stockForDay = cbList.mapNotNull { cb ->
                    val product = resolveProduct(cb.productType, cb.brandCode, productMap) ?: run {
                        Log.w(TAG, "TestCB day $dayOffset: no match for '${cb.productType}${cb.brandCode}'")
                        return@mapNotNull null
                    }
                    val prev = prevClose[product.stockCode] ?: intArrayOf(0, 0, 0, 0)

                    val saleQq = maxOf(0, prev[0] - cb.closeQq)
                    val salePp = maxOf(0, prev[1] - cb.closePp)
                    val saleNn = maxOf(0, prev[2] - cb.closeNn)
                    val saleDd = maxOf(0, prev[3] - cb.closeDd)

                    val amtQq = saleQq * product.qqSalePrice
                    val amtPp = salePp * product.ppSalePrice
                    val amtNn = saleNn * product.nnSalePrice
                    val amtDd = saleDd * product.ddSalePrice

                    prevClose[product.stockCode] =
                        intArrayOf(cb.closeQq, cb.closePp, cb.closeNn, cb.closeDd)

                    DailyStock(
                        date        = cbDate,
                        productCode = product.stockCode,
                        openQq = prev[0], openPp = prev[1], openNn = prev[2], openDd = prev[3],
                        closeQq = cb.closeQq, closePp = cb.closePp,
                        closeNn = cb.closeNn, closeDd = cb.closeDd,
                        saleQq = saleQq, salePp = salePp, saleNn = saleNn, saleDd = saleDd,
                        priceQq = product.qqSalePrice, pricePp = product.ppSalePrice,
                        priceNn = product.nnSalePrice, priceDd = product.ddSalePrice,
                        amountQq = amtQq, amountPp = amtPp, amountNn = amtNn, amountDd = amtDd,
                        saleAmount   = amtQq + amtPp + amtNn + amtDd,
                        isCommitted  = true,
                        syncStatus   = SyncStatus.PENDING_UPSERT,
                        lastModified = now
                    )
                }
                repository.saveAllEntries(stockForDay)
                totalCbRows += stockForDay.size
            }

            Log.d(TAG, "Import done: ${obRows.size} OB, ${cbByDay.size} CB days, $totalCbRows CB entries")
            ImportResult.Success(
                obCount = obRows.size,
                cbDays  = cbByDay.size,
                cbRows  = totalCbRows
            )
        } catch (e: Exception) {
            Log.e(TAG, "importTestData: ${e.message}")
            ImportResult.Failure(e.message ?: "Unexpected error during import")
        }
    }

    // ── Product resolution ────────────────────────────────────────────────────

    /**
     * Resolves a product by trying multiple key variants: direct, stripped leading zeros,
     * and zero-padded to 4 digits. Mirrors DailyStockImportHelper lookup strategy.
     */
    private fun resolveProduct(
        productType: String,
        brandCode:   String,
        productMap:  Map<String, Product>
    ): Product? {
        val direct  = "$productType$brandCode"
        val stripped = "$productType${brandCode.trimStart('0').ifEmpty { "0" }}"
        val padded   = "$productType${brandCode.padStart(4, '0')}"
        return productMap[direct] ?: productMap[stripped] ?: productMap[padded]
    }

    /**
     * Builds a lookup map with all key variants per product (primary code + aliases,
     * each in direct / stripped / padded form).
     */
    private fun buildProductMap(products: List<Product>): Map<String, Product> {
        val map = mutableMapOf<String, Product>()
        products.forEach { p ->
            fun register(type: String, code: String) {
                map["$type$code"] = p
                map["$type${code.trimStart('0').ifEmpty { "0" }}"] = p
                map["$type${code.padStart(4, '0')}"] = p
            }
            register(p.productType, p.brandCode)
            if (p.aliases.isNotBlank()) {
                p.aliases.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    .forEach { alias -> register(p.productType, alias) }
            }
        }
        return map
    }
}
