package com.simhadri.winentry.ui.dailystock

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.simhadri.winentry.data.AppDatabase
import com.simhadri.winentry.data.entity.DayReconciliation
import com.simhadri.winentry.data.repository.DayReconciliationRepository
import kotlinx.coroutines.launch
import com.simhadri.winentry.ui.auth.ErrorLogger

/**
 * ViewModel for the day-end reconciliation footer card.
 *
 * Lifecycle mirrors the DailyStockFragment — one instance shared via
 * the Fragment's viewModelScope so it responds to date changes.
 */
class DayReconciliationViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: DayReconciliationRepository

    init {
        val db = AppDatabase.getInstance(application)
        repository = DayReconciliationRepository(
            reconciliationDao = db.dayReconciliationDao()
        )
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private val _reconciliation = MutableLiveData<DayReconciliation?>()
    val reconciliation: LiveData<DayReconciliation?> = _reconciliation

    /** Live total day sales pulled from DailyStock for the current date. */
    private val _totalDaySales = MutableLiveData<Double>(0.0)
    val totalDaySales: LiveData<Double> = _totalDaySales

    /** Calculated cash for deposit: totalDaySales - upi - expenses */
    private val _cashForDeposit = MutableLiveData<Double>(0.0)
    val cashForDeposit: LiveData<Double> = _cashForDeposit

    private val _saveStatus = MutableLiveData<SaveStatus?>()
    val saveStatus: LiveData<SaveStatus?> = _saveStatus

    // In-memory working values (not saved until user taps Save)
    private var workingUpi      = 0.0
    private var workingExpenses = 0.0
    private var workingDeposits = 0.0
    private var workingNotes    = ""
    private var currentDate     = ""

    // ── Load ──────────────────────────────────────────────────────────────────

    /**
     * Called whenever the date changes in DailyStockFragment.
     * Loads any existing reconciliation record and the live total sales figure.
     */
    // Flag set to true when loadForDate() is called — consumed once by Fragment
    // to tell the adapter to force-clear fields on date navigation.
    private var dateChangedPending = false

    /** Returns true once after a date change, then false until the next change. */
    fun consumeDateChanged(): Boolean {
        val v = dateChangedPending
        dateChangedPending = false
        return v
    }

    fun loadForDate(date: String) {
        dateChangedPending = true
        currentDate = date
        viewModelScope.launch {
            // Load existing reconciliation record (UPI, expenses, notes)
            // Total day sales is pushed separately via setTotalDaySales()
            // from the Fragment's dailyEntries observer so it always reflects
            // the live in-memory cache, not the DB saleAmount column.
            val existing = repository.getByDate(date)
            _reconciliation.value = existing

            if (existing != null) {
                workingUpi      = existing.upiReceipts
                workingExpenses = existing.dayExpenses
                workingDeposits = existing.deposits
                workingNotes    = existing.notes
            } else {
                workingUpi      = 0.0
                workingExpenses = 0.0
                workingDeposits = 0.0
                workingNotes    = ""
            }

            recalculateCashForDeposit()
        }
    }

    /**
     * Receive the live total day sales from the Fragment.
     * Called every time dailyEntries LiveData emits — the total is summed
     * directly from the entriesCache so it matches the top-row figure exactly,
     * even on purchase days with uncommitted (draft) entries.
     */
    fun setTotalDaySales(total: Double) {
        _totalDaySales.value = total
        recalculateCashForDeposit()
    }

    // ── Working value updates (called on each EditText change) ────────────────

    fun updateUpi(value: Double) {
        workingUpi = value
        recalculateCashForDeposit()
    }

    fun updateExpenses(value: Double) {
        workingExpenses = value
        recalculateCashForDeposit()
    }

    fun updateDeposits(value: Double) {
        workingDeposits = value
    }

    fun updateNotes(value: String) {
        workingNotes = value
    }

    private fun recalculateCashForDeposit() {
        val total = _totalDaySales.value ?: 0.0
        _cashForDeposit.value = DayReconciliation.calculateCashForDeposit(
            total, workingUpi, workingExpenses
        )
    }

    // ── Save ──────────────────────────────────────────────────────────────────

    fun save() {
        if (currentDate.isEmpty()) return
        viewModelScope.launch {
            try {
                _saveStatus.value = SaveStatus.Saving
                val total = _totalDaySales.value ?: 0.0
                repository.save(
                    date          = currentDate,
                    totalDaySales = total,
                    upiReceipts   = workingUpi,
                    dayExpenses   = workingExpenses,
                    deposits      = workingDeposits,
                    notes         = workingNotes
                )
                // Reload to reflect saved state
                _reconciliation.value = repository.getByDate(currentDate)
                _saveStatus.value = SaveStatus.Success
            } catch (e: Exception) {
                ErrorLogger.log(
                    getApplication(),
                    "DayReconciliation",
                    "Save failed for date=$currentDate",
                    e
                )
                _saveStatus.value = SaveStatus.Error(e.message ?: "Save failed")
            }
        }
    }

    fun clearSaveStatus() { _saveStatus.value = null }

    // ── Sealed classes ────────────────────────────────────────────────────────

    sealed class SaveStatus {
        object Saving : SaveStatus()
        object Success : SaveStatus()
        data class Error(val message: String) : SaveStatus()
    }
}
