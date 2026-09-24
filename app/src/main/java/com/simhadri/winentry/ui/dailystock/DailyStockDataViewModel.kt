package com.simhadri.winentry.ui.dailystock

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.simhadri.winentry.data.AppDatabase
import com.simhadri.winentry.data.entity.DailyStock
import com.simhadri.winentry.data.entity.Product
import com.simhadri.winentry.data.entity.ProductSizeQty
import com.simhadri.winentry.data.entity.stockCode
import com.simhadri.winentry.data.repository.DailyStockRepository
import com.simhadri.winentry.data.repository.CascadeResult
import com.simhadri.winentry.data.repository.BaselineMath
import androidx.room.withTransaction
import com.simhadri.winentry.data.repository.PurchaseRepository
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.simhadri.winentry.utils.DailyStockImportHelper
import kotlinx.coroutines.launch

/**
 * ViewModel for import, export, and data-management operations on daily stock.
 *
 * Kept separate from DailyStockViewModel so the core Fragment ViewModel
 * stays focused on the daily entry workflow.
 *
 * Schema v2: daily_stock has ONE ROW PER PRODUCT per date (brand-level productCode).
 * All 4 sizes (QQ/PP/NN/DD) and their opening/closing/sale/price/amount snapshots
 * are stored inline. No size-code rows exist.
 */
class DailyStockDataViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: DailyStockRepository
    private val purchaseRepository: PurchaseRepository

    init {
        val db = AppDatabase.getInstance(application)
        repository         = DailyStockRepository(db.productDao(), db.dailyStockDao())
        purchaseRepository = PurchaseRepository(db.purchaseDao())
    }

    // ── Import — direct DB writes ─────────────────────────────────────────────

    /**
     * Write one committed daily stock row directly (fire-and-forget).
     * Caller provides a fully-formed DailyStock object with all snapshot values.
     * Used by the import flow where values are already validated.
     */
    fun saveDailyStockDirect(stock: DailyStock) {
        viewModelScope.launch { repository.saveAllEntries(listOf(stock)) }
    }

    /**
     * Write one committed daily stock row and suspend until the DB write completes.
     * Use this during sequential import so the next day's opening balance query
     * sees the correct closing balance immediately.
     */
    suspend fun saveDailyStockDirectSuspend(stock: DailyStock) {
        repository.saveAllEntries(listOf(stock))
    }

    /**
     * Opening balance (previous committed closing) for a product on a date.
     * Returns a DailyStock row with all 4 size closings, or null if no history.
     * Used during import validation.
     */
    suspend fun getPreviousRow(productCode: String, date: String): DailyStock? =
        repository.getPreviousRow(productCode, date)

    /**
     * Get purchase quantities for a product on a specific date.
     * Reads from the Purchases table — the authoritative source.
     */
    suspend fun getPurchaseQtyFromPurchases(
        productId: Long,
        date:      String
    ): ProductSizeQty {
        val qty = purchaseRepository.getPurchaseQuantitiesForDateAndProduct(date, productId)
        return ProductSizeQty(
            qq = qty.qqTotalUnits,
            pp = qty.ppTotalUnits,
            nn = qty.nnTotalUnits,
            dd = qty.ddTotalUnits
        )
    }


    // ── Import status — survives rotation ─────────────────────────────────────

    /**
     * Holds parsed import data + anomaly analysis while user reviews confirmation dialog.
     * Survives rotation — fragment re-reads this to re-show the confirmation dialog.
     */
    data class PendingImport(
        val result:        DailyStockImportHelper.ImportResult,
        val products:      List<Product>,
        val dateRange:     String,
        val anomalyList:   List<String>,
        val isFirstUse:    Boolean = false  // true = no prior committed history
    )

    private val _pendingImport = MutableLiveData<PendingImport?>(null)
    val pendingImport: LiveData<PendingImport?> = _pendingImport

    fun clearPendingImport() { _pendingImport.value = null }

    /**
     * Returns true if no committed daily_stock rows exist in the DB.
     * Used by CB import to detect first-time use and suggest Opening Stock setup.
     */
    suspend fun hasNoCommittedHistory(): Boolean {
        return try {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            val cal = java.util.Calendar.getInstance()
            val today = sdf.format(cal.time)
            cal.add(java.util.Calendar.YEAR, -2)
            val twoYearsAgo = sdf.format(cal.time)
            val rows = repository.getAllDailyStockInRange(twoYearsAgo, today)
            rows.none { stock -> stock.isCommitted }
        } catch (_: Exception) { false }
    }

    /**
     * Parse the Excel file and run anomaly analysis — both DB and file I/O
     * happen in viewModelScope so rotation doesn't cancel them.
     * Fragment observes [pendingImport] to show the confirmation dialog.
     *
     * [treatAsOpeningStock] skips the anomaly check entirely — it's meaningless
     * there. The check flags CB < (previous committed close + that day's purchases),
     * i.e. "this would create a negative sale by continuing the existing chain."
     * An opening-stock import explicitly discards that chain (OB = CB = imported
     * qty, sale forced to 0 in [applyImportedAsOpeningStock]) — comparing a fresh
     * physical count against a stale prior closing balance isn't a real anomaly,
     * it's the expected result of however much untracked selling happened in the
     * gap before this new baseline.
     */
    fun prepareClosingImport(
        uri: android.net.Uri,
        importHelper: DailyStockImportHelper,
        treatAsOpeningStock: Boolean = false
    ) {
        if (_importStatus.value is ImportStatus.Running) return
        viewModelScope.launch {
            try {
                val products = repository.getAllProductsSync()  // all products — inactive can be imported
                if (products.isEmpty()) {
                    _importStatus.postValue(ImportStatus.Error(
                        "No products available. Add products first."))
                    return@launch
                }

                // Parse Excel — fast, no DB
                val result = importHelper.importClosingOnly(uri, products)
                if (!result.success) {
                    _importStatus.postValue(ImportStatus.Error(result.message))
                    return@launch
                }

                // Date range string
                val dates = result.closingData.values.map { it.date }.toSortedSet()
                val sdf   = java.text.SimpleDateFormat("dd/MM/yy", java.util.Locale.getDefault())
                val dbFmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                fun fmt(d: String) = try { sdf.format(dbFmt.parse(d)!!) } catch (_: Exception) { d }
                val dateRange = when {
                    dates.isEmpty()  -> "unknown dates"
                    dates.size == 1  -> fmt(dates.first())
                    else             -> "${fmt(dates.first())} → ${fmt(dates.last())}"
                }

                // Anomaly check — needs DB. Skipped for opening-stock imports (see doc above).
                val anomalyList = mutableListOf<String>()
                if (!treatAsOpeningStock) {
                    result.closingData.values.sortedBy { it.date }.forEach { ci ->
                        val product = products.find {
                            it.productType == ci.productType && it.brandCode == ci.brandCode
                        } ?: return@forEach
                        val ob      = repository.getOpeningFor(product.stockCode, ci.date)
                        val pqRaw   = purchaseRepository.getPurchaseQuantitiesForDateAndProduct(
                            ci.date, product.id)
                        data class Chk(val ob: Int, val pq: Int, val cb: Int, val lbl: String)
                        listOf(
                            Chk(ob[0], pqRaw.qqTotalUnits, ci.qqClosing, "QQ"),
                            Chk(ob[1], pqRaw.ppTotalUnits, ci.ppClosing, "PP"),
                            Chk(ob[2], pqRaw.nnTotalUnits, ci.nnClosing, "NN"),
                            Chk(ob[3], pqRaw.ddTotalUnits, ci.ddClosing, "DD")
                        ).forEach { sz ->
                            val sale = sz.ob + sz.pq - sz.cb
                            if (sale < 0) anomalyList.add(
                                "${product.displayName}  ${sz.lbl}  ${fmt(ci.date)}" +
                                "  |  OB=${sz.ob} PQ=${sz.pq} CB=${sz.cb} → Sale=$sale"
                            )
                        }
                    }
                }

                val firstUse = hasNoCommittedHistory()
                _pendingImport.postValue(PendingImport(result, products, dateRange, anomalyList, firstUse))
            } catch (e: Exception) {
                _importStatus.postValue(ImportStatus.Error(e.message ?: "Parse failed"))
            }
        }
    }

    sealed class ImportStatus {
        object Idle                                        : ImportStatus()
        data class Running(val message: String)            : ImportStatus()
        data class Success(val count: Int, val dates: Int, val negativeDates: List<String> = emptyList()) : ImportStatus()
        data class Error(val message: String)              : ImportStatus()
    }

    private val _importStatus = MutableLiveData<ImportStatus>(ImportStatus.Idle)
    val importStatus: LiveData<ImportStatus> = _importStatus

    fun clearImportStatus() { _importStatus.value = ImportStatus.Idle }

    // ── Closing balance import (viewModelScope — survives rotation) ───────────

    fun applyImportedClosingData(
        result:   DailyStockImportHelper.ImportResult,
        products: List<Product>
    ) {
        if (_importStatus.value is ImportStatus.Running) return
        _importStatus.value = ImportStatus.Running("Saving closing balances…")
        viewModelScope.launch {
            try {
                var updatedRecords = 0
                val negatives = sortedSetOf<String>()
                val written = mutableListOf<DailyStock>()
                val sortedRecords = result.closingData.values.sortedBy { it.date }
                // All or nothing; each day's OB reads the previous day's CB written just before it
                AppDatabase.getInstance(getApplication()).withTransaction {
                for (ci in sortedRecords) {
                    val product = products.find {
                        it.productType == ci.productType && it.brandCode == ci.brandCode
                    } ?: continue
                    val ob      = repository.getOpeningFor(product.stockCode, ci.date)
                    val pqRaw   = purchaseRepository.getPurchaseQuantitiesForDateAndProduct(
                        ci.date, product.id)
                    val stored  = repository.getDailyStockRaw(ci.date, product.stockCode)?.takeIf { it.isCommitted }
                    val cb = intArrayOf(ci.qqClosing, ci.ppClosing, ci.nnClosing, ci.ddClosing)
                    val pq = intArrayOf(pqRaw.qqTotalUnits, pqRaw.ppTotalUnits, pqRaw.nnTotalUnits, pqRaw.ddTotalUnits)
                    val sale = IntArray(4) { ob[it] + pq[it] - cb[it] }
                    if (sale.any { it < 0 }) negatives += ci.date
                    val row = BaselineMath.withSale(DailyStock(
                        date=ci.date, productCode=product.stockCode,
                        openQq=ob[0], openPp=ob[1], openNn=ob[2], openDd=ob[3],
                        closeQq=cb[0], closePp=cb[1], closeNn=cb[2], closeDd=cb[3],
                        priceQq=stored?.priceQq ?: product.qqSalePrice, pricePp=stored?.pricePp ?: product.ppSalePrice,
                        priceNn=stored?.priceNn ?: product.nnSalePrice, priceDd=stored?.priceDd ?: product.ddSalePrice,
                        isCommitted=true
                    ), sale)
                    repository.saveAllEntries(listOf(row))
                    written += row
                    updatedRecords++
                }
                // The day after the last imported date takes its opening from the imported closing
                val lastPerProduct = written.groupBy { it.productCode }.values.map { rows -> rows.maxBy { it.date } }
                negatives += repository.cascadeRecalculate(lastPerProduct, products).negativeSaleDates
                }
                val dateCount = result.closingData.values.map{it.date}.toSortedSet().size
                _importStatus.postValue(ImportStatus.Success(updatedRecords, dateCount, negatives.toList()))
            } catch (e: Exception) {
                _importStatus.postValue(ImportStatus.Error(e.message ?: "Import failed"))
            }
        }
    }

    // ── Generic import: purchase/sale/closing data (viewModelScope) ──────────


    // ── Export ────────────────────────────────────────────────────────────────

    /**
     * Read all committed closing balances for a date range.
     * Returns rows sorted by date then product display order, ready for Excel.
     * With schema v2 each DailyStock row has all 4 sizes — no aggregation needed.
     */
    suspend fun getClosingRowsForExport(
        startDate: String,
        endDate:   String
    ): List<ExportClosingRow> {
        val rows     = mutableListOf<ExportClosingRow>()
        val products = repository.getAllProductsSync()  // all products — inactive still exportable

        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        var currentDate = startDate

        while (currentDate <= endDate) {
            // One query per date — each row is a full product (all 4 sizes)
            val stockByCode = repository.getAllDailyStockForDate(currentDate)
                .filter { it.isCommitted }
                .associateBy { it.productCode }

            // Emit in product sort order
            products.forEach { product ->
                val stock = stockByCode[product.stockCode]
                if (stock != null) {
                    rows.add(
                        ExportClosingRow(
                            date        = currentDate,
                            productType = product.productType,
                            brandCode   = product.brandCode,
                            productName = product.displayName,
                            qq          = stock.closeQq,
                            pp          = stock.closePp,
                            nn          = stock.closeNn,
                            dd          = stock.closeDd
                        )
                    )
                }
            }

            val cal = java.util.Calendar.getInstance().apply {
                time = sdf.parse(currentDate)!!
                add(java.util.Calendar.DAY_OF_MONTH, 1)
            }
            currentDate = sdf.format(cal.time)
        }
        return rows
    }

    data class ExportClosingRow(
        val date:        String,
        val productType: String,
        val brandCode:   String,
        val productName: String,
        val qq: Int,
        val pp: Int,
        val nn: Int,
        val dd: Int
    )

    // ── Data management ───────────────────────────────────────────────────────

    /** Clear all daily stock rows for a specific date. */
    /** All distinct dates with at least one committed row — for Opening Stock manager. */
    suspend fun getCommittedDates(): List<String> =
        repository.getCommittedDates()

    /** The earliest committed date — the true start of stock history. */
    suspend fun getEarliestCommittedDate(): String? =
        repository.getEarliestCommittedDate()

    /** The most recent committed date across all products — bounds new re-baseline dates. */
    suspend fun getLatestCommittedDate(): String? =
        repository.getLatestCommittedDate()

    /** The ACTIVE opening stock date — the most recent re-baseline. */
    suspend fun getLatestOpeningStockDate(): String? =
        repository.getLatestOpeningStockDate()

    /** Same as above, with a one-time self-heal for legacy/restored data — see repository doc. */
    suspend fun getLatestOpeningStockDateOrHeal(): String? =
        repository.getLatestOpeningStockDateOrHeal()

    /** All dates ever marked as an opening-stock baseline, most recent (active) first. */
    suspend fun getAllOpeningStockDates(): List<String> =
        repository.getAllOpeningStockDates()

    suspend fun getBaselineCandidates() = repository.getBaselineCandidates()

    /** Live purchase quantities for [date], keyed by stockCode as [qq, pp, nn, dd]. */
    suspend fun getPurchaseQtyByStockCode(date: String, products: List<Product>): Map<String, IntArray> {
        val bySize = purchaseRepository.getAllPurchaseQuantitiesByCodeForDate(date, products)
        return products.associate { p ->
            p.stockCode to intArrayOf(
                bySize[p.qqCode] ?: 0, bySize[p.ppCode] ?: 0,
                bySize[p.nnCode] ?: 0, bySize[p.ddCode] ?: 0
            )
        }
    }

    suspend fun planOpeningStockSave(
        date: String, products: List<Product>, newOb: Map<String, IntArray>, isNewBaseline: Boolean
    ) = repository.planOpeningStockSave(
        date, products, newOb, getPurchaseQtyByStockCode(date, products), isNewBaseline
    )

    suspend fun applyOpeningStockSave(
        date: String, changes: List<DailyStockRepository.BaselineChange>, products: List<Product>
    ) = repository.applyOpeningStockSave(date, changes, products)

    suspend fun setActiveBaseline(date: String): CascadeResult {
        val products = repository.getActiveProductsSortedSync()
        return repository.setActiveBaseline(date, products, getPurchaseQtyByStockCode(date, products))
    }

    suspend fun isBaselineDate(date: String) = repository.isBaselineDate(date)

    /** Suspend version — caller awaits DB completion before reloading UI. */
    suspend fun clearDateDataAwait(date: String): CascadeResult =
        repository.clearDateData(date, getPurchaseQtyByStockCode(date, repository.getAllProductsSync()))

    /** Clear the entire daily_stock table. */
    fun clearAllData() {
        viewModelScope.launch { repository.clearAllData() }
    }

    /** Suspend version — caller awaits DB completion before reloading UI. */
    suspend fun clearAllDataAwait(alsoCloud: Boolean) = repository.clearAllData(alsoCloud)

    suspend fun clearProductDataAwait(date: String, productCode: String) =
        repository.deleteDailyStock(date, productCode)

    suspend fun clearProductDataWithCascadeAwait(
        date:        String,
        productCode: String,
        products:    List<com.simhadri.winentry.data.entity.Product>
    ): CascadeResult = repository.clearEntryWithCascade(date, productCode, products,
        getPurchaseQtyByStockCode(date, products)[productCode] ?: IntArray(4))

    /** Deletes the active opening stock date and cascade-corrects the next committed day. */
    suspend fun clearOpeningStockWithCascadeAwait(
        date:     String,
        products: List<Product>
    ): CascadeResult = repository.clearOpeningStockWithCascade(date, products,
        getPurchaseQtyByStockCode(date, repository.getAllProductsSync()))

    /** Force all committed rows to re-sync to Google Sheets. */
    fun forceFullResync() {
        viewModelScope.launch { repository.forceFullResync() }
    }
}
