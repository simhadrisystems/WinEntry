package com.simhadri.winentry.ui.reports

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.simhadri.winentry.data.AppDatabase
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

data class DaySummaryRow(
    val date:                String,
    val displayDate:         String,
    val purchases:           Double,
    val sales:               Double,
    val upiReceipts:         Double,
    val dayExpenses:         Double,
    val cashForDeposit:      Double,
    val notes:               String,
    val noReconciliation:    Boolean, // true = day-end never saved
    val staleReconciliation: Boolean  // true = saved but CB changed since; re-save needed
)

data class MonthSummary(
    val rows:                       List<DaySummaryRow>,
    val totalPurchases:             Double,
    val totalSales:                 Double,
    val totalUpi:                   Double,
    val totalExpenses:              Double,
    val totalCash:                  Double,
    val daysWithoutReconciliation:  Int,  // saved reconciliation missing
    val daysStaleReconciliation:    Int   // saved but CB changed — re-save needed
)

class MonthlySummaryViewModel(app: Application) : AndroidViewModel(app) {

    private val db       = AppDatabase.getInstance(app)
    private val recDao   = db.dayReconciliationDao()
    private val stockDao = db.dailyStockDao()
    private val purchDao = db.purchaseDao()

    private val dbFmt   = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val lblFmt  = SimpleDateFormat("MMMM yyyy",  Locale.getDefault())
    private val dispFmt = SimpleDateFormat("dd/MM/yy",   Locale.getDefault())

    private val cal = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }

    private val _monthLabel = MutableLiveData<String>()
    val monthLabel: LiveData<String> = _monthLabel

    private val _summary = MutableLiveData<MonthSummary>()
    val summary: LiveData<MonthSummary> = _summary

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    init { load() }

    fun previousMonth() { cal.add(Calendar.MONTH, -1); load() }
    fun nextMonth()     { cal.add(Calendar.MONTH,  1); load() }

    /** Called from onResume to pick up changes made in Daily Stock / Reconciliation. */
    fun refresh() { load() }
    fun goToMonth(year: Int, month: Int) {
        cal.set(Calendar.YEAR, year)
        cal.set(Calendar.MONTH, month)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        load()
    }
    fun currentCalendar(): Calendar = cal.clone() as Calendar

    private fun load() {
        _monthLabel.value = lblFmt.format(cal.time)
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val start = cal.clone() as Calendar
                start.set(Calendar.DAY_OF_MONTH, 1)
                val end = cal.clone() as Calendar
                end.set(Calendar.DAY_OF_MONTH, end.getActualMaximum(Calendar.DAY_OF_MONTH))
                val s = dbFmt.format(start.time)
                val e = dbFmt.format(end.time)

                // ── Sale totals — single authoritative source ─────────────────────
                //
                // DayReconciliation.totalDaySales is the ONLY reliable sale figure.
                // It is saved when the user taps SAVE on the day-end reconciliation
                // footer, capturing exactly what the screen showed (opening + purchase
                // − closing, summed across all products × their sale prices).
                //
                // daily_stock.saleAmount is NOT used because it can be stale:
                // if purchases arrive after the day was saved, the stored saleAmount
                // reflects purchaseQty=0 and produces wrong/negative derived values.
                //
                // For days where the user has not yet saved day-end reconciliation,
                // sales show as 0 and noReconciliation=true flags the row visually.
                // The user should open that day in Daily Stock and tap SAVE on the
                // reconciliation footer to lock in the correct figure.

                val reconcMap = recDao.getByDateRange(s, e).associateBy { it.date }

                // All committed stock rows for the month — used for dates and staleness check
                val stockRows = stockDao.getAllDailyStockForDateRange(s, e)
                    .filter { it.isCommitted }
                val stockDates = stockRows.map { it.date }.toSortedSet()

                val purchMap = mutableMapOf<String, Double>()
                purchDao.getPurchasesForDateRange(s, e).forEach { p ->
                    purchMap[p.purchaseDate] = (purchMap[p.purchaseDate] ?: 0.0) + p.totalCost
                }

                val allDates = (stockDates + purchMap.keys + reconcMap.keys).toSortedSet()

                // Staleness: map of date → max lastModified across all committed stock rows.
                // If any stock row was saved AFTER the reconciliation, the reconciliation
                // is stale (CB changed since day-end was saved — user should re-save it).
                val stockLastModifiedMap: Map<String, Long> = stockRows
                    .groupBy { it.date }
                    .mapValues { (_, dateRows) -> dateRows.maxOf { it.lastModified } }

                // Only flag dates up to today — future dates cannot have reconciliation yet
                val todayStr  = dbFmt.format(java.util.Date())

                var noRecCount    = 0
                var staleRecCount = 0
                val rows = allDates.map { date ->
                    val rec       = reconcMap[date]
                    val hasRec    = rec != null
                    val sales     = rec?.totalDaySales ?: 0.0
                    val purchases = purchMap[date]     ?: 0.0
                    val upi       = rec?.upiReceipts   ?: 0.0
                    val expenses  = rec?.dayExpenses   ?: 0.0
                    val cash      = rec?.cashForDeposit ?: 0.0
                    val isPast    = date <= todayStr
                    val noRec     = isPast && !hasRec && (date in stockDates || purchases > 0)
                    // Stale: any committed stock row was saved AFTER reconciliation.
                    // False positives are prevented by cascade skip-unchanged logic —
                    // days where nothing changed are not written and lastModified is
                    // not touched, so they are not flagged here.
                    val stockLastMod = stockLastModifiedMap[date] ?: 0L
                    val recLastMod   = rec?.lastModified ?: Long.MAX_VALUE
                    val stale        = isPast && hasRec && stockLastMod > recLastMod
                    if (noRec)  noRecCount++
                    if (stale)  staleRecCount++
                    DaySummaryRow(
                        date                 = date,
                        displayDate          = try { dispFmt.format(dbFmt.parse(date)!!) } catch (_: Exception) { date },
                        purchases            = purchases,
                        sales                = sales,
                        upiReceipts          = upi,
                        dayExpenses          = expenses,
                        cashForDeposit       = cash,
                        notes                = rec?.notes ?: "",
                        noReconciliation     = noRec,
                        staleReconciliation  = stale
                    )
                }

                _summary.value = MonthSummary(
                    rows                      = rows,
                    totalPurchases            = rows.sumOf { it.purchases },
                    totalSales                = rows.sumOf { it.sales },
                    totalUpi                  = rows.sumOf { it.upiReceipts },
                    totalExpenses             = rows.sumOf { it.dayExpenses },
                    totalCash                 = rows.sumOf { it.cashForDeposit },
                    daysWithoutReconciliation = noRecCount,
                    daysStaleReconciliation   = staleRecCount
                )
            } finally {
                _isLoading.value = false
            }
        }
    }
}
