package com.simhadri.winentry.ui.dailystock

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.viewModelScope
import com.simhadri.winentry.data.AppDatabase
import com.simhadri.winentry.data.entity.DailyEntry
import com.simhadri.winentry.data.entity.DailyStock
import com.simhadri.winentry.data.entity.Product
import com.simhadri.winentry.data.entity.ProductSizeAmounts
import com.simhadri.winentry.data.entity.ProductSizeQty
import com.simhadri.winentry.data.entity.stockCode
import com.simhadri.winentry.data.repository.CascadePreview
import com.simhadri.winentry.data.repository.CascadeResult
import com.simhadri.winentry.data.dao.PurchaseDao
import com.simhadri.winentry.data.entity.Purchase
import com.simhadri.winentry.data.repository.DailyStockRepository
import com.simhadri.winentry.data.repository.PurchaseRepository
import kotlinx.coroutines.launch
import com.simhadri.winentry.ui.auth.ErrorLogger
import java.text.SimpleDateFormat
import java.util.*

/**
 * ViewModel for DailyStockFragment.
 *
 * Responsibilities:
 *   - Date navigation
 *   - Bulk entry loading (3 DB queries regardless of product count)
 *   - In-memory cache of DailyEntry objects
 *   - Dirty tracking per product
 *   - Search / filter
 *   - Save (single product or all dirty products)
 *   - Cascade recalculation after CB edit
 *   - Date status indicator
 *
 * Import, export, and data-management operations live in
 * DailyStockDataViewModel so this class stays focused on the daily workflow.
 */
class DailyStockViewModel(application: Application) : AndroidViewModel(application) {

    val repository: DailyStockRepository
    val purchaseRepository: PurchaseRepository

    val allProducts: LiveData<List<Product>>

    private val _dailyEntries = MutableLiveData<List<DailyEntry>>()
    val dailyEntries: LiveData<List<DailyEntry>> = _dailyEntries

    private val _selectedDate = MutableLiveData<String>()
    val selectedDate: LiveData<String> = _selectedDate

    private val _entryMode = MutableLiveData<EntryMode>()
    val entryMode: LiveData<EntryMode> = _entryMode

    private val _saveStatus = MutableLiveData<SaveStatus?>()
    val saveStatus: LiveData<SaveStatus?> = _saveStatus

    private val _searchQuery = MutableLiveData<String>()

    var entriesCache = mutableMapOf<Long, DailyEntry>()
        private set

    /** Tracks the active load coroutine — cancelled on rapid date navigation. */
    private var loadJob: kotlinx.coroutines.Job? = null

    // ── Dirty tracking ────────────────────────────────────────────────────────

    private val _hasUnsavedChanges = MutableLiveData(false)
    val hasUnsavedChanges: LiveData<Boolean> = _hasUnsavedChanges

    private val dirtyProducts = mutableSetOf<Long>()

    private val _dirtyProductIds = MutableLiveData<Set<Long>>(emptySet())
    val dirtyProductIds: LiveData<Set<Long>> = _dirtyProductIds

    private val _discardedProductId = MutableLiveData<Long?>()
    val discardedProductId: LiveData<Long?> = _discardedProductId

    fun isProductDirty(productId: Long): Boolean = dirtyProducts.contains(productId)

    fun markProductDirty(productId: Long) {
        dirtyProducts.add(productId)
        _hasUnsavedChanges.value = true
        _dirtyProductIds.value = dirtyProducts.toSet()
    }

