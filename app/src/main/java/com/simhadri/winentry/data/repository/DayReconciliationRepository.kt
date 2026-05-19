package com.simhadri.winentry.data.repository

import androidx.lifecycle.LiveData
import com.simhadri.winentry.data.dao.DayReconciliationDao
import com.simhadri.winentry.data.entity.DayReconciliation

/**
 * Repository for day-end reconciliation records.
 *
 * Total day sales are fetched via [DailyStockRepository.getTotalDaySales]
 * rather than injecting DailyStockDao here directly — each repository
 * owns only its own DAO.
 */
class DayReconciliationRepository(
    private val reconciliationDao: DayReconciliationDao
) {

    // ── Read ──────────────────────────────────────────────────────────────────

    suspend fun getByDate(date: String): DayReconciliation? =
        reconciliationDao.getByDate(date)

    fun observeByDate(date: String): LiveData<DayReconciliation?> =
        reconciliationDao.observeByDate(date)

    suspend fun getByDateRange(startDate: String, endDate: String): List<DayReconciliation> =
        reconciliationDao.getByDateRange(startDate, endDate)

    // ── Write ─────────────────────────────────────────────────────────────────

    /**
     * Save a day reconciliation record.
     * cashForDeposit is always calculated here — never trusted from the UI.
     */
    suspend fun save(
        date: String,
        totalDaySales: Double,
        upiReceipts: Double,
        dayExpenses: Double,
        deposits: Double = 0.0,
        notes: String
    ) {
        reconciliationDao.upsert(
            date           = date,
            totalDaySales  = totalDaySales,
            upiReceipts    = upiReceipts,
            dayExpenses    = dayExpenses,
            cashForDeposit = DayReconciliation.calculateCashForDeposit(
                totalDaySales, upiReceipts, dayExpenses
            ),
            deposits       = deposits,
            notes          = notes
        )
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    suspend fun deleteByDate(date: String) = reconciliationDao.deleteByDate(date)
    suspend fun deleteAll()                = reconciliationDao.deleteAll()

    // ── Sync ──────────────────────────────────────────────────────────────────

    suspend fun getPendingSync(): List<DayReconciliation> =
        reconciliationDao.getPendingSync()

    suspend fun markAsSynced(date: String)  = reconciliationDao.markAsSynced(date)
    suspend fun markSyncError(date: String) = reconciliationDao.markSyncError(date)
    suspend fun forceFullResync()           = reconciliationDao.markAllAsPending()
}
