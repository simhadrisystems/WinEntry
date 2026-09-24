package com.simhadri.winentry.data.repository

import com.simhadri.winentry.data.AppDatabase
import com.simhadri.winentry.data.entity.DailyStock
import com.simhadri.winentry.data.entity.Product
import com.simhadri.winentry.data.entity.Purchase
import com.simhadri.winentry.data.entity.SyncStatus
import com.simhadri.winentry.data.entity.stockCode

/**
 * Keeps committed Daily Stock rows consistent after a purchase is added, edited, deleted
 * or moved to another received date. The entered CB stays; sale = OB + PQ - CB is
 * recomputed at the row's stored prices. A baseline row with no closing entered keeps
 * sale 0 and moves its CB instead, which cascades to the next day.
 */
class CommittedSaleRefresher(private val db: AppDatabase) {

    private val stockRepo = DailyStockRepository(db.productDao(), db.dailyStockDao())
    private val purchaseRepo = PurchaseRepository(db.purchaseDao())

    /** Returns the dates that now hold a negative sale. */
    suspend fun refresh(dates: Collection<String>): Set<String> {
        val todo = dates.filter { it.isNotBlank() }.toSortedSet()
        if (todo.isEmpty()) return emptySet()
        val products = db.productDao().getAllProductsSync()
        val negatives = mutableSetOf<String>()
        for (date in todo) {
            val rows = db.dailyStockDao().getAllDailyStockForDate(date).filter { it.isCommitted }
            if (rows.isEmpty()) continue
            val pq = pqByStockCode(date, products)
            val saleFixed = mutableListOf<DailyStock>()
            val cbMoved = mutableListOf<DailyStock>()
            for (r in rows) {
                val p = pq[r.productCode] ?: IntArray(4)
                val ob = BaselineMath.open(r)
                if (r.isOpeningStock && !BaselineMath.hasClosing(r)) {
                    val cb = IntArray(4) { ob[it] + p[it] }
                    if (!cb.contentEquals(BaselineMath.close(r)))
                        cbMoved += r.copy(closeQq = cb[0], closePp = cb[1], closeNn = cb[2], closeDd = cb[3])
                    continue
                }
                val cb = BaselineMath.close(r)
                val sale = IntArray(4) { ob[it] + p[it] - cb[it] }
                if (sale.any { it < 0 }) negatives += date
                if (!sale.contentEquals(BaselineMath.sale(r))) saleFixed += BaselineMath.withSale(r, sale)
            }
            if (saleFixed.isEmpty() && cbMoved.isEmpty()) continue
            db.dailyStockDao().upsertCommittedBatch(saleFixed + cbMoved)
            if (cbMoved.isNotEmpty()) negatives += stockRepo.cascadeRecalculate(cbMoved, products).negativeSaleDates
            refreshDayTotal(date)
        }
        return negatives
    }

    /** Re-snapshots the day total on an existing reconciliation row so reports match the stock. */
    private suspend fun refreshDayTotal(date: String) {
        val rDao = db.dayReconciliationDao()
        val rec = rDao.getByDate(date) ?: return
        val total = db.dailyStockDao().getAllDailyStockForDate(date).filter { it.isCommitted }.sumOf { it.saleAmount }
        if (total == rec.totalDaySales) return
        rDao.updateByDate(date, total, rec.upiReceipts, rec.dayExpenses,
            total - rec.upiReceipts - rec.dayExpenses, rec.deposits, rec.notes,
            SyncStatus.PENDING_UPSERT, System.currentTimeMillis())
    }

    private suspend fun pqByStockCode(date: String, products: List<Product>): Map<String, IntArray> {
        val bySize = purchaseRepo.getAllPurchaseQuantitiesByCodeForDate(date, products)
        val codes = products.map { it.stockCode }.toSet() +
            bySize.keys.map { it.dropLast(2) }
        return codes.associateWith { c ->
            val p = products.firstOrNull { it.stockCode == c }
            intArrayOf(
                bySize[p?.qqCode ?: "${c}QQ"] ?: 0, bySize[p?.ppCode ?: "${c}PP"] ?: 0,
                bySize[p?.nnCode ?: "${c}NN"] ?: 0, bySize[p?.ddCode ?: "${c}DD"] ?: 0)
        }
    }

    companion object {
        fun effectiveDate(p: Purchase) = p.receivedDate.ifBlank { p.purchaseDate }
    }
}