    fun clearDirty() {
        _hasUnsavedChanges.value = false
        dirtyProducts.clear()
        _dirtyProductIds.value = emptySet()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    private val purchaseChangeObserver =
        Observer<List<Purchase>> {
            // Don't reload if user has unsaved edits — would overwrite in-progress changes
            if (entriesCache.isNotEmpty() && dirtyProducts.isEmpty()) loadEntriesForDate()
        }

    init {
        val db = AppDatabase.getInstance(application)
        repository         = DailyStockRepository(db.productDao(), db.dailyStockDao())
        purchaseRepository = PurchaseRepository(db.purchaseDao())
        allProducts         = repository.getActiveProducts()
        _selectedDate.value = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        _entryMode.value    = EntryMode.VIEW
        _searchQuery.value  = ""
        purchaseRepository.allPurchases.observeForever(purchaseChangeObserver)
    }

    override fun onCleared() {
        super.onCleared()
        purchaseRepository.allPurchases.removeObserver(purchaseChangeObserver)
    }

    // ── Date navigation ───────────────────────────────────────────────────────

    fun goToPreviousDate() {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val cal = Calendar.getInstance().apply {
            time = sdf.parse(_selectedDate.value ?: return) ?: return
            add(Calendar.DAY_OF_MONTH, -1)
        }
        setDate(sdf.format(cal.time))
    }

    fun goToNextDate() {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val cal = Calendar.getInstance().apply {
            time = sdf.parse(_selectedDate.value ?: return) ?: return
            add(Calendar.DAY_OF_MONTH, 1)
        }
        val maxCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, MAX_FUTURE_DAYS) }
        if (cal.after(maxCal)) return
        setDate(sdf.format(cal.time))
    }

    fun setDate(date: String) {
        if (_selectedDate.value != date) {
            _selectedDate.value = date
            clearDirty()
            loadEntriesForDate()
        }
    }

    fun setEntryMode(mode: EntryMode) { _entryMode.value = mode }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        filterAndUpdateEntries()
    }

    // ── Entry loading ─────────────────────────────────────────────────────────

    fun isCacheEmpty(): Boolean = entriesCache.isEmpty()

    /** Called when product list changes — re-filter without hitting the DB. */
    fun refreshActiveProducts() = filterAndUpdateEntries()

    fun loadEntriesForDate() {
        // Never reload if user has unsaved edits — would overwrite in-progress changes
        if (dirtyProducts.isNotEmpty()) return
        // Cancel any in-flight load — prevents race when user navigates dates quickly
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val products = allProducts.value ?: return@launch
            if (products.isEmpty()) return@launch
            initializeEntries(products)
            loadDateStatus()
        }
    }

    fun initializeEntries(products: List<Product>) {
        viewModelScope.launch {
            val date = _selectedDate.value ?: return@launch
            if (products.isEmpty()) return@launch

            // Product-level codes (brand-level, e.g. "W1249")
            val productCodes = products.map { it.stockCode }

            // One query returns all previous rows (all 4 sizes each)
            val prevRowMap  = runCatching { repository.getBulkPreviousRows(productCodes, date) }
                .getOrDefault(emptyMap())
            // Purchase quantities keyed by primary size code
            val purchaseMap = runCatching {
                purchaseRepository.getAllPurchaseQuantitiesByCodeForDate(date, products)
            }.getOrDefault(emptyMap())
            // Current stock rows for the selected date (product-level)
            val stockMap    = runCatching { repository.getAllDailyStockForDate(date) }
                .getOrDefault(emptyList()).associateBy { it.productCode }

            val dao = AppDatabase.getInstance(getApplication()).purchaseDao()
            val entries = products.map { buildEntry(it, date, prevRowMap, purchaseMap, stockMap, dao) }

            // Update cache in-place — only replace entries whose values changed.
            // This lets DiffUtil do minimal rebinding: card backgrounds and row
            // structure stay intact; only changed number cells are redrawn.
            entries.forEach { entriesCache[it.product.id] = it }
            // Remove any products no longer in the active list
            val activeIds = entries.map { it.product.id }.toSet()
            entriesCache.keys.retainAll(activeIds)

            // Auto-activate Save button for products that have purchase qty but no
            // committed stock row yet (e.g. 31 Jan opening stock scenario).
            entries.forEach { entry ->
                val hasPurchase  = entry.purchase.qq + entry.purchase.pp +
                                   entry.purchase.nn + entry.purchase.dd > 0
                val notCommitted = stockMap[entry.product.stockCode]
                                       ?.isCommitted != true
                if (hasPurchase && notCommitted) markProductDirty(entry.product.id)
            }

            filterAndUpdateEntries()
        }
    }

    /**
     * Build a DailyEntry from pre-loaded maps — no extra DB calls per product.
     *
     * prevRowMap: productCode → previous committed DailyStock row (all 4 sizes).
     * purchaseMap: size-code → total purchased units (from Purchases table).
     * stockMap: productCode → today's DailyStock row (may be null for new days).
     *
     * Opening balance priority:
     *   1. Stored openQq/Pp/Nn/Dd snapshot in today's committed row
     *   2. Previous day's closeQq/Pp/Nn/Dd (for drafts / new days)
     *   3. Zero (no history)
     */
    private suspend fun buildEntry(
        product:     Product,
        date:        String,
        prevRowMap:  Map<String, DailyStock>,   // productCode → previous row
        purchaseMap: Map<String, Int>,           // size-code → purch units
        stockMap:    Map<String, DailyStock>,    // productCode → today's row
        purchaseDao: PurchaseDao
    ): DailyEntry {
        var stock = stockMap[product.stockCode]

        // Opening balance priority:
        //   1. Stored snapshot in today's committed row (openQq/Pp/Nn/Dd)
        //      — always correct, locked at import/save time
        //   2. Previous day's closing (prevRowMap) — for uncommitted/draft rows
        //   3. Zero — no history at all
        val prev = prevRowMap[product.stockCode]
        val qqOb: Int
        val ppOb: Int
        val nnOb: Int
        val ddOb: Int
        if (stock?.isCommitted == true) {
            // Use the snapshot stored at commit time — most reliable
            qqOb = stock.openQq; ppOb = stock.openPp
            nnOb = stock.openNn; ddOb = stock.openDd
        } else {
            // Derive from previous day's closing (draft or no row yet)
            qqOb = prev?.closeQq ?: 0; ppOb = prev?.closePp ?: 0
            nnOb = prev?.closeNn ?: 0; ddOb = prev?.closeDd ?: 0
        }

        // Purchase quantities live from Purchases table (keyed by size code)
        val qqPq = purchaseMap[product.qqCode] ?: 0
        val ppPq = purchaseMap[product.ppCode] ?: 0
        val nnPq = purchaseMap[product.nnCode] ?: 0
        val ddPq = purchaseMap[product.ddCode] ?: 0

        // A draft whose purchases moved to another date (e.g. Date Received changed) has no reason to exist
        if (stock != null && !stock.isCommitted && (qqPq + ppPq + nnPq + ddPq) == 0) {
            runCatching { repository.deleteDraft(date, product.stockCode) }
            stock = null
        }

        // Auto-save draft if any purchase exists but no today row yet
        if (stock == null && (qqPq + ppPq + nnPq + ddPq) > 0) {
            runCatching {
                repository.saveDraftIfAbsent(
                    date        = date,
                    productCode = product.stockCode,
                    openQq = qqOb, openPp = ppOb, openNn = nnOb, openDd = ddOb,
                    purchQq = qqPq, purchPp = ppPq, purchNn = nnPq, purchDd = ddPq
                )
                purchaseDao.markPurchasesAsProcessedByEffectiveDate(date, product.id)
            }
            stock = repository.getDailyStockRaw(date, product.stockCode)
        }

        // Committed rows keep the user's CB. Drafts only ever stored OB + PQ as of
        // creation, so their CB is re-derived from the current OB and purchases —
        // otherwise a later-received or moved purchase shows a phantom or negative sale.
        fun resolveCb(stored: Int?, committed: Boolean, ob: Int, pq: Int): Int =
            if (stored != null && committed) stored else ob + pq

        val qqCb = resolveCb(stock?.closeQq, stock?.isCommitted == true, qqOb, qqPq)
        val ppCb = resolveCb(stock?.closePp, stock?.isCommitted == true, ppOb, ppPq)
        val nnCb = resolveCb(stock?.closeNn, stock?.isCommitted == true, nnOb, nnPq)
        val ddCb = resolveCb(stock?.closeDd, stock?.isCommitted == true, ddOb, ddPq)

        return DailyEntry(
            product  = product,
            date     = date,
            opening  = ProductSizeQty(qqOb, ppOb, nnOb, ddOb),
            purchase = ProductSizeQty(qqPq, ppPq, nnPq, ddPq),
            sale     = ProductSizeQty(
                qqOb + qqPq - qqCb,   // negative allowed — adapter shows in red
                ppOb + ppPq - ppCb,   // user must fix CB when sale < 0
                nnOb + nnPq - nnCb,
                ddOb + ddPq - ddCb
            ),
            closing  = ProductSizeQty(qqCb, ppCb, nnCb, ddCb),
            committedAmounts = if (stock?.isCommitted == true)
                ProductSizeAmounts(stock.amountQq, stock.amountPp, stock.amountNn, stock.amountDd)
            else null,
            isBaseline = stock?.isCommitted == true && stock.isOpeningStock
        )
    }

    private fun filterAndUpdateEntries() {
        val query    = _searchQuery.value ?: ""
        val filtered = entriesCache.values.toList().let { all ->
            if (query.isEmpty()) all
            else all.filter {
                it.product.displayName.contains(query, ignoreCase = true) ||
                it.product.brandCode.contains(query, ignoreCase = true)
            }
        }
        _dailyEntries.value = filtered.sortedWith(
            compareBy({ it.product.dailySortKey }, { it.product.displayName })
        )
    }

    // ── In-memory edits ───────────────────────────────────────────────────────

    fun updatePurchase(productId: Long, size: String, value: Int) {
        val entry = entriesCache[productId] ?: return
        markProductDirty(productId)
        val newPq = when (size) {
            "QQ" -> entry.purchase.copy(qq = value)
            "PP" -> entry.purchase.copy(pp = value)
            "NN" -> entry.purchase.copy(nn = value)
            else -> entry.purchase.copy(dd = value)
        }
        val newCb = when (size) {
            "QQ" -> entry.closing.copy(qq = entry.opening.qq + value)
            "PP" -> entry.closing.copy(pp = entry.opening.pp + value)
            "NN" -> entry.closing.copy(nn = entry.opening.nn + value)
            else -> entry.closing.copy(dd = entry.opening.dd + value)
        }
        val newSale = when (size) {
            "QQ" -> entry.sale.copy(qq = entry.opening.qq + value - newCb.qq)
            "PP" -> entry.sale.copy(pp = entry.opening.pp + value - newCb.pp)
            "NN" -> entry.sale.copy(nn = entry.opening.nn + value - newCb.nn)
            else -> entry.sale.copy(dd = entry.opening.dd + value - newCb.dd)
        }
        entriesCache[productId] = entry.copy(purchase = newPq, closing = newCb, sale = newSale)
    }

    fun updateClosing(productId: Long, size: String, value: Int) {
        val entry = entriesCache[productId] ?: return
        markProductDirty(productId)
        val newCb = when (size) {
            "QQ" -> entry.closing.copy(qq = value)
            "PP" -> entry.closing.copy(pp = value)
            "NN" -> entry.closing.copy(nn = value)
            else -> entry.closing.copy(dd = value)
        }
        val newSale = when (size) {
            "QQ" -> entry.sale.copy(qq = entry.opening.qq + entry.purchase.qq - value)
            "PP" -> entry.sale.copy(pp = entry.opening.pp + entry.purchase.pp - value)
            "NN" -> entry.sale.copy(nn = entry.opening.nn + entry.purchase.nn - value)
            else -> entry.sale.copy(dd = entry.opening.dd + entry.purchase.dd - value)
        }
        // Edited values are priced live until saved; stale locked amounts would misreport the sale
        entriesCache[productId] = entry.copy(closing = newCb, sale = newSale, committedAmounts = null)
    }

    private fun lockSavedAmounts(rows: List<DailyStock>) {
        val byCode = rows.associateBy { it.productCode }
        entriesCache.entries.forEach { (id, e) ->
            val r = byCode[e.product.stockCode] ?: return@forEach
            entriesCache[id] = e.copy(committedAmounts = ProductSizeAmounts(r.amountQq, r.amountPp, r.amountNn, r.amountDd))
        }
    }

    // ── Save ──────────────────────────────────────────────────────────────────

    fun saveProductEntry(productId: Long) {
        viewModelScope.launch {
            try {
                val date  = _selectedDate.value ?: return@launch
                val entry = entriesCache[productId] ?: return@launch
                val rows  = buildRows(date, entry)
                // Save unconditionally — downstream negative sales are handled by
                // the cascade system (CascadeWithWarnings) so the user can correct
                // dates one at a time without being blocked by downstream issues.
                repository.saveAllEntries(rows)
                lockSavedAmounts(rows)
                dirtyProducts.remove(productId)
                if (dirtyProducts.isEmpty()) _hasUnsavedChanges.value = false
                loadDateStatus()
                filterAndUpdateEntries()
                checkAndRequestCascade(rows)
            } catch (e: Exception) {
                ErrorLogger.log(getApplication(), "DailyStock", "saveProductEntry failed date=${_selectedDate.value}", e)
            }
        }
    }

    fun saveAllEntries() {
        viewModelScope.launch {
            try {
                _saveStatus.value = SaveStatus.Saving
                val date       = _selectedDate.value ?: return@launch
                val rowsToSave = mutableListOf<DailyStock>()

                entriesCache.values
                    .distinctBy { it.product.id }
                    .filter { dirtyProducts.contains(it.product.id) }
                    .forEach { entry -> rowsToSave.addAll(buildRows(date, entry)) }

                if (rowsToSave.isNotEmpty()) {
                    // Save unconditionally — downstream issues handled by cascade warnings.
                    repository.saveAllEntries(rowsToSave)
                    lockSavedAmounts(rowsToSave)
                }
                clearDirty()
                loadDateStatus()
                filterAndUpdateEntries()   // re-emit updated cache so strip total recalculates
                _saveStatus.value = SaveStatus.Success(rowsToSave.size)
                if (rowsToSave.isNotEmpty()) checkAndRequestCascade(rowsToSave)
            } catch (e: Exception) {
                ErrorLogger.log(getApplication(), "DailyStock", "saveAllEntries failed date=${_selectedDate.value}", e)
                _saveStatus.value = SaveStatus.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun clearSaveStatus() { _saveStatus.value = null }

    /** Discard all pending edits on the current date — used when blocking save. */
    fun discardAllEdits() {
        viewModelScope.launch {
            clearDirty()                              // removes save/cancel icons immediately
            val products = allProducts.value ?: return@launch
            if (products.isNotEmpty()) initializeEntries(products)  // restores DB values
        }
    }



    fun discardProductEdits(productId: Long) {
        viewModelScope.launch {
            val date    = _selectedDate.value ?: return@launch
            val product = allProducts.value?.find { it.id == productId } ?: return@launch
            val allProds   = allProducts.value ?: emptyList()
            val prevRowMap = buildMap<String, DailyStock> {
                val prev = repository.getPreviousRow(product.stockCode, date)
                if (prev != null) put(product.stockCode, prev)
            }
            val pqMap  = purchaseRepository.getAllPurchaseQuantitiesByCodeForDate(date, allProds)
            val stMap  = repository.getAllDailyStockForDate(date)
                .filter { it.productCode == product.stockCode }
                .associateBy { it.productCode }
            val dao    = AppDatabase.getInstance(getApplication()).purchaseDao()
            entriesCache[productId] = buildEntry(product, date, prevRowMap, pqMap, stMap, dao)
            dirtyProducts.remove(productId)
            _dirtyProductIds.value = dirtyProducts.toSet()
            if (dirtyProducts.isEmpty()) _hasUnsavedChanges.value = false
            filterAndUpdateEntries()
            _discardedProductId.value = productId
            _discardedProductId.value = null
        }
    }

    // ── Build helpers ─────────────────────────────────────────────────────────

    /**
     * Build ONE DailyStock row per product — all 4 sizes, all snapshots.
     * Called at commit time; values are locked permanently.
     */
    private fun buildRows(date: String, entry: DailyEntry): List<DailyStock> {
        val p  = entry.product
        val sq = entry.sale
        val ob = entry.opening
        val cb = entry.closing
        val amtQq = sq.qq * p.qqSalePrice
        val amtPp = sq.pp * p.ppSalePrice
        val amtNn = sq.nn * p.nnSalePrice
        val amtDd = sq.dd * p.ddSalePrice
        return listOf(
            DailyStock(
                date        = date,
                productCode = p.stockCode,        // brand-level, not size-level
                openQq  = ob.qq, openPp  = ob.pp, openNn  = ob.nn, openDd  = ob.dd,
                closeQq = cb.qq, closePp = cb.pp, closeNn = cb.nn, closeDd = cb.dd,
                saleQq  = sq.qq, salePp  = sq.pp, saleNn  = sq.nn, saleDd  = sq.dd,
                priceQq = p.qqSalePrice, pricePp = p.ppSalePrice,
                priceNn = p.nnSalePrice, priceDd = p.ddSalePrice,
                amountQq = amtQq, amountPp = amtPp,
                amountNn = amtNn, amountDd = amtDd,
                saleAmount  = amtQq + amtPp + amtNn + amtDd,
                isCommitted = true
            )
        )
    }


    // ── Cascade ───────────────────────────────────────────────────────────────

    private val _cascadeRequest = MutableLiveData<CascadeRequest?>()
    val cascadeRequest: LiveData<CascadeRequest?> = _cascadeRequest

    /** Dates with negative sale after cascade — fragment navigates user there. */
    private val _negativeSaleDates = MutableLiveData<List<String>>(emptyList())
    val negativeSaleDates: LiveData<List<String>> = _negativeSaleDates

    private var pendingCascadeRows: List<DailyStock>? = null

    private suspend fun checkAndRequestCascade(rows: List<DailyStock>) {
        try {
            val preview = repository.previewCascade(rows)
            if (!preview.isNeeded) return
            pendingCascadeRows   = rows
            _cascadeRequest.postValue(CascadeRequest(preview))
        } catch (e: Exception) {
            android.util.Log.e("DailyStock", "Cascade preview: ${e.message}", e)
        }
    }

    enum class CascadeChoice { UPDATE, SKIP, CLEAR_NEXT }

    fun confirmCascade(choice: CascadeChoice) {
        _cascadeRequest.value = null
        if (choice == CascadeChoice.SKIP) { pendingCascadeRows = null; return }

        val rows = pendingCascadeRows ?: return
        pendingCascadeRows = null

        if (choice == CascadeChoice.CLEAR_NEXT) {
            viewModelScope.launch {
                try {
                    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                    val cal = Calendar.getInstance()
                    for (row in rows) {
                        cal.time = sdf.parse(row.date) ?: continue
                        cal.add(Calendar.DAY_OF_MONTH, 1)
                        val nextDate = sdf.format(cal.time)
                        if (repository.getDailyStockRaw(nextDate, row.productCode)?.isOpeningStock == true) continue
                        repository.deleteDailyStock(nextDate, row.productCode)
                    }
                    loadEntriesForDate()
                    loadDateStatus()
                } catch (e: Exception) {
                    ErrorLogger.log(getApplication(), "DailyStock", "Clear next day failed", e)
                }
            }
            return
        }

        // CascadeChoice.UPDATE
        viewModelScope.launch {
            try {
                val products = allProducts.value ?: emptyList()
                val result   = repository.cascadeRecalculate(rows, products)
                loadEntriesForDate()
                loadDateStatus()
                if (result.hasNegatives) {
                    _negativeSaleDates.value = result.negativeSaleDates.sorted()
                    _saveStatus.value = SaveStatus.CascadeWithWarnings(
                        updatedCount   = result.updatedCount,
                        problemDates   = result.negativeSaleDates.sorted()
                    )
                } else {
                    _negativeSaleDates.value = emptyList()
                    _saveStatus.value = SaveStatus.CascadeComplete(result.updatedCount)
                }
            } catch (e: Exception) {
                ErrorLogger.log(getApplication(), "DailyStock", "Cascade failed", e)
            }
        }
    }

    /**
     * Manually re-runs the cascade check for the current date's already-committed
     * entries, without requiring any edit. Covers the case where the user picked
     * "Skip" on the cascade dialog (or the next day's data changed since) and later
     * wants the next day's opening balance corrected — previously the only way to
     * reopen that dialog was to re-edit and re-save every product on the date, even
     * though nothing about today's own values needed to change.
     */
    fun recalculateNextDayOpeningBalance() {
        viewModelScope.launch {
            try {
                val date = _selectedDate.value ?: return@launch
                val rows = repository.getAllDailyStockForDate(date).filter { it.isCommitted }
                if (rows.isEmpty()) {
                    _saveStatus.value = SaveStatus.Info("No committed entries on $date.")
                    return@launch
                }
                val preview = repository.previewCascade(rows)
                if (!preview.isNeeded) {
                    _saveStatus.value = SaveStatus.Info("No committed next-day data found after $date.")
                    return@launch
                }
                pendingCascadeRows = rows
                _cascadeRequest.postValue(CascadeRequest(preview))
            } catch (e: Exception) {
                ErrorLogger.log(getApplication(), "DailyStock", "recalculateNextDayOpeningBalance failed", e)
            }
        }
    }

    // ── Date status ───────────────────────────────────────────────────────────

    private val _dateStatus = MutableLiveData<DateStatus>()
    val dateStatus: LiveData<DateStatus> = _dateStatus

    fun loadDateStatus() {
        viewModelScope.launch {
            val date = _selectedDate.value ?: return@launch
            _dateStatus.value = getDateStatus(date)
        }
    }

    suspend fun getDateStatus(date: String): DateStatus {
        return try {
            val rows        = repository.getAllDailyStockForDate(date)
            val commitCount = rows.count { it.isCommitted }
            val draftCount  = rows.count { !it.isCommitted }
            DateStatus(
                hasData          = rows.isNotEmpty(),
                isFullyCommitted = rows.isNotEmpty() && draftCount == 0,
                commitCount      = commitCount,
                draftCount       = draftCount,
                totalEntries     = rows.size,
                isOpeningStockBaseline = rows.any { it.isOpeningStock }
            )
        } catch (e: Exception) { DateStatus(false, false, 0, 0, 0) }
    }

    // ── Inner types ───────────────────────────────────────────────────────────

    companion object { const val MAX_FUTURE_DAYS = 1 }

    enum class EntryMode { VIEW, BALANCE }

    sealed class SaveStatus {
        object Saving : SaveStatus()
        data class Success(val count: Int) : SaveStatus()
        data class Error(val message: String) : SaveStatus()
        /** Non-error informational message (e.g. a manual action found nothing to do). */
        data class Info(val message: String) : SaveStatus()
        data class CascadeComplete(val updatedCount: Int) : SaveStatus()
        /** Cascade completed but some subsequent days have CB > new OB (negative sale). */
        data class CascadeWithWarnings(
            val updatedCount: Int,
            val problemDates: List<String>
        ) : SaveStatus()
        /**
         * Save blocked: the new CB would make the next committed day's sale negative.
         * User must fix the next day's CB first before this day can be saved.
         * [violations] format: "yyyy-MM-dd|dd/MM/yy|QQ(-5), PP(-2)"
         */
        data class BlockedByIntegrity(val violations: List<String>) : SaveStatus()
    }

    data class CascadeRequest(val preview: CascadePreview)

    data class DateStatus(
        val hasData:          Boolean,
        val isFullyCommitted: Boolean,
        val commitCount:      Int,
        val draftCount:       Int,
        val totalEntries:     Int,
        /** True when this date's rows carry the opening-stock baseline marker — either
         *  the original setup or a "Start New Opening Balance" re-baseline point. */
        val isOpeningStockBaseline: Boolean = false
    ) {
        val statusColor: Int get() = when {
            !hasData          -> 0xFF9E9E9E.toInt()
            !isFullyCommitted -> 0xFFFFC107.toInt()
            else              -> 0xFF4CAF50.toInt()
        }
        val statusText: String get() = when {
            !hasData       -> "No Data"
            draftCount > 0 -> "Not Saved ($commitCount/$totalEntries)"
            else           -> "Committed ✓"
        }
    }
}
