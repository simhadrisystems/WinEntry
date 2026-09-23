package com.simhadri.winentry.data.dao

import androidx.room.*
import com.simhadri.winentry.data.entity.DailyStock
import com.simhadri.winentry.data.entity.SyncStatus

/**
 * DAO for daily_stock table.
 *
 * Schema v2: one row per PRODUCT per date (brand-level productCode).
 * All 4 sizes, opening snapshots, sale qtys, prices and amounts in one row.
 * 100 writes per full save (was 400 with size-level schema).
 *
 * Upsert strategy: @Insert(onConflict = REPLACE) used throughout.
 * This replaces the old @Query INSERT...ON CONFLICT approach which
 * caused KSP parsing issues with complex SQL in some Room versions.
 */
@Dao
interface DailyStockDao {

    // ── Read ──────────────────────────────────────────────────────────────────

    @Query("SELECT * FROM daily_stock WHERE date = :date AND productCode = :productCode")
    suspend fun getDailyStock(date: String, productCode: String): DailyStock?

    @Query("SELECT * FROM daily_stock WHERE date = :date ORDER BY productCode")
    suspend fun getAllDailyStockForDate(date: String): List<DailyStock>

    /**
     * Previous day's closing for a product — all 4 sizes in one row.
     * Returns most recent committed row before [date].
     */
    @Query("""
        SELECT * FROM daily_stock
        WHERE productCode = :productCode
          AND date < :date
          AND isCommitted = 1
        ORDER BY date DESC LIMIT 1
    """)
    suspend fun getLastCommittedBeforeDate(productCode: String, date: String): DailyStock?

    /**
     * Bulk previous-day closings — one DB round-trip for all products.
     * Returns one row per productCode (most recent committed before [date]).
     */
    @Query("""
        SELECT * FROM daily_stock d1
        WHERE productCode IN (:codes)
          AND isCommitted = 1
          AND date < :date
          AND date = (
              SELECT MAX(d2.date) FROM daily_stock d2
              WHERE d2.productCode = d1.productCode
                AND d2.isCommitted = 1
                AND d2.date < :date
          )
    """)
    suspend fun getBulkLastCommittedBeforeDate(
        codes: List<String>,
        date:  String
    ): List<DailyStock>

    /**
     * Subsequent committed rows for cascade recalculation.
     */
    @Query("""
        SELECT * FROM daily_stock
        WHERE productCode = :productCode
          AND date > :afterDate
          AND isCommitted = 1
        ORDER BY date ASC
    """)
    suspend fun getSubsequentCommittedRows(
        productCode: String,
        afterDate:   String
    ): List<DailyStock>

    // ── Write — committed rows ────────────────────────────────────────────────

    /**
     * Insert or replace a committed row.
     * REPLACE strategy drops the old row and inserts the new one atomically —
     * safe because we always write all columns.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(stock: DailyStock)

    /** Batch insert-or-replace — single transaction. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplaceAll(rows: List<DailyStock>)

    /**
     * Upsert one committed row — sets syncStatus = PENDING_UPSERT.
     * A row on a baseline date keeps isOpeningStock even if the caller built it without the flag.
     */
    @Transaction
    suspend fun upsertCommitted(stock: DailyStock) {
        upsertCommittedBatch(listOf(stock))
    }

    /** Batch upsert — single SQLite transaction for the entire save. */
    @Transaction
    suspend fun upsertCommittedBatch(rows: List<DailyStock>) {
        if (rows.isEmpty()) return
        val baselineDates = getAllOpeningStockDates().toHashSet()
        val now = System.currentTimeMillis()
        insertOrReplaceAll(
            rows.map { r ->
                r.copy(
                    isCommitted    = true,
                    isOpeningStock = r.isOpeningStock || r.date in baselineDates,
                    syncStatus     = SyncStatus.PENDING_UPSERT,
                    lastModified   = now
                )
            }
        )
    }

