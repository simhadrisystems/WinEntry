package com.simple.simpleinventory.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.simple.simpleinventory.data.entity.DayReconciliation
import com.simple.simpleinventory.data.entity.SyncStatus
import androidx.room.OnConflictStrategy

@Dao
interface DayReconciliationDao {

    // ── Read ──────────────────────────────────────────────────────────────────

    @Query("SELECT * FROM day_reconciliation WHERE date = :date")
    suspend fun getByDate(date: String): DayReconciliation?

    @Query("SELECT * FROM day_reconciliation WHERE date = :date")
    fun observeByDate(date: String): LiveData<DayReconciliation?>

    @Query("SELECT * FROM day_reconciliation ORDER BY date DESC")
    suspend fun getAll(): List<DayReconciliation>

    @Query("SELECT * FROM day_reconciliation WHERE date BETWEEN :startDate AND :endDate ORDER BY date ASC")
    suspend fun getByDateRange(startDate: String, endDate: String): List<DayReconciliation>

    // ── Write ─────────────────────────────────────────────────────────────────

    /**
     * Safe upsert — ON CONFLICT DO UPDATE so markAsSynced() is never undone
     * by a subsequent replace. Always sets syncStatus = PENDING_UPSERT because
     * any save means the cloud copy needs refreshing.
     */
    @Query("""
        INSERT INTO day_reconciliation
            (date, totalDaySales, upiReceipts, dayExpenses, cashForDeposit,
             notes, syncStatus, lastModified)
        VALUES
            (:date, :totalDaySales, :upiReceipts, :dayExpenses, :cashForDeposit,
             :notes, :syncStatus, :now)
        ON CONFLICT(date) DO UPDATE SET
            totalDaySales  = excluded.totalDaySales,
            upiReceipts    = excluded.upiReceipts,
            dayExpenses    = excluded.dayExpenses,
            cashForDeposit = excluded.cashForDeposit,
            notes          = excluded.notes,
            syncStatus     = :syncStatus,
            lastModified   = excluded.lastModified
    """)
    suspend fun upsert(
        date: String,
        totalDaySales: Double,
        upiReceipts: Double,
        dayExpenses: Double,
        cashForDeposit: Double,
        notes: String,
        syncStatus: String = SyncStatus.PENDING_UPSERT,
        now: Long = System.currentTimeMillis()
    )

    // ── Delete ────────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplaceAll(rows: List<DayReconciliation>)

    @Query("DELETE FROM day_reconciliation WHERE date = :date")
    suspend fun deleteByDate(date: String)

    @Query("DELETE FROM day_reconciliation WHERE date BETWEEN :startDate AND :endDate")
    suspend fun deleteByDateRange(startDate: String, endDate: String)

    @Query("DELETE FROM day_reconciliation")
    suspend fun deleteAll()

    // ── Sync ──────────────────────────────────────────────────────────────────

    @Query("""
        SELECT * FROM day_reconciliation
        WHERE syncStatus IN ('${SyncStatus.PENDING_UPSERT}', '${SyncStatus.SYNC_ERROR}')
        ORDER BY date ASC
    """)
    suspend fun getPendingSync(): List<DayReconciliation>

    @Query("SELECT COUNT(*) FROM day_reconciliation WHERE syncStatus = '${SyncStatus.SYNC_ERROR}'")
    suspend fun getSyncErrorCount(): Int

    @Query("UPDATE day_reconciliation SET syncStatus = '${SyncStatus.SYNCED}' WHERE date = :date")
    suspend fun markAsSynced(date: String)

    @Query("UPDATE day_reconciliation SET syncStatus = '${SyncStatus.SYNC_ERROR}' WHERE date = :date")
    suspend fun markSyncError(date: String)

    @Query("UPDATE day_reconciliation SET syncStatus = '${SyncStatus.PENDING_UPSERT}'")
    suspend fun markAllAsPending()
}
