package com.simple.simpleinventory.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.simple.simpleinventory.data.entity.Purchase
import com.simple.simpleinventory.data.entity.SyncStatus

@Dao
interface PurchaseDao {

    // ── Write ─────────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(purchase: Purchase): Long

    @Update
    suspend fun update(purchase: Purchase)

    @Delete
    suspend fun delete(purchase: Purchase)

    @Query("DELETE FROM purchases")
    suspend fun deleteAll()

    // ── Live queries (for UI observation) ────────────────────────────────────

    @Query("SELECT * FROM purchases WHERE isDeleted = 0 ORDER BY purchaseDate DESC, id ASC")
    fun getAllPurchases(): LiveData<List<Purchase>>

    @Query("SELECT * FROM purchases WHERE isProcessed = 0 AND isDeleted = 0 ORDER BY purchaseDate ASC, id ASC")
    fun getUnprocessedPurchases(): LiveData<List<Purchase>>

    @Query("SELECT * FROM purchases WHERE purchaseDate = :date AND isDeleted = 0 ORDER BY id ASC")
    fun getPurchasesByDate(date: String): LiveData<List<Purchase>>

    @Query("SELECT * FROM purchases WHERE productId = :productId AND isDeleted = 0 ORDER BY purchaseDate DESC")
    fun getPurchasesByProduct(productId: Long): LiveData<List<Purchase>>

    @Query("SELECT * FROM purchases WHERE purchaseDate BETWEEN :startDate AND :endDate AND isDeleted = 0 ORDER BY purchaseDate DESC")
    fun getPurchasesByDateRange(startDate: String, endDate: String): LiveData<List<Purchase>>

    // ── Suspend queries ───────────────────────────────────────────────────────

    @Query("SELECT * FROM purchases WHERE id = :id")
    suspend fun getPurchaseById(id: Long): Purchase?

    @Query("SELECT * FROM purchases WHERE txnId = :txnId LIMIT 1")
    suspend fun getPurchaseByTxnId(txnId: String): Purchase?

    @Query("SELECT * FROM purchases WHERE purchaseDate BETWEEN :startDate AND :endDate AND isDeleted = 0 ORDER BY purchaseDate ASC")
    suspend fun getPurchasesByDateRangeSync(startDate: String, endDate: String): List<Purchase>

    @Query("SELECT * FROM purchases WHERE purchaseDate = :date AND productId = :productId AND isDeleted = 0 ORDER BY id ASC")
    suspend fun getPurchasesByDateAndProduct(date: String, productId: Long): List<Purchase>

    @Query("SELECT * FROM purchases WHERE purchaseDate = :date AND isDeleted = 0 ORDER BY productId ASC, id ASC")
    suspend fun getPurchasesByDateSync(date: String): List<Purchase>

    @Query("SELECT * FROM purchases WHERE invoiceNumber = :invoiceNumber AND purchaseDate = :date AND isDeleted = 0")
    suspend fun getPurchasesByInvoiceAndDate(invoiceNumber: String, date: String): List<Purchase>

    @Query("SELECT * FROM purchases ORDER BY purchaseDate DESC")
    suspend fun getAllPurchasesSync(): List<Purchase>

    // ── Aggregates ────────────────────────────────────────────────────────────

    @Query("SELECT SUM(totalCost) FROM purchases WHERE purchaseDate BETWEEN :startDate AND :endDate AND isDeleted = 0")
    suspend fun getTotalCostByDateRange(startDate: String, endDate: String): Double?

    @Query("SELECT SUM(qqTotalUnits + ppTotalUnits + nnTotalUnits + ddTotalUnits) FROM purchases WHERE productId = :productId AND purchaseDate BETWEEN :startDate AND :endDate AND isDeleted = 0")
    suspend fun getTotalUnitsPurchased(productId: Long, startDate: String, endDate: String): Int?

    // ── Daily-stock processing flags ──────────────────────────────────────────

