package com.simple.simpleinventory.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.simple.simpleinventory.data.AppDatabase
import com.simple.simpleinventory.data.entity.Product
import com.simple.simpleinventory.data.repository.DailyStockRepository
import kotlinx.coroutines.launch

/**
 * Product Display Order ViewModel — Draft-then-Save workflow
 *
 * EDIT OFF (default):
 *   - Active section: read-only, sorted by dailySortKey.
 *   - Inactive section: alphabetical, read-only.
 *   - Long-press active → deactivate. Long-press inactive → activate.
 *   - Search filters both sections simultaneously.
 *
 * EDIT ON:
 *   - Active section: drag / nudge / type badge number.
 *   - Inactive section: still visible but not editable.
 *   - No DB writes until "Renumber & Save".
 *
 * "Renumber & Save":
 *   - Assigns sequential 1..N dailySortKey values to active products in visual order.
 */
class ProductOrderViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: DailyStockRepository

    // Last committed active order from the database
    private var dbProducts: List<Product> = emptyList()

    // Last loaded inactive products
    private var dbInactive: List<Product> = emptyList()

    // Draft labels: product.id → number the user has assigned (may have dupes/gaps)
    private val draftLabels = mutableMapOf<Long, Int>()

    private val _editEnabled = MutableLiveData(false)
    val editEnabled: LiveData<Boolean> = _editEnabled

    // Active products — sorted by draftLabels, filtered by search
    private val _displayList = MutableLiveData<List<Product>>()
    val displayList: LiveData<List<Product>> = _displayList

    // Inactive products — alphabetical, filtered by search
    private val _inactiveList = MutableLiveData<List<Product>>()
    val inactiveList: LiveData<List<Product>> = _inactiveList

    private var searchQuery = ""

    private val _saveStatus = MutableLiveData<SaveStatus?>(null)
    val saveStatus: LiveData<SaveStatus?> = _saveStatus

    private val _scrollToTop = MutableLiveData(false)
    val scrollToTop: LiveData<Boolean> = _scrollToTop

    init {
        val db = AppDatabase.getInstance(application)
        repository = DailyStockRepository(db.productDao(), db.dailyStockDao())
        loadFromDb()
    }

    // ── Data loading ──────────────────────────────────────────────

    fun loadFromDb() {
        viewModelScope.launch {
            dbProducts = repository.getActiveProductsSortedSync()
            dbInactive = repository.getInactiveProductsSync()
            syncDraftFromDb()
            publish()
        }
    }

    private fun syncDraftFromDb() {
        draftLabels.clear()
        dbProducts.forEachIndexed { i, p ->
            draftLabels[p.id] = if (p.dailySortKey in 1..998) p.dailySortKey else (i + 1)
        }
    }

    // ── Edit mode toggle ──────────────────────────────────────────

    fun setEditEnabled(on: Boolean) {
        if (!on) syncDraftFromDb()
        _editEnabled.value = on
        publish()
    }

    // ── Search ────────────────────────────────────────────────────

    fun setSearchQuery(q: String) {
        searchQuery = q.trim()
        publish()
    }

    fun clearSearch() {
        searchQuery = ""
        publish()
    }

    // ── Draft mutations ───────────────────────────────────────────

    fun onVisualReorder(newOrder: List<Product>) {
        newOrder.forEachIndexed { i, p -> draftLabels[p.id] = i + 1 }
        publish()
    }

    fun onLabelTyped(productId: Long, typedNumber: Int) {
        draftLabels[productId] = typedNumber.coerceIn(1, 9999)
        publish()
    }

    fun getDraftLabel(productId: Long): Int = draftLabels[productId] ?: 9999

    // ── Commit (Renumber & Save) ──────────────────────────────────

    fun renumberAndSave() {
        viewModelScope.launch {
            _saveStatus.value = SaveStatus.Saving
            try {
                val visualOrder = buildActiveList(dbProducts, searchQuery = "")
                val updated = visualOrder.mapIndexed { i, p -> p.copy(dailySortKey = i + 1) }
                repository.updateSortKeys(updated)
                dbProducts = updated
                syncDraftFromDb()
                _editEnabled.value = false
                publish()
                _scrollToTop.value = true
                _saveStatus.value = SaveStatus.Success
            } catch (e: Exception) {
                _saveStatus.value = SaveStatus.Error(e.message ?: "Unknown error")
            }
        }
    }

    // ── Activate / Deactivate ─────────────────────────────────────

    fun deactivateProduct(product: Product) {
        viewModelScope.launch {
            repository.deactivateProduct(product)
            loadFromDb()
        }
    }

    fun activateProduct(product: Product) {
        viewModelScope.launch {
            repository.activateProduct(product)
            loadFromDb()
        }
    }

    fun clearScrollToTop() { _scrollToTop.value = false }
    fun clearSaveStatus()   { _saveStatus.value = null }

    // ── Internal ─────────────────────────────────────────────────

    private fun publish() {
        val filter = searchQuery
        _displayList.postValue(buildActiveList(dbProducts, filter))
        _inactiveList.postValue(
            if (filter.isEmpty()) dbInactive
            else dbInactive.filter {
                it.displayName.contains(filter, ignoreCase = true) ||
                it.brandCode.contains(filter, ignoreCase = true)
            }
        )
    }

    private fun buildActiveList(base: List<Product>, searchQuery: String): List<Product> {
        val filtered = if (searchQuery.isEmpty()) base
        else base.filter {
            it.displayName.contains(searchQuery, ignoreCase = true) ||
            it.brandCode.contains(searchQuery, ignoreCase = true)
        }
        return filtered.sortedWith(compareBy({ draftLabels[it.id] ?: 9999 }, { it.displayName }))
    }

    sealed class SaveStatus {
        object Saving  : SaveStatus()
        object Success : SaveStatus()
        data class Error(val message: String) : SaveStatus()
    }
}