    /**
     * Update opening snapshots, sale qtys and amounts after cascade recalculation.
     * openXx must be updated because previous day's closing changed.
     * Never touches closeXx — user-entered closing is preserved.
     */
    @Query("""
        UPDATE daily_stock SET
            openQq = :openQq, openPp = :openPp, openNn = :openNn, openDd = :openDd,
            saleQq = :saleQq, salePp = :salePp, saleNn = :saleNn, saleDd = :saleDd,
            amountQq = :amountQq, amountPp = :amountPp,
            amountNn = :amountNn, amountDd = :amountDd,
            saleAmount   = :saleAmount,
            syncStatus   = :syncStatus,
            lastModified = :now
        WHERE date = :date AND productCode = :productCode AND isCommitted = 1
    """)
    suspend fun updateSaleAmounts(
        date: String, productCode: String,
        openQq: Int, openPp: Int, openNn: Int, openDd: Int,
        saleQq: Int, salePp: Int, saleNn: Int, saleDd: Int,
        amountQq: Double, amountPp: Double, amountNn: Double, amountDd: Double,
        saleAmount: Double,
        syncStatus: String = SyncStatus.PENDING_UPSERT,
        now: Long = System.currentTimeMillis()
    )

    // ── Write — draft rows ────────────────────────────────────────────────────

    /**
     * Insert a draft row only if no row exists yet.
     * IGNORE strategy means existing rows (committed or draft) are never touched.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDraftIfAbsent(stock: DailyStock)

    // ── Delete ────────────────────────────────────────────────────────────────

    @Query("DELETE FROM daily_stock WHERE date = :date AND productCode = :productCode")
    suspend fun deleteDailyStock(date: String, productCode: String)

    @Query("DELETE FROM daily_stock WHERE date = :date")
    suspend fun deleteAllForDate(date: String)

    /** Removes an auto-created draft; never touches a committed row. */
    @Query("DELETE FROM daily_stock WHERE date = :date AND productCode = :productCode AND isCommitted = 0")
    suspend fun deleteDraft(date: String, productCode: String)

    @Query("DELETE FROM daily_stock WHERE productCode = :productCode")
    suspend fun deleteDailyStockByProduct(productCode: String)

    @Query("DELETE FROM daily_stock")
    suspend fun deleteAll()

    // ── Sync ──────────────────────────────────────────────────────────────────

    @Query("""
        SELECT * FROM daily_stock
        WHERE isCommitted = 1
          AND syncStatus IN ('PENDING_UPSERT', 'SYNC_ERROR')
        ORDER BY date ASC, productCode ASC
    """)
    suspend fun getPendingSyncStock(): List<DailyStock>

    @Query("SELECT COUNT(*) FROM daily_stock WHERE syncStatus = 'SYNC_ERROR'")
    suspend fun getSyncErrorCount(): Int

    @Query("UPDATE daily_stock SET syncStatus = 'SYNCED' WHERE date = :date AND productCode = :productCode")
    suspend fun markStockAsSynced(date: String, productCode: String)

    @Query("UPDATE daily_stock SET syncStatus = 'SYNC_ERROR' WHERE date = :date AND productCode = :productCode")
    suspend fun markStockSyncError(date: String, productCode: String)

    @Query("UPDATE daily_stock SET syncStatus = 'PENDING_UPSERT' WHERE isCommitted = 1")
    suspend fun markAllCommittedAsPending()

    /** All committed rows in a date range — used by Monthly Summary. */
    @Query("SELECT * FROM daily_stock WHERE isCommitted = 1 AND date BETWEEN :startDate AND :endDate ORDER BY date ASC")
    suspend fun getAllDailyStockForDateRange(startDate: String, endDate: String): List<DailyStock>

    /** Distinct dates that have at least one committed row — used by Opening Stock manager. */
    @Query("SELECT DISTINCT date FROM daily_stock WHERE isCommitted = 1 ORDER BY date ASC")
    suspend fun getCommittedDates(): List<String>

    /** The earliest date with any committed row — the true opening stock date. */
    @Query("SELECT MIN(date) FROM daily_stock WHERE isCommitted = 1")
    suspend fun getEarliestCommittedDate(): String?

    /** The most recent date with any committed row — used to bound new purchase/re-baseline dates. */
    @Query("SELECT MAX(date) FROM daily_stock WHERE isCommitted = 1")
    suspend fun getLatestCommittedDate(): String?

    /**
     * The ACTIVE opening stock date — the most recent date marked isOpeningStock.
     * Older marked dates are prior re-baselines, kept as read-only Daily Stock history.
     */
    @Query("SELECT MAX(date) FROM daily_stock WHERE isOpeningStock = 1")
    suspend fun getLatestOpeningStockDate(): String?

