package com.simhadri.winentry.ui.quicksale

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.simhadri.winentry.data.AppDatabase
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-memory-only ViewModel for the Quick Sale Check scratchpad screen.
 *
 * Reads the active product list (read-only, same sort order as Daily Stock) once via
 * ProductDao and seeds one zeroed row per product. Every edit stays inside [rowsById] —
 * nothing here ever reaches Room.
 *
 * Scoped to the Activity (see QuickSaleCheckFragment's `by activityViewModels()`) so the
 * scratchpad survives navigating away and back within the same app session — only app
 * process death / force-close clears it, matching "stays temporarily while using the app."
 */
class QuickSaleViewModel(application: Application) : AndroidViewModel(application) {

    private val productDao = AppDatabase.getInstance(application).productDao()

    private val rowsById = LinkedHashMap<Long, QuickSaleRow>()
    // Rows for products that dropped out of the active set mid-session while carrying
    // typed data — held here (out of rowsById/_rows) so the visible list/count always
    // matches Product Display Order's active list, but a same-session reactivation
    // still gets its data back instead of losing it.
    private val orphanedRows = LinkedHashMap<Long, QuickSaleRow>()
    private var loaded = false

    private val _rows = MutableLiveData<List<QuickSaleRow>>(emptyList())

    private val _mode = MutableLiveData(QuickSaleMode.OB_CB)
    val mode: LiveData<QuickSaleMode> = _mode

    private val _showPurchase = MutableLiveData(true)
    val showPurchase: LiveData<Boolean> = _showPurchase

    private val _workingDate = MutableLiveData(
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    )
    val workingDate: LiveData<String> = _workingDate

    private val _searchQuery = MutableLiveData("")

    private val _isLoading = MutableLiveData(true)
    val isLoading: LiveData<Boolean> = _isLoading

    val filteredRows: MediatorLiveData<List<QuickSaleRow>> = MediatorLiveData()
    val totalSaleAmount: MediatorLiveData<Double> = MediatorLiveData()

    // ── Reconciliation (in-memory only, mirrors DayReconciliation's fields/formula) ──
    private val _upiReceipts = MutableLiveData(0.0)
    val upiReceipts: LiveData<Double> = _upiReceipts
    private val _dayExpenses = MutableLiveData(0.0)
    val dayExpenses: LiveData<Double> = _dayExpenses
    private val _deposits = MutableLiveData(0.0)
    val deposits: LiveData<Double> = _deposits
    private val _notes = MutableLiveData("")
    val notes: LiveData<String> = _notes
    val cashForDeposit: MediatorLiveData<Double> = MediatorLiveData()

    init {
        filteredRows.addSource(_rows) { recomputeFiltered() }
        filteredRows.addSource(_searchQuery) { recomputeFiltered() }

        totalSaleAmount.addSource(_rows) { recomputeTotal() }
        totalSaleAmount.addSource(_mode) { recomputeTotal() }

        cashForDeposit.addSource(totalSaleAmount) { recomputeCashForDeposit() }
        cashForDeposit.addSource(_upiReceipts) { recomputeCashForDeposit() }
        cashForDeposit.addSource(_dayExpenses) { recomputeCashForDeposit() }

        if (!loaded) {
            loaded = true
            viewModelScope.launch {
                val products = productDao.getActiveProductsByDailySortKeySync()
                products.forEach { p -> rowsById[p.id] = QuickSaleRow(product = p) }
                _rows.value = rowsById.values.toList()
                _isLoading.value = false
            }
        }
    }

    private fun recomputeFiltered() {
        val query = _searchQuery.value.orEmpty().trim()
        val all = _rows.value.orEmpty()
        filteredRows.value = if (query.isEmpty()) all else all.filter { row ->
            row.product.displayName.contains(query, ignoreCase = true) ||
            row.product.brandCode.contains(query, ignoreCase = true)
        }
    }

    private fun recomputeTotal() {
        val m = _mode.value ?: QuickSaleMode.OB_CB
        totalSaleAmount.value = _rows.value.orEmpty().sumOf { it.saleAmount(m) }
    }

    private fun recomputeCashForDeposit() {
        val total = totalSaleAmount.value ?: 0.0
        val upi = _upiReceipts.value ?: 0.0
        val expenses = _dayExpenses.value ?: 0.0
        cashForDeposit.value = total - upi - expenses
    }

    fun setMode(newMode: QuickSaleMode) {
        if (_mode.value == newMode) return
        _mode.value = newMode
    }

    fun setShowPurchase(show: Boolean) {
        _showPurchase.value = show
    }

    fun setWorkingDate(date: String) {
        _workingDate.value = date
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun updateUpi(value: Double) { _upiReceipts.value = value }
    fun updateExpenses(value: Double) { _dayExpenses.value = value }
    fun updateDeposits(value: Double) { _deposits.value = value }
    fun updateNotes(value: String) { _notes.value = value }

    fun updateOpening(productId: Long, size: String, value: Int) =
        updateRow(productId) { it.copy(opening = it.opening.with(size, value)) }

    fun updatePurchase(productId: Long, size: String, value: Int) =
        updateRow(productId) { it.copy(purchase = it.purchase.with(size, value)) }

    fun updateClosing(productId: Long, size: String, value: Int) =
        updateRow(productId) { it.copy(closing = it.closing.with(size, value)) }

    fun updateDirectSale(productId: Long, size: String, value: Int) =
        updateRow(productId) { it.copy(directSale = it.directSale.with(size, value)) }

    /**
     * Wholesale row replace — used by Excel import once a product code is resolved.
     * [directSale] is nullable and left untouched (`null`) by the two Daily-Stock-derived
     * import formats, which have no direct-sale concept; only this screen's own export
     * format supplies a real value here, restoring Direct Qty mode data on re-import.
     */
    fun applyImportedRow(productId: Long, opening: SizeQty, purchase: SizeQty, closing: SizeQty, directSale: SizeQty? = null) {
        updateRow(productId) {
            it.copy(
                opening = opening, purchase = purchase, closing = closing,
                directSale = directSale ?: it.directSale
            )
        }
    }

    fun clearAll() {
        rowsById.keys.toList().forEach { id ->
            rowsById[id] = rowsById.getValue(id).copy(
                opening = SizeQty(), purchase = SizeQty(), closing = SizeQty(), directSale = SizeQty()
            )
        }
        orphanedRows.clear()
        _rows.value = rowsById.values.toList()
        _upiReceipts.value = 0.0
        _dayExpenses.value = 0.0
        _deposits.value = 0.0
        _notes.value = ""
    }

    /** Snapshot of the full (unfiltered) row list — used by export/print. */
    fun currentRows(): List<QuickSaleRow> = _rows.value.orEmpty()

    /**
     * Re-reads the active product list and swaps in the latest [Product] object
     * (prices, name, dailySortKey, etc.) for every existing row, keeping all
     * typed opening/purchase/closing/direct-sale values untouched. Newly-activated
     * products are appended as zeroed rows. Called on every screen resume so
     * edits made in the Products module (e.g. a sale price change or a display
     * order change) show up here without needing an app restart; also exposed
     * as a manual "Refresh Prices" menu action.
     *
     * Rebuilds [rowsById] from scratch in the freshly-queried order rather than
     * updating values in place — a LinkedHashMap keeps values in *insertion*
     * order, so an in-place update would silently keep showing the original
     * load's row order even after a product's dailySortKey changed elsewhere.
     *
     * A product that drops out of the active set (deactivated, reordered out via
     * Product Display Order, etc.) is removed from the visible list — otherwise the
     * on-screen row count silently grows past what Product Display Order shows, which
     * is confusing and was reported as "extra/duplicate products". Any such row that
     * still carries typed data is stashed in [orphanedRows] instead of being dropped
     * outright, and restored automatically if the same product becomes active again
     * later in the same session.
     */
    fun refreshProducts() {
        viewModelScope.launch {
            val products = productDao.getActiveProductsByDailySortKeySync()
            val activeIds = products.mapTo(HashSet()) { it.id }
            val reordered = LinkedHashMap<Long, QuickSaleRow>()
            products.forEach { p ->
                val existing = rowsById[p.id] ?: orphanedRows.remove(p.id)
                reordered[p.id] = existing?.copy(product = p) ?: QuickSaleRow(product = p)
            }
            rowsById.forEach { (id, row) ->
                if (id !in activeIds && !row.isUntouched()) orphanedRows[id] = row
            }
            rowsById.clear()
            rowsById.putAll(reordered)
            _rows.value = rowsById.values.toList()
        }
    }

    private fun updateRow(productId: Long, transform: (QuickSaleRow) -> QuickSaleRow) {
        val existing = rowsById[productId] ?: return
        rowsById[productId] = transform(existing)
        _rows.value = rowsById.values.toList()
    }
}
