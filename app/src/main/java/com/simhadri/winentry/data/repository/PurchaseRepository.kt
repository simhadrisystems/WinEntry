package com.simhadri.winentry.data.repository

import androidx.lifecycle.LiveData
import com.simhadri.winentry.data.dao.PurchaseDao
import com.simhadri.winentry.data.entity.Purchase
import com.simhadri.winentry.data.entity.SyncStatus

class PurchaseRepository(private val purchaseDao: PurchaseDao) {

    val allPurchases: LiveData<List<Purchase>> = purchaseDao.getAllPurchases()

    // ── Write ─────────────────────────────────────────────────────────────────

    suspend fun insert(purchase: Purchase): Long {
        val toInsert = purchase.copy(
            txnId      = Purchase.generateTxnId(),
            syncStatus = SyncStatus.PENDING_INSERT,
            isDeleted  = false
        )
        return purchaseDao.insert(toInsert)
    }

    suspend fun update(purchase: Purchase) {
        purchaseDao.update(
            purchase.copy(
                syncStatus = SyncStatus.PENDING_UPDATE,
                updatedAt  = System.currentTimeMillis()
            )
        )
    }

    /** Delete alias-coded row before inserting normalised primary-coded row. */
    suspend fun deleteByProductIdInvoiceDate(productId: Long, invoice: String, date: String) =
        purchaseDao.deleteByProductIdInvoiceDate(productId, invoice, date)

    /** Soft-delete: marks row as deleted + PENDING_DELETE for sync. */
    suspend fun delete(purchase: Purchase) {
        purchaseDao.update(
            purchase.copy(
                isDeleted  = true,
                syncStatus = SyncStatus.PENDING_DELETE,
                updatedAt  = System.currentTimeMillis()
            )
        )
    }

    /** Hard-delete after cloud confirms row removal. */
    suspend fun hardDeleteAfterSync(id: Long) =
        purchaseDao.hardDeleteAfterSync(id)

    suspend fun deleteAll() = purchaseDao.deleteAll()

    // ── Read ──────────────────────────────────────────────────────────────────

    fun getPurchasesByDate(date: String): LiveData<List<Purchase>> =
        purchaseDao.getPurchasesByDate(date)

    fun getPurchasesByProduct(productId: Long): LiveData<List<Purchase>> =
        purchaseDao.getPurchasesByProduct(productId)

    fun getPurchasesByDateRange(startDate: String, endDate: String): LiveData<List<Purchase>> =
        purchaseDao.getPurchasesByDateRange(startDate, endDate)

    suspend fun getPurchaseById(id: Long): Purchase? =
        purchaseDao.getPurchaseById(id)

    suspend fun getTotalCostByDateRange(startDate: String, endDate: String): Double =
        purchaseDao.getTotalCostByDateRange(startDate, endDate) ?: 0.0

    suspend fun getTotalUnitsPurchased(productId: Long, startDate: String, endDate: String): Int =
        purchaseDao.getTotalUnitsPurchased(productId, startDate, endDate) ?: 0

    suspend fun getPurchasesByInvoiceAndDate(invoiceNumber: String, date: String): List<Purchase> =
        purchaseDao.getPurchasesByInvoiceAndDate(invoiceNumber, date)

    // ── DailyStock integration ────────────────────────────────────────────────

    suspend fun getPurchaseQuantitiesForDateAndProduct(
        date: String,
        productId: Long
    ): PurchaseQuantities =
        purchaseDao.getPurchasesByDateAndProduct(date, productId).toQuantities()

    suspend fun getAllPurchaseQuantitiesForDate(date: String): Map<Long, PurchaseQuantities> =
        purchaseDao.getPurchasesByDateSync(date)
            .groupBy { it.productId }
            .mapValues { (_, list) -> list.toQuantities() }

