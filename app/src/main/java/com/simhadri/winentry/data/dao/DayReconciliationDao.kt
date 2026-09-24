package com.simhadri.winentry.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.simhadri.winentry.data.entity.DayReconciliation
import com.simhadri.winentry.data.entity.PendingCloudDelete
import com.simhadri.winentry.data.entity.SyncStatus

@Dao
abstract class DayReconciliationDao {

    // ── Read ──────────────────────────────────────────────────────────────────

    @Query("SELECT COUNT(*) FROM day_reconciliation")
    abstract suspend fun getCount(): Int

    @Query("SELECT * FROM day_reconciliation WHERE date = :date")
    abstract suspend fun getByDate(date: String): DayReconciliation?

    @Query("SELECT * FROM day_reconciliation WHERE date = :date")
    abstract fun observeByDate(date: String): LiveData<DayReconciliation?>

    @Query("SELECT * FROM day_reconciliation ORDER BY date DESC")
    abstract suspend fun getAll(): List<DayReconciliation>

    @Query("SELECT * FROM day_reconciliation WHERE date BETWEEN :startDate AND :endDate ORDER BY date ASC")
    abstract suspend fun getByDateRange(startDate: String, endDate: String): List<DayReconciliation>

    // ── Write ─────────────────────────────────────────────────────────────────

    // Step 1: create the row only if this date has never been saved before.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIgnore(row: DayReconciliation): Long

    // Step 2: always update — harmless double-write on a fresh insert; applies
    // new values when the date already exists. syncStatus is always set to the
    // caller's value (never inherited from the existing row) so every local save
    // marks the record as needing a cloud push.
    @Query("""
        UPDATE day_reconciliation SET
            totalDaySales  = :totalDaySales,
            upiReceipts    = :upiReceipts,
            dayExpenses    = :dayExpenses,
            cashForDeposit = :cashForDeposit,
            deposits       = :deposits,
            notes          = :notes,
            syncStatus     = :syncStatus,
            lastModified   = :now
        WHERE date = :date
    """)
    abstract suspend fun updateByDate(
        date: String,
        totalDaySales: Double,
        upiReceipts: Double,
        dayExpenses: Double,
        cashForDeposit: Double,
        deposits: Double,
        notes: String,
        syncStatus: String,
        now: Long
    )

    // Compatible upsert for all SQLite versions (API 21+).
    // ON CONFLICT … DO UPDATE requires SQLite 3.24+ (Android 10+) and crashed
    // on devices still running Android 8/9 with minSdk=26.
    @Transaction
    open suspend fun upsert(
        date: String,
        totalDaySales: Double,
        upiReceipts: Double,
        dayExpenses: Double,
        cashForDeposit: Double,
        deposits: Double = 0.0,
        notes: String,
        syncStatus: String = SyncStatus.PENDING_UPSERT,
        now: Long = System.currentTimeMillis()
    ) {
        insertIgnore(
            DayReconciliation(
                date           = date,
                totalDaySales  = totalDaySales,
                upiReceipts    = upiReceipts,
                dayExpenses    = dayExpenses,
                cashForDeposit = cashForDeposit,
                deposits       = deposits,
                notes          = notes,
                syncStatus     = syncStatus,
                lastModified   = now
            )
        )
        updateByDate(date, totalDaySales, upiReceipts, dayExpenses, cashForDeposit, deposits, notes, syncStatus, now)
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertOrReplaceAll(rows: List<DayReconciliation>)

    @Query("DELETE FROM day_reconciliation WHERE date = :date")
    abstract suspend fun deleteByDateLocalOnly(date: String)

    @Query("DELETE FROM day_reconciliation WHERE date BETWEEN :startDate AND :endDate")
    abstract suspend fun deleteByDateRangeLocalOnly(startDate: String, endDate: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun queueCloudDeletes(rows: List<PendingCloudDelete>)

    @Query("SELECT * FROM pending_cloud_deletes")
    abstract suspend fun getQueuedCloudDeletes(): List<PendingCloudDelete>

    @Transaction
    open suspend fun deleteByDate(date: String) {
        queueCloudDeletes(listOf(PendingCloudDelete(PendingCloudDelete.SUMMARY, date)))
        deleteByDateLocalOnly(date)
    }

    @Transaction
    open suspend fun deleteByDateRange(startDate: String, endDate: String) {
        queueCloudDeletes(listOf(PendingCloudDelete(PendingCloudDelete.SUMMARY_RANGE, startDate, dateTo = endDate)))
        deleteByDateRangeLocalOnly(startDate, endDate)
    }

    @Query("SELECT date FROM day_reconciliation WHERE syncStatus != '${SyncStatus.SYNCED}'")
    abstract suspend fun getUnsyncedDates(): List<String>

    /** Restore merge: skips dates with unsynced local changes or a queued cloud delete. */
    @Transaction
    open suspend fun mergeFromCloud(rows: List<DayReconciliation>): Int {
        val unsynced = getUnsyncedDates().toHashSet()
        val queued = getQueuedCloudDeletes()
        val keep = rows.filter { r -> r.date !in unsynced && queued.none { it.coversSummary(r.date) } }
        if (keep.isNotEmpty()) insertOrReplaceAll(keep)
        return rows.size - keep.size
    }

    @Query("DELETE FROM day_reconciliation")
    abstract suspend fun deleteAll()

    // ── Sync ──────────────────────────────────────────────────────────────────

    @Query("""
        SELECT * FROM day_reconciliation
        WHERE syncStatus IN ('${SyncStatus.PENDING_UPSERT}', '${SyncStatus.SYNC_ERROR}')
        ORDER BY date ASC
    """)
    abstract suspend fun getPendingSync(): List<DayReconciliation>

    @Query("SELECT COUNT(*) FROM day_reconciliation WHERE syncStatus = '${SyncStatus.SYNC_ERROR}'")
    abstract suspend fun getSyncErrorCount(): Int

    @Query("UPDATE day_reconciliation SET syncStatus = '${SyncStatus.SYNCED}' WHERE date = :date")
    abstract suspend fun markAsSynced(date: String)

    /** Marks only rows that still match what was sent; a row edited during the upload stays pending. */
    @Transaction
    open suspend fun markSyncedIfUnchanged(sent: List<DayReconciliation>) {
        for (s in sent) {
            val cur = getByDate(s.date) ?: continue
            if (cur.copy(syncStatus = s.syncStatus) == s) markAsSynced(s.date)
        }
    }

    @Query("UPDATE day_reconciliation SET syncStatus = '${SyncStatus.SYNC_ERROR}' WHERE date = :date")
    abstract suspend fun markSyncError(date: String)

    @Query("UPDATE day_reconciliation SET syncStatus = '${SyncStatus.PENDING_UPSERT}'")
    abstract suspend fun markAllAsPending()
}