    /** All dates ever marked as an opening-stock baseline, most recent first — the
     *  active one is the first (MAX) entry, the rest are prior re-baselines kept as
     *  read-only Daily Stock history. */
    @Query("SELECT DISTINCT date FROM daily_stock WHERE isOpeningStock = 1 ORDER BY date DESC")
    suspend fun getAllOpeningStockDates(): List<String>

    /**
     * Self-heal fallback for [getLatestOpeningStockDate]: the most recent date whose
     * committed rows LOOK like opening stock in aggregate (whole-day open total>0,
     * whole-day sale total=0) but were never flagged — e.g. data restored from a
     * cloud sheet uploaded before the isOpeningStock column existed. Only consulted
     * when the flag-based lookup finds nothing despite committed data being present.
     * Deliberately a day-level aggregate, not a per-row check (see [backfillOpeningStockFlagForDate]).
     */
    @Query("""
        SELECT date FROM daily_stock
        WHERE isCommitted = 1
        GROUP BY date
        HAVING SUM(ABS(saleQq)+ABS(salePp)+ABS(saleNn)+ABS(saleDd)) = 0
           AND SUM(openQq+openPp+openNn+openDd) > 0
        ORDER BY date DESC LIMIT 1
    """)
    suspend fun findLikelyOpeningStockDate(): String?

    /**
     * Persists the self-heal result and marks the corrected rows for re-sync.
     * Flags every committed row on [date] — [findLikelyOpeningStockDate] already
     * verified the whole day qualifies, so no further per-row open/sale check here
     * (a per-row check would under-flag any product that had zero sales *and* zero
     * opening balance that day, e.g. a product added after the baseline).
     */
    @Query("""
        UPDATE daily_stock SET isOpeningStock = 1, syncStatus = 'PENDING_UPSERT'
        WHERE date = :date AND isCommitted = 1
    """)
    suspend fun backfillOpeningStockFlagForDate(date: String)

    data class BaselineCandidate(val date: String, val markedCount: Int, val committedCount: Int)

    /** Every date with at least one isOpeningStock row, newest first, with how many of its
     *  committed rows carry the marker — markedCount < committedCount means the marker is incomplete. */
    @Query("""
        SELECT date,
               SUM(CASE WHEN isOpeningStock = 1 THEN 1 ELSE 0 END) AS markedCount,
               COUNT(*) AS committedCount
        FROM daily_stock
        WHERE isCommitted = 1
        GROUP BY date
        HAVING markedCount > 0
        ORDER BY date DESC
    """)
    suspend fun getBaselineCandidates(): List<BaselineCandidate>

    @Query("""
        UPDATE daily_stock SET isOpeningStock = 0, syncStatus = 'PENDING_UPSERT', lastModified = :now
        WHERE isOpeningStock = 1 AND date > :date
    """)
    suspend fun clearOpeningStockFlagsAfter(date: String, now: Long)

    @Query("""
        UPDATE daily_stock SET isOpeningStock = 1, syncStatus = 'PENDING_UPSERT', lastModified = :now
        WHERE date = :date AND isCommitted = 1 AND isOpeningStock = 0
    """)
    suspend fun markOpeningStockFlagForDate(date: String, now: Long)

    /** Makes [date] the only active baseline: flags all its rows, unflags later dates. Quantities untouched. */
    @Transaction
    suspend fun setActiveBaseline(date: String) {
        val now = System.currentTimeMillis()
        clearOpeningStockFlagsAfter(date, now)
        markOpeningStockFlagForDate(date, now)
    }

    /**
     * Returns 1 if the product has any committed row with non-zero closing balance,
     * 0 otherwise. Used to block deactivation of products that still have stock.
     */
    @Query("""
        SELECT COUNT(*) FROM daily_stock
        WHERE productCode = :productCode
          AND isCommitted = 1
          AND (closeQq + closePp + closeNn + closeDd) > 0
    """)
    suspend fun hasNonZeroClosingBalance(productCode: String): Int

    /**
     * Sync statuses for all opening-stock rows on [date].
     * Used by Opening Stock Setup screen to show Synced / Pending badge.
     */
    @Query("""
        SELECT syncStatus FROM daily_stock
        WHERE date = :date
          AND isCommitted = 1
          AND isOpeningStock = 1
    """)
    suspend fun getOpeningStockSyncStatuses(date: String): List<String>
}