    /**
     * Same as above but keyed by productCode (e.g. "W1039QQ") instead of productId.
     * productCode is the stable identifier shared between the purchases table and
     * daily_stock — it does not change across DB resets or re-imports.
     * Used in buildEntry as the primary lookup to avoid productId mismatch bugs.
     *
     * A Purchase stores one productCode for the product brand (e.g. "W1039") but
     * the daily_stock uses size-specific codes (e.g. "W1039QQ", "W1039PP" etc.).
     * We derive the four size codes from the brand productCode and the size totals.
     */
    /**
     * Fetch purchase quantities for [date], keyed by primary size code (e.g. "W1182NN").
     *
     * [products] is the full active product list, used to resolve alias codes to
     * primary codes at read time. This handles purchases that were saved before
     * the write-time normalisation fix (i.e. already in the DB with alias codes).
     *
     * Resolution: for each purchase row, find its canonical product via
     * ProductCodeResolver — if found, use "${primaryCode}QQ" etc. as the key;
     * if not found, fall back to the raw code (safe for manually-entered rows
     * that are already correct).
     */
    suspend fun getAllPurchaseQuantitiesByCodeForDate(
        date:     String,
        products: List<com.simhadri.winentry.data.entity.Product>
    ): Map<String, Int> {
        val purchases = purchaseDao.getPurchasesByDateSync(date)
        val result    = mutableMapOf<String, Int>()
        for (p in purchases) {
            // Resolve alias → primary code
            val canonical = com.simhadri.winentry.util.ProductCodeResolver
                .resolve(p.productCode.trim(), products)
            val base = if (canonical != null) {
                com.simhadri.winentry.util.ProductCodeResolver.primaryCode(canonical)
            } else {
                p.productCode.trim()   // already primary or unrecognised — use as-is
            }
            result[base + "QQ"] = (result[base + "QQ"] ?: 0) + p.qqTotalUnits
            result[base + "PP"] = (result[base + "PP"] ?: 0) + p.ppTotalUnits
            result[base + "NN"] = (result[base + "NN"] ?: 0) + p.nnTotalUnits
            result[base + "DD"] = (result[base + "DD"] ?: 0) + p.ddTotalUnits
            if (canonical == null) {
                android.util.Log.w("PurchaseQty",
                    "date=$date unresolved code=${p.productCode} — using as-is")
            }
        }
        return result
    }

    // ── Sync ──────────────────────────────────────────────────────────────────

    suspend fun getPendingSyncPurchases(): List<Purchase> =
        purchaseDao.getPendingSyncPurchases()

    suspend fun markAsSynced(id: Long) = purchaseDao.markAsSynced(id)
    suspend fun markSyncError(id: Long) = purchaseDao.markSyncError(id)
}

// ── Supporting data classes ───────────────────────────────────────────────────

private fun List<Purchase>.toQuantities() = PurchaseQuantities(
    qqBoxes      = sumOf { it.qqBoxes },
    qqLoose      = sumOf { it.qqLoose },
    qqTotalUnits = sumOf { it.qqTotalUnits },
    ppBoxes      = sumOf { it.ppBoxes },
    ppLoose      = sumOf { it.ppLoose },
    ppTotalUnits = sumOf { it.ppTotalUnits },
    nnBoxes      = sumOf { it.nnBoxes },
    nnLoose      = sumOf { it.nnLoose },
    nnTotalUnits = sumOf { it.nnTotalUnits },
    ddBoxes      = sumOf { it.ddBoxes },
    ddLoose      = sumOf { it.ddLoose },
    ddTotalUnits = sumOf { it.ddTotalUnits }
)

data class PurchaseQuantities(
    val qqBoxes: Int = 0, val qqLoose: Int = 0, val qqTotalUnits: Int = 0,
    val ppBoxes: Int = 0, val ppLoose: Int = 0, val ppTotalUnits: Int = 0,
    val nnBoxes: Int = 0, val nnLoose: Int = 0, val nnTotalUnits: Int = 0,
    val ddBoxes: Int = 0, val ddLoose: Int = 0, val ddTotalUnits: Int = 0
)