    @Query("UPDATE purchases SET isProcessed = 1 WHERE purchaseDate = :date AND productId = :productId")
    suspend fun markPurchasesAsProcessed(date: String, productId: Long)

    @Query("UPDATE purchases SET isProcessed = 1 WHERE id IN (:purchaseIds)")
    suspend fun markPurchasesAsProcessedByIds(purchaseIds: List<Long>)

    @Query("UPDATE purchases SET isProcessed = 0 WHERE purchaseDate = :date")
    suspend fun resetProcessedFlagForDate(date: String)

    @Query("UPDATE purchases SET isProcessed = 0")
    suspend fun resetAllProcessedFlags()

    // ── Sync ──────────────────────────────────────────────────────────────────

    @Query("""
        SELECT * FROM purchases
        WHERE syncStatus IN (
            '${SyncStatus.PENDING_INSERT}',
            '${SyncStatus.PENDING_UPDATE}',
            '${SyncStatus.PENDING_DELETE}',
            '${SyncStatus.SYNC_ERROR}'
        )
        ORDER BY purchaseDate ASC, id ASC
    """)
    suspend fun getPendingSyncPurchases(): List<Purchase>

    @Query("UPDATE purchases SET syncStatus = '${SyncStatus.SYNCED}' WHERE id = :id")
    suspend fun markAsSynced(id: Long)

    @Query("UPDATE purchases SET syncStatus = '${SyncStatus.SYNC_ERROR}' WHERE id = :id")
    suspend fun markSyncError(id: Long)

    @Query("SELECT COUNT(*) FROM purchases WHERE syncStatus = '${SyncStatus.SYNC_ERROR}'")
    suspend fun getSyncErrorCount(): Int

    @Query("DELETE FROM purchases WHERE id = :id AND isDeleted = 1")
    suspend fun hardDeleteAfterSync(id: Long)

    /** Delete existing purchase rows by invoice+product+date — used for Replace on down-sync. */
    @Query("""DELETE FROM purchases
              WHERE invoiceNumber = :invoice
                AND productCode   = :productCode
                AND purchaseDate  = :date
                AND isDeleted = 0""")
    suspend fun deleteByInvoiceProductDate(invoice: String, productCode: String, date: String)

    /**
     * Delete by productId + invoice + date — used when inserting a normalised
     * primary-coded row to remove any existing alias-coded row for the same purchase.
     * productId is stable regardless of what productCode string was stored.
     */
    @Query("""DELETE FROM purchases
              WHERE productId     = :productId
                AND invoiceNumber = :invoice
                AND purchaseDate  = :date
                AND isDeleted = 0""")
    suspend fun deleteByProductIdInvoiceDate(productId: Long, invoice: String, date: String)

    /** All txnIds present in the local DB — used for down-sync deduplication. */
    @Query("SELECT txnId FROM purchases WHERE isDeleted = 0")
    suspend fun getAllTxnIds(): List<String>

    /**
     * Lightweight projection for import-sheet dedup.
     * Returns invoiceNumber, productCode and purchaseDate for all live rows.
     * Used to build the "invoiceNumber|productCode|date" key set that prevents
     * the same shipment row being imported twice.
     */
    @Query("SELECT invoiceNumber, productCode, purchaseDate FROM purchases WHERE isDeleted = 0")
    suspend fun getAllPurchasesForDedup(): List<PurchaseDedupRow>

    /** All non-deleted purchases in a date range — used by Monthly Summary. */
    @Query("SELECT * FROM purchases WHERE isDeleted = 0 AND purchaseDate BETWEEN :startDate AND :endDate ORDER BY purchaseDate ASC")
    suspend fun getPurchasesForDateRange(startDate: String, endDate: String): List<Purchase>
}

data class PurchaseDedupRow(
    val invoiceNumber: String,
    val productCode:   String,
    val purchaseDate:  String
)
