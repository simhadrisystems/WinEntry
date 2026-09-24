package com.simhadri.winentry.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.simhadri.winentry.data.entity.Purchase
import com.simhadri.winentry.data.entity.SyncStatus

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

    @Query("SELECT COUNT(*) FROM purchases")
    suspend fun getCount(): Int

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

    // Used by daily stock — matches by effective received date
    @Query("""
        SELECT * FROM purchases
        WHERE COALESCE(NULLIF(receivedDate,''), purchaseDate) = :date
          AND isDeleted = 0
        ORDER BY productId ASC, id ASC
    """)
    suspend fun getPurchasesByEffectiveDateSync(date: String): List<Purchase>

    @Query("""
        SELECT * FROM purchases
        WHERE COALESCE(NULLIF(receivedDate,''), purchaseDate) = :date
          AND productId = :productId
          AND isDeleted = 0
        ORDER BY id ASC
    """)
    suspend fun getPurchasesByEffectiveDateAndProduct(date: String, productId: Long): List<Purchase>

    @Query("""
        UPDATE purchases SET isProcessed = 1
        WHERE COALESCE(NULLIF(receivedDate,''), purchaseDate) = :date
          AND productId = :productId
    """)
    suspend fun markPurchasesAsProcessedByEffectiveDate(date: String, productId: Long)

    @Query("SELECT * FROM purchases WHERE invoiceNumber = :invoiceNumber AND purchaseDate = :date AND isDeleted = 0")
    suspend fun getPurchasesByInvoiceAndDate(invoiceNumber: String, date: String): List<Purchase>

    @Query("""
        UPDATE purchases
        SET receivedDate = :receivedDate,
            syncStatus   = 'PENDING_UPDATE',
            updatedAt    = :now
        WHERE invoiceNumber = :invoiceNumber
          AND purchaseDate  = :purchaseDate
          AND isDeleted     = 0
    """)
    suspend fun updateReceivedDateForInvoice(
        invoiceNumber: String,
        purchaseDate:  String,
        receivedDate:  String,
        now:           Long = System.currentTimeMillis()
    )

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

    /** Marks only rows that still match what was sent; a row edited during the upload stays pending. */
    @Transaction
    suspend fun markSyncedIfUnchanged(sent: List<Purchase>) {
        for (s in sent) {
            val cur = getPurchaseById(s.id) ?: continue
            if (cur.copy(syncStatus = s.syncStatus) == s) markAsSynced(s.id)
        }
    }

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

    /** Restores a cloud ReceivedDate onto a local row that has no unsynced edits. */
    @Query("""
        UPDATE purchases SET receivedDate = :receivedDate
        WHERE txnId = :txnId AND syncStatus = 'SYNCED' AND isDeleted = 0
          AND receivedDate != :receivedDate
    """)
    suspend fun repairReceivedDate(txnId: String, receivedDate: String): Int

    /**
     * Hard-delete any soft-deleted (PENDING_DELETE) tombstone that shares the given txnId.
     * Called when restoring a purchase from cloud: if a matching tombstone exists it would
     * cause the next sync to delete the just-restored cloud row, so we cancel it here.
     */
    @Query("DELETE FROM purchases WHERE txnId = :txnId AND isDeleted = 1")
    suspend fun cancelPendingDeleteByTxnId(txnId: String)

    @Query("""SELECT * FROM purchases
              WHERE productId = :productId AND invoiceNumber = :invoice
                AND purchaseDate = :date AND isDeleted = 0
              ORDER BY txnId DESC""")
    suspend fun getActiveLines(productId: Long, invoice: String, date: String): List<Purchase>

    @Query("""SELECT * FROM purchases
              WHERE productCode = :productCode AND invoiceNumber = :invoice
                AND purchaseDate = :date AND isDeleted = 0
              ORDER BY txnId DESC""")
    suspend fun getActiveLinesByCode(productCode: String, invoice: String, date: String): List<Purchase>

    /**
     * Saves [purchase] as the only active line for its product + invoice + date.
     *
     * The line keeps [purchase].txnId if set, otherwise the txnId of the line it replaces,
     * so the cloud upsert (keyed by TxnId) updates that row instead of appending a copy.
     * Other copies that already reached the cloud are tombstoned so the next sync deletes
     * them there too. Hard-deleting them here is what used to leave duplicates in the sheet.
     */
    @Transaction
    suspend fun replaceLine(purchase: Purchase): Long {
        val existing = if (purchase.productId > 0)
            getActiveLines(purchase.productId, purchase.invoiceNumber, purchase.purchaseDate)
        else
            getActiveLinesByCode(purchase.productCode, purchase.invoiceNumber, purchase.purchaseDate)

        val reusedTxnId = existing.firstOrNull { it.txnId.isNotBlank() }?.txnId
        val txnId = purchase.txnId.ifBlank { reusedTxnId ?: Purchase.generateTxnId() }
        val status = when {
            purchase.syncStatus == SyncStatus.SYNCED -> SyncStatus.SYNCED
            existing.any { it.txnId == txnId }      -> SyncStatus.PENDING_UPDATE
            else                                    -> SyncStatus.PENDING_INSERT
        }

        for (old in existing) {
            val neverSynced = old.txnId.isBlank() || old.syncStatus == SyncStatus.PENDING_INSERT
            if (old.txnId == txnId || neverSynced) delete(old)
            else update(old.copy(
                isDeleted  = true,
                syncStatus = SyncStatus.PENDING_DELETE,
                updatedAt  = System.currentTimeMillis()
            ))
        }
        cancelPendingDeleteByTxnId(txnId)
        return insert(purchase.copy(
            id         = 0,
            txnId      = txnId,
            syncStatus = status,
            isDeleted  = false,
            updatedAt  = System.currentTimeMillis()
        ))
    }


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
