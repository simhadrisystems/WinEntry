package com.simhadri.winentry.data.repository

import com.simhadri.winentry.data.AppDatabase
import com.simhadri.winentry.data.entity.Purchase
import com.simhadri.winentry.data.entity.SyncStatus
import com.simhadri.winentry.data.entity.stockCode
import com.simhadri.winentry.util.ProductCodeResolver

/**
 * Settings → Check Data Integrity. [check] only reads; [repair] fixes what is safe to fix
 * automatically and leaves the rest (opening mismatches, unknown codes, negative sales)
 * listed for the user, because only they know the right number.
 */
class IntegrityChecker(private val db: AppDatabase) {

    data class Report(
        val staleSaleDates: List<String>,
        val openingMismatches: List<String>,
        val blankTxnIds: List<Purchase>,
        val duplicateLines: List<List<Purchase>>,
        val relinkable: List<Pair<Purchase, Long>>,
        val orphanPurchases: List<String>,
        val orphanStockCodes: List<String>,
        val negativeSales: List<String>
    ) {
        val repairable: Int get() = staleSaleDates.size + blankTxnIds.size + duplicateLines.size + relinkable.size
        val isClean: Boolean get() = repairable == 0 && openingMismatches.isEmpty() &&
            orphanPurchases.isEmpty() && orphanStockCodes.isEmpty() && negativeSales.isEmpty()

        fun summary(): String = buildString {
            fun line(n: Int, text: String) { if (n > 0) append("• $n $text\n") }
            fun list(items: List<String>) {
                items.take(8).forEach { append("    $it\n") }
                if (items.size > 8) append("    … and ${items.size - 8} more\n")
            }
            if (isClean) { append("No problems found."); return@buildString }
            if (repairable > 0) append("Can be repaired:\n")
            line(staleSaleDates.size, "date(s) whose sale no longer matches the purchases")
            line(blankTxnIds.size, "purchase(s) with no cloud id")
            line(duplicateLines.size, "purchase line(s) saved more than once")
            line(relinkable.size, "purchase(s) linked to a missing product that can be re-linked")
            if (openingMismatches.isNotEmpty() || orphanPurchases.isNotEmpty() ||
                orphanStockCodes.isNotEmpty() || negativeSales.isNotEmpty()) append("\nCheck yourself:\n")
            if (openingMismatches.isNotEmpty()) {
                append("• ${openingMismatches.size} opening balance(s) differ from the previous closing\n")
                list(openingMismatches)
            }
            if (negativeSales.isNotEmpty()) {
                append("• ${negativeSales.size} negative sale(s)\n"); list(negativeSales)
            }
            if (orphanPurchases.isNotEmpty()) {
                append("• ${orphanPurchases.size} purchase(s) for unknown products\n"); list(orphanPurchases)
            }
            if (orphanStockCodes.isNotEmpty()) {
                append("• Stock rows for ${orphanStockCodes.size} unknown product code(s)\n"); list(orphanStockCodes)
            }
        }.trimEnd()
    }

    suspend fun check(): Report {
        val products = db.productDao().getAllProductsSync()
        val productIds = products.map { it.id }.toSet()
        val stockCodes = products.map { it.stockCode }.toSet()
        val purchases = db.purchaseDao().getActivePurchases()

        val blank = purchases.filter { it.txnId.isBlank() }
        val dupes = purchases.filter { it.invoiceNumber.isNotBlank() && it.productId > 0 }
            .groupBy { Triple(it.productId, it.invoiceNumber, it.purchaseDate) }
            .values.filter { it.size > 1 }

        val relink = mutableListOf<Pair<Purchase, Long>>()
        val orphans = mutableListOf<String>()
        for (p in purchases.filter { it.productId !in productIds }) {
            val match = ProductCodeResolver.resolve(p.productCode.trim(), products)
            if (match != null) relink += p to match.id
            else orphans += "${p.productCode} on ${p.purchaseDate} (invoice ${p.invoiceNumber.ifBlank { "-" }})"
        }

        val rows = db.dailyStockDao().getCommittedQtyRows()
        val baselines = db.dailyStockDao().getAllOpeningStockDates().sorted()
        val mismatches = mutableListOf<String>()
        val negatives = mutableListOf<String>()
        for ((code, list) in rows.groupBy { it.productCode }) {
            var prev: com.simhadri.winentry.data.dao.StockQtyRow? = null
            for (r in list) {
                if (r.saleQq < 0 || r.salePp < 0 || r.saleNn < 0 || r.saleDd < 0) negatives += "$code on ${r.date}"
                if (!r.isOpeningStock) {
                    // Same floor as getLastCommittedBeforeDate: nothing carries past a baseline
                    val floor = baselines.lastOrNull { it <= r.date } ?: ""
                    val p = prev?.takeIf { it.date >= floor }
                    val expected = intArrayOf(p?.closeQq ?: 0, p?.closePp ?: 0, p?.closeNn ?: 0, p?.closeDd ?: 0)
                    val actual = intArrayOf(r.openQq, r.openPp, r.openNn, r.openDd)
                    if (!expected.contentEquals(actual)) mismatches += "$code on ${r.date}"
                }
                prev = r
            }
        }
        val orphanStock = rows.map { it.productCode }.distinct().filter { it !in stockCodes }

        val stale = CommittedSaleRefresher(db).findStale(db.dailyStockDao().getCommittedDates())

        return Report(stale.sorted(), mismatches, blank, dupes, relink, orphans, orphanStock, negatives)
    }

    /** Applies the automatic repairs, then returns a fresh report. */
    suspend fun repair(report: Report): Report {
        val pDao = db.purchaseDao()
        val touched = mutableSetOf<String>()
        val now = System.currentTimeMillis()
        for (p in report.blankTxnIds) {
            val cur = pDao.getPurchaseById(p.id) ?: continue
            if (cur.txnId.isBlank()) pDao.update(cur.copy(txnId = Purchase.generateTxnId(),
                syncStatus = SyncStatus.PENDING_INSERT, updatedAt = now))
        }
        for ((p, productId) in report.relinkable) {
            val cur = pDao.getPurchaseById(p.id) ?: continue
            pDao.update(cur.copy(productId = productId, syncStatus = SyncStatus.PENDING_UPDATE, updatedAt = now))
            touched += CommittedSaleRefresher.effectiveDate(cur)
        }
        for (group in report.duplicateLines) {
            val current = group.mapNotNull { pDao.getPurchaseById(it.id) }.filter { !it.isDeleted }
            val newest = current.maxByOrNull { it.updatedAt } ?: continue
            current.forEach { touched += CommittedSaleRefresher.effectiveDate(it) }
            pDao.replaceLine(newest.copy(txnId = "", syncStatus = SyncStatus.PENDING_UPDATE))
        }
        CommittedSaleRefresher(db).refresh(report.staleSaleDates + touched)
        return check()
    }
}
