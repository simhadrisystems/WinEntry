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
                        val prevRow = repository.getPreviousRow(product.stockCode, ci.date)
                        val pqRaw   = purchaseRepository.getPurchaseQuantitiesForDateAndProduct(
                            ci.date, product.id)
                        data class Chk(val ob: Int, val pq: Int, val cb: Int, val lbl: String)
                        listOf(
                            Chk(prevRow?.closeQq?:0, pqRaw.qqTotalUnits, ci.qqClosing, "QQ"),
                            Chk(prevRow?.closePp?:0, pqRaw.ppTotalUnits, ci.ppClosing, "PP"),
                            Chk(prevRow?.closeNn?:0, pqRaw.nnTotalUnits, ci.nnClosing, "NN"),
                            Chk(prevRow?.closeDd?:0, pqRaw.ddTotalUnits, ci.ddClosing, "DD")
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
        data class Success(val count: Int, val dates: Int) : ImportStatus()
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
                val sortedRecords = result.closingData.values.sortedBy { it.date }
                for (ci in sortedRecords) {
                    val product = products.find {
                        it.productType == ci.productType && it.brandCode == ci.brandCode
                    } ?: continue
                    val prevRow = repository.getPreviousRow(product.stockCode, ci.date)
                    val pqRaw   = purchaseRepository.getPurchaseQuantitiesForDateAndProduct(
                        ci.date, product.id)
                    val obQq = prevRow?.closeQq ?: 0; val obPp = prevRow?.closePp ?: 0
                    val obNn = prevRow?.closeNn ?: 0; val obDd = prevRow?.closeDd ?: 0
                    val cbQq = ci.qqClosing; val cbPp = ci.ppClosing
                    val cbNn = ci.nnClosing; val cbDd = ci.ddClosing
                    val pqQq = pqRaw.qqTotalUnits; val pqPp = pqRaw.ppTotalUnits
                    val pqNn = pqRaw.nnTotalUnits; val pqDd = pqRaw.ddTotalUnits
                    val sqQq = if (obQq==0&&pqQq==0) 0 else (obQq+pqQq-cbQq).coerceAtLeast(0)
                    val sqPp = if (obPp==0&&pqPp==0) 0 else (obPp+pqPp-cbPp).coerceAtLeast(0)
                    val sqNn = if (obNn==0&&pqNn==0) 0 else (obNn+pqNn-cbNn).coerceAtLeast(0)
                    val sqDd = if (obDd==0&&pqDd==0) 0 else (obDd+pqDd-cbDd).coerceAtLeast(0)
                    val amtQq = sqQq*product.qqSalePrice; val amtPp = sqPp*product.ppSalePrice
                    val amtNn = sqNn*product.nnSalePrice; val amtDd = sqDd*product.ddSalePrice
                    repository.saveAllEntries(listOf(DailyStock(
                        date=ci.date, productCode=product.stockCode,
                        openQq=obQq, openPp=obPp, openNn=obNn, openDd=obDd,
                        closeQq=cbQq, closePp=cbPp, closeNn=cbNn, closeDd=cbDd,
                        saleQq=sqQq, salePp=sqPp, saleNn=sqNn, saleDd=sqDd,
                        priceQq=product.qqSalePrice, pricePp=product.ppSalePrice,
                        priceNn=product.nnSalePrice, priceDd=product.ddSalePrice,
                        amountQq=amtQq, amountPp=amtPp, amountNn=amtNn, amountDd=amtDd,
                        saleAmount=amtQq+amtPp+amtNn+amtDd, isCommitted=true
                    )))
                    updatedRecords++
                }
                val dateCount = result.closingData.values.map{it.date}.toSortedSet().size
                _importStatus.postValue(ImportStatus.Success(updatedRecords, dateCount))
            } catch (e: Exception) {
                _importStatus.postValue(ImportStatus.Error(e.message ?: "Import failed"))
            }
        }
    }

    // ── Opening stock import — OB = CB = imported qty, sale = 0 ─────────────
    //
    // Unlike applyImportedClosingData (which saves openQq=0, closeQq=importedQty
    // causing negative sales on the imported date), this method saves
    // openQq = closeQq = importedQty so the day shows zero sales and correct OB.
    // Used by OpeningStockFragment when importing from Excel.
    //
    // Writes a row for EVERY active product on the baseline date, not just the
    // ones present in the file — a product missing from the file (all-zero rows
    // are dropped by the parser before this even runs, or the product simply
    // wasn't listed) must still get an explicit zero row here. Otherwise its
    // opening balance lookup skips straight past this new baseline to whatever
    // older committed row precedes it — the exact "stale carried-forward
    // quantity" bug this re-baseline feature exists to avoid.

    private fun buildOpeningStockRow(
        date: String, product: Product, ci: DailyStockImportHelper.ClosingImport?
    ): DailyStock {
        val qq = ci?.qqClosing ?: 0; val pp = ci?.ppClosing ?: 0
        val nn = ci?.nnClosing ?: 0; val dd = ci?.ddClosing ?: 0
        return DailyStock(
            date = date, productCode = product.stockCode,
            openQq = qq, openPp = pp, openNn = nn, openDd = dd,
            closeQq = qq, closePp = pp, closeNn = nn, closeDd = dd,
            saleQq = 0, salePp = 0, saleNn = 0, saleDd = 0,
            priceQq = product.qqSalePrice, pricePp = product.ppSalePrice,
            priceNn = product.nnSalePrice, priceDd = product.ddSalePrice,
            amountQq = 0.0, amountPp = 0.0, amountNn = 0.0, amountDd = 0.0,
            saleAmount = 0.0, isCommitted = true, isOpeningStock = true
        )
    }

    fun applyImportedAsOpeningStock(
        result:   DailyStockImportHelper.ImportResult,
        products: List<Product>
    ) {
        if (_importStatus.value is ImportStatus.Running) return
        _importStatus.value = ImportStatus.Running("Saving opening stock…")
        viewModelScope.launch {
            try {
                val byDate = result.closingData.values.groupBy { it.date }
                val activeProducts = products.filter { it.isActive }
                var updatedRecords = 0
                for ((date, records) in byDate) {
                    val importedByCode = records.mapNotNull { ci ->
                        val product = products.find {
                            it.productType == ci.productType && it.brandCode == ci.brandCode
                        } ?: return@mapNotNull null
                        product.stockCode to ci
                    }.toMap()

                    val coveredCodes = activeProducts.map { it.stockCode }.toSet()
                    val rows = activeProducts.map { product ->
                        buildOpeningStockRow(date, product, importedByCode[product.stockCode])
                    }.toMutableList()

                    // Preserve imported values for any inactive product explicitly in the file.
                    importedByCode.forEach { (code, ci) ->
                        if (code !in coveredCodes) {
                            products.find { it.stockCode == code }?.let {
                                rows.add(buildOpeningStockRow(date, it, ci))
                            }
                        }
                    }

                    repository.saveAllEntries(rows)
                    updatedRecords += rows.size
                }
                _importStatus.postValue(ImportStatus.Success(updatedRecords, byDate.size))
            } catch (e: Exception) {
                _importStatus.postValue(ImportStatus.Error(e.message ?: "Import failed"))
            }
        }
    }

    // ── Generic import: purchase/sale/closing data (viewModelScope) ──────────

    fun applyImportedData(
        result:   DailyStockImportHelper.ImportResult,
        products: List<Product>
    ) {
        if (_importStatus.value is ImportStatus.Running) return
        _importStatus.value = ImportStatus.Running("Applying import…")
        viewModelScope.launch {
            try {
                var updatedRecords = 0

                result.purchaseData.forEach { (_, pi) ->
                    val product = products.find { it.stockCode == pi.productCode } ?: return@forEach
                    val prev = repository.getPreviousRow(product.stockCode, pi.date)
                    val obQq = prev?.closeQq?:0; val obPp = prev?.closePp?:0
                    val obNn = prev?.closeNn?:0; val obDd = prev?.closeDd?:0
                    val pqQq=pi.qqPurchase; val pqPp=pi.ppPurchase
                    val pqNn=pi.nnPurchase; val pqDd=pi.ddPurchase
                    if (pqQq+pqPp+pqNn+pqDd > 0) {
                        repository.saveAllEntries(listOf(DailyStock(
                            date=pi.date, productCode=product.stockCode,
                            openQq=obQq, openPp=obPp, openNn=obNn, openDd=obDd,
                            closeQq=obQq+pqQq, closePp=obPp+pqPp,
                            closeNn=obNn+pqNn, closeDd=obDd+pqDd,
                            saleAmount=0.0, isCommitted=true
                        )))
                        updatedRecords++
                    }
                }

                result.saleData.forEach { (_, si) ->
                    val product = products.find { it.stockCode == si.productCode } ?: return@forEach
                    val prev = repository.getPreviousRow(product.stockCode, si.date)
                    val pqRaw = purchaseRepository.getPurchaseQuantitiesForDateAndProduct(si.date, product.id)
                    val obQq=prev?.closeQq?:0; val obPp=prev?.closePp?:0
                    val obNn=prev?.closeNn?:0; val obDd=prev?.closeDd?:0
                    val sqQq=si.qqSale; val sqPp=si.ppSale; val sqNn=si.nnSale; val sqDd=si.ddSale
                    val cbQq=(obQq+pqRaw.qqTotalUnits-sqQq).coerceAtLeast(0)
                    val cbPp=(obPp+pqRaw.ppTotalUnits-sqPp).coerceAtLeast(0)
                    val cbNn=(obNn+pqRaw.nnTotalUnits-sqNn).coerceAtLeast(0)
                    val cbDd=(obDd+pqRaw.ddTotalUnits-sqDd).coerceAtLeast(0)
                    val amtQq=sqQq*product.qqSalePrice; val amtPp=sqPp*product.ppSalePrice
                    val amtNn=sqNn*product.nnSalePrice; val amtDd=sqDd*product.ddSalePrice
                    if (sqQq+sqPp+sqNn+sqDd > 0) {
                        repository.saveAllEntries(listOf(DailyStock(
                            date=si.date, productCode=product.stockCode,
                            openQq=obQq, openPp=obPp, openNn=obNn, openDd=obDd,
                            closeQq=cbQq, closePp=cbPp, closeNn=cbNn, closeDd=cbDd,
                            saleQq=sqQq, salePp=sqPp, saleNn=sqNn, saleDd=sqDd,
                            priceQq=product.qqSalePrice, pricePp=product.ppSalePrice,
                            priceNn=product.nnSalePrice, priceDd=product.ddSalePrice,
                            amountQq=amtQq, amountPp=amtPp, amountNn=amtNn, amountDd=amtDd,
                            saleAmount=amtQq+amtPp+amtNn+amtDd, isCommitted=true
                        )))
                        updatedRecords++
                    }
                }

                result.closingData.forEach { (_, ci) ->
                    val product = products.find { it.stockCode == ci.productCode } ?: return@forEach
                    val prev = repository.getPreviousRow(product.stockCode, ci.date)
                    val pqRaw = purchaseRepository.getPurchaseQuantitiesForDateAndProduct(ci.date, product.id)
                    val obQq=prev?.closeQq?:0; val obPp=prev?.closePp?:0
                    val obNn=prev?.closeNn?:0; val obDd=prev?.closeDd?:0
                    val cbQq=ci.qqClosing; val cbPp=ci.ppClosing
                    val cbNn=ci.nnClosing; val cbDd=ci.ddClosing
                    val sqQq=(obQq+pqRaw.qqTotalUnits-cbQq).coerceAtLeast(0)
                    val sqPp=(obPp+pqRaw.ppTotalUnits-cbPp).coerceAtLeast(0)
                    val sqNn=(obNn+pqRaw.nnTotalUnits-cbNn).coerceAtLeast(0)
                    val sqDd=(obDd+pqRaw.ddTotalUnits-cbDd).coerceAtLeast(0)
                    val amtQq=sqQq*product.qqSalePrice; val amtPp=sqPp*product.ppSalePrice
                    val amtNn=sqNn*product.nnSalePrice; val amtDd=sqDd*product.ddSalePrice
                    repository.saveAllEntries(listOf(DailyStock(
                        date=ci.date, productCode=product.stockCode,
                        openQq=obQq, openPp=obPp, openNn=obNn, openDd=obDd,
                        closeQq=cbQq, closePp=cbPp, closeNn=cbNn, closeDd=cbDd,
                        saleQq=sqQq, salePp=sqPp, saleNn=sqNn, saleDd=sqDd,
                        priceQq=product.qqSalePrice, pricePp=product.ppSalePrice,
                        priceNn=product.nnSalePrice, priceDd=product.ddSalePrice,
                        amountQq=amtQq, amountPp=amtPp, amountNn=amtNn, amountDd=amtDd,
                        saleAmount=amtQq+amtPp+amtNn+amtDd, isCommitted=true
                    )))
                    updatedRecords++
                }

                val dateCount = (
                    result.purchaseData.values.map{it.date} +
                    result.saleData.values.map{it.date} +
                    result.closingData.values.map{it.date}
                ).toSortedSet().size
                _importStatus.postValue(ImportStatus.Success(updatedRecords, dateCount))
            } catch (e: Exception) {
                _importStatus.postValue(ImportStatus.Error(e.message ?: "Import failed"))
            }
        }
    }

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

    /** Dates that are opening stock entries (all rows have zero opening balances). */
    suspend fun getOpeningStockDateCounts() =
        repository.getOpeningStockDateCounts()

    fun clearDateData(date: String) {
        viewModelScope.launch { repository.clearDateData(date) }
    }

    /** Suspend version — caller awaits DB completion before reloading UI. */
    suspend fun clearDateDataAwait(date: String) = repository.clearDateData(date)

    /** Clear the entire daily_stock table. */
    fun clearAllData() {
        viewModelScope.launch { repository.clearAllData() }
    }

    /** Suspend version — caller awaits DB completion before reloading UI. */
    suspend fun clearAllDataAwait() = repository.clearAllData()

    suspend fun clearProductDataAwait(date: String, productCode: String) =
        repository.deleteDailyStock(date, productCode)

    suspend fun clearProductDataWithCascadeAwait(
        date:        String,
        productCode: String,
        products:    List<com.simhadri.winentry.data.entity.Product>
    ) = repository.clearEntryWithCascade(date, productCode, products)

    /** Deletes the active opening stock date and cascade-corrects the next committed day. */
    suspend fun clearOpeningStockWithCascadeAwait(
        date:     String,
        products: List<Product>
    ) = repository.clearOpeningStockWithCascade(date, products)

    /** Force all committed rows to re-sync to Google Sheets. */
    fun forceFullResync() {
        viewModelScope.launch { repository.forceFullResync() }
    }
}
