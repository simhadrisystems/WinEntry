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
 *
 * Only the products whose purchases changed are touched. Other rows on the same date may
 * disagree with today's purchase records for historical reasons (older app logic, moved
 * dates); rewriting them automatically would silently change sales the user checked.
 */
class CommittedSaleRefresher(private val db: AppDatabase) {

    private val stockRepo = DailyStockRepository(db.productDao(), db.dailyStockDao())
    private val purchaseRepo = PurchaseRepository(db.purchaseDao())

    /** Recomputes the rows of the products in [purchases] on their effective dates. Returns dates now holding a negative sale. */
    suspend fun refreshFor(purchases: Collection<Purchase>): Set<String> {
        if (purchases.isEmpty()) return emptySet()
        val products = db.productDao().getAllProductsSync()
        val byId = products.associateBy { it.id }
        val keys = purchases.map { p ->
            val code = byId[p.productId]?.stockCode
                ?: com.simhadri.winentry.util.ProductCodeResolver.resolve(p.productCode.trim(), products)?.stockCode
                ?: p.productCode.trim()
            effectiveDate(p) to code
        }
        return run(keys.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }, dryRun = false, products).negatives
    }

    /** "CODE on date" for committed rows that disagree with their purchases; nothing is written. */
    suspend fun findStale(dates: Collection<String>): List<String> =
        run(dates.associateWith { null }, dryRun = true, db.productDao().getAllProductsSync()).stale

    private class Outcome(val stale: List<String>, val negatives: Set<String>)

    /** [scope]: date to the product codes to check there (null = every product on that date). */
    private suspend fun run(scope: Map<String, Set<String>?>, dryRun: Boolean, products: List<Product>): Outcome {
        val todo = scope.keys.filter { it.isNotBlank() }.toSortedSet()
        val negatives = mutableSetOf<String>()
        val stale = mutableListOf<String>()
        for (date in todo) {
            val codes = scope[date]
            val rows = db.dailyStockDao().getAllDailyStockForDate(date)
                .filter { it.isCommitted && (codes == null || it.productCode in codes) }
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
            (saleFixed + cbMoved).forEach { stale += "${it.productCode} on $date" }
            if (dryRun) continue
            db.dailyStockDao().upsertCommittedBatch(saleFixed + cbMoved)
            if (cbMoved.isNotEmpty()) negatives += stockRepo.cascadeRecalculate(cbMoved, products).negativeSaleDates
            refreshDayTotal(date)
        }
        return Outcome(stale, negatives)
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
