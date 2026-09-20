package com.simhadri.winentry.data.repository

import androidx.lifecycle.LiveData
import com.simhadri.winentry.data.dao.DailyStockDao
import com.simhadri.winentry.data.dao.ProductDao
import com.simhadri.winentry.data.entity.DailyStock
import com.simhadri.winentry.data.entity.Product
import com.simhadri.winentry.data.entity.SyncStatus
import com.simhadri.winentry.data.entity.stockCode

class DailyStockRepository(
    private val productDao:     ProductDao,
    private val dailyStockDao:  DailyStockDao
) {

    // ── Products ──────────────────────────────────────────────────────────────

    fun getActiveProducts(): LiveData<List<Product>> =
        productDao.getActiveProductsByDailySortKey()

    suspend fun getActiveProductsSortedSync(): List<Product> =
        productDao.getActiveProductsByDailySortKeySync()

    fun getActiveProductsBySortKey(sortKey: String): LiveData<List<Product>> =
        productDao.getActiveProductsBySortKey(sortKey)

    suspend fun getAllProductsSync(): List<Product> =
        productDao.getAllProductsSync()

    suspend fun getAllProductsByDailySortKeySync(): List<Product> =
        productDao.getAllProductsByDailySortKeySync()

    // ── Opening balance ───────────────────────────────────────────────────────

    /**
     * Previous-day closing for one product.
     * Returns a DailyStock row (all 4 sizes) or null if no history.
     */
    suspend fun getPreviousRow(productCode: String, date: String): DailyStock? =
        dailyStockDao.getLastCommittedBeforeDate(productCode, date)

    /**
     * Bulk previous-day closings — one DB round-trip for all products.
     * Returns map of productCode → DailyStock (previous committed row).
     */
    suspend fun getBulkPreviousRows(
        productCodes: List<String>,
        date:         String
    ): Map<String, DailyStock> {
        if (productCodes.isEmpty()) return emptyMap()
        return dailyStockDao.getBulkLastCommittedBeforeDate(productCodes, date)
            .associateBy { it.productCode }
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    suspend fun getDailyStockRaw(date: String, productCode: String): DailyStock? =
        dailyStockDao.getDailyStock(date, productCode)

    suspend fun getAllDailyStockForDate(date: String): List<DailyStock> =
        dailyStockDao.getAllDailyStockForDate(date)

    suspend fun getAllDailyStockInRange(startDate: String, endDate: String): List<DailyStock> =
        dailyStockDao.getAllDailyStockForDateRange(startDate, endDate)

    suspend fun getCommittedDates(): List<String> =
        dailyStockDao.getCommittedDates()

    suspend fun getEarliestCommittedDate(): String? =
        dailyStockDao.getEarliestCommittedDate()

    suspend fun getLatestCommittedDate(): String? =
        dailyStockDao.getLatestCommittedDate()

    suspend fun getLatestOpeningStockDate(): String? =
        dailyStockDao.getLatestOpeningStockDate()

    /**
     * Active opening stock date, with a one-time self-heal for legacy data that
     * predates the isOpeningStock column (e.g. restored from an old cloud sheet).
     * If recovered via the heuristic fallback, the flag is persisted so future
     * calls hit the fast path and the correction syncs back to the cloud.
     */
    suspend fun getLatestOpeningStockDateOrHeal(): String? {
        dailyStockDao.getLatestOpeningStockDate()?.let { return it }
        val likely = dailyStockDao.findLikelyOpeningStockDate() ?: return null
        dailyStockDao.backfillOpeningStockFlagForDate(likely)
        return likely
    }

    suspend fun getOpeningStockDateCounts() =
        dailyStockDao.getOpeningStockDateCounts()

    /**
     * Total committed sale amount for [date].
     * Used by DayReconciliationRepository.
     */
    suspend fun getTotalDaySales(date: String): Double =
        dailyStockDao.getAllDailyStockForDate(date)
            .filter { it.isCommitted }
            .sumOf { it.saleAmount }

    // ── Write — draft auto-save ───────────────────────────────────────────────

    /**
     * Auto-save a draft row when a purchase exists but no stock entry yet.
     * Closing defaults to opening + purchase (no sale yet).
     */
    suspend fun saveDraftIfAbsent(
        date:        String,
        productCode: String,
        openQq: Int, openPp: Int, openNn: Int, openDd: Int,
        purchQq: Int, purchPp: Int, purchNn: Int, purchDd: Int
    ) {
        // Default closing = open + purchase (user will adjust)
        dailyStockDao.insertDraftIfAbsent(
            DailyStock(
                date        = date,
                productCode = productCode,
                openQq  = openQq,  openPp  = openPp,  openNn  = openNn,  openDd  = openDd,
                closeQq = openQq + purchQq, closePp = openPp + purchPp,
                closeNn = openNn + purchNn, closeDd = openDd + purchDd,
                isCommitted = false,
                syncStatus  = SyncStatus.SYNCED
            )
        )
    }

    // ── Write — explicit user commit ──────────────────────────────────────────

    suspend fun saveAllEntries(rows: List<DailyStock>) =
        dailyStockDao.upsertCommittedBatch(rows)

    suspend fun saveDailyStocks(rows: List<DailyStock>) =
        dailyStockDao.upsertCommittedBatch(rows)

    // ── Cascade recalculation ─────────────────────────────────────────────────

    /**
     * Pre-save integrity check: would saving [rows] create a negative sale
     * on the next committed day for any product?
     *
     * Called BEFORE writing to DB. Returns a list of violation descriptions
     * so the user can fix the next day first. Empty list = safe to save.
     *
     * Negative sale occurs when:
     *   next.closeQq > new OB (= rows[product].closeQq)
     * i.e. the next day's user-committed CB exceeds the new opening balance.
     */
    /**
     * Pre-save integrity check: would saving [rows] create or worsen a negative
     * sale on the next committed day?
     *
     * Block when: newSale < 0 AND newSale < storedSale
     *   → either creating a fresh negative, or making existing negative worse
     *
     * Allow when: newSale >= 0 (no problem)
     *   OR newSale < 0 but newSale >= storedSale (existing negative, not worsened)
     *
     * Example — today's CB = tomorrow's OB:
     *   11th CB: 10→20, 12th CB=15, stored saleQq=0
     *   newSale = 20-15 = +5 → ≥0 → ALLOW
     *
     *   11th CB: 10→5,  12th CB=15, stored saleQq=0
     *   newSale = 5-15 = -10 → <0 AND <0 → BLOCK
     *
     *   12th already -5 (stored), 11th CB 10→8
     *   newSale = 8-15 = -7 → <0 AND < -5 → BLOCK (worsens)
     *
     *   12th already -5 (stored), 11th CB 10→12
     *   newSale = 12-15 = -3 → <0 BUT -3 > -5 → ALLOW (improves)
     */
    suspend fun checkWouldCreateNegative(rows: List<DailyStock>): List<String> {
        val violations = mutableListOf<String>()
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val disp = java.text.SimpleDateFormat("dd/MM/yy", java.util.Locale.US)
        val cal = java.util.Calendar.getInstance()

        for (row in rows) {
            val next = dailyStockDao.getSubsequentCommittedRows(row.productCode, row.date)
                .firstOrNull() ?: continue

            cal.time = sdf.parse(row.date)!!
            cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
            if (next.date != sdf.format(cal.time)) continue

            // newSale = today's new CB (= tomorrow's new OB) minus tomorrow's committed CB
            val newSaleQq = row.closeQq - next.closeQq
            val newSalePp = row.closePp - next.closePp
            val newSaleNn = row.closeNn - next.closeNn
            val newSaleDd = row.closeDd - next.closeDd

            // Only check sizes where today's CB actually changed from what was stored.
            // next.openQq == current stored CB for today (set by previous cascade/save).
            // If CB unchanged for a size, that size was not edited — don't penalise it.
            val worsened = listOf(
                Triple("QQ", newSaleQq, next.saleQq) to (row.closeQq != next.openQq),
                Triple("PP", newSalePp, next.salePp) to (row.closePp != next.openPp),
                Triple("NN", newSaleNn, next.saleNn) to (row.closeNn != next.openNn),
                Triple("DD", newSaleDd, next.saleDd) to (row.closeDd != next.openDd)
            ).filter { (triple, changed) ->
                val (_, newSale, storedSale) = triple
                changed && newSale < 0 && newSale < storedSale
            }.map { (triple, _) ->
                val (size, newSale, _) = triple
                size to newSale
            }

            if (worsened.isNotEmpty()) {
                val nextDateDisp = try { disp.format(sdf.parse(next.date)!!) } catch (_: Exception) { next.date }
                val sizes = worsened.joinToString(", ") { (size, sale) -> "$size($sale)" }
                violations.add("${next.date}|$nextDateDisp|$sizes")
            }
        }
        return violations
    }

    /**
     * Preview: how many consecutive committed rows after [savedRows] exist.
     * Consecutive = no gap in dates (each day follows the previous).
     */
    suspend fun previewCascade(savedRows: List<DailyStock>): CascadePreview {
        val affectedDates = mutableSetOf<String>()
        val affectedRows  = mutableListOf<DailyStock>()
        for (row in savedRows) {
            val subsequent = getConsecutiveCommittedRows(row.productCode, row.date)
            affectedRows.addAll(subsequent)
            subsequent.mapTo(affectedDates) { it.date }
        }
        val unique = affectedRows.distinctBy { "${it.date}|${it.productCode}" }
        return CascadePreview(
            affectedRowCount  = unique.size,
            affectedDateCount = affectedDates.size,
            earliestDate      = affectedDates.minOrNull() ?: "",
            latestDate        = affectedDates.maxOrNull() ?: ""
        )
    }

    /**
     * Cascade recalculate: walk forward from [savedRows] updating opening snapshots
     * and sale figures for all consecutive committed days.
     *
     * Two key behaviours:
     *
     * 1. SKIP UNCHANGED — only writes to DB (and updates lastModified) when the
     *    computed values actually differ from what is stored. This prevents the
     *    Monthly Summary stale-reconciliation flag from firing on days where
     *    the cascade had no practical effect.
     *
     * 2. NEGATIVE SALE DETECTION — when a subsequent day's closing exceeds its
     *    new opening (CB > OB, impossible physically), sale is stored as the raw
     *    negative value rather than being silently clamped to 0. The negative is
     *    returned in [CascadeResult.negativeSaleDates] so the ViewModel can warn
     *    the user that those days need manual correction.
     */
    suspend fun cascadeRecalculate(
        savedRows: List<DailyStock>,
        products:  List<Product>
    ): CascadeResult {
        val productMap: Map<String, Product> = products.associateBy { it.stockCode }
        var totalUpdated    = 0
        val now             = System.currentTimeMillis()
        val processed       = mutableSetOf<String>()
        val negativeSaleDates = mutableSetOf<String>()

        // Collect fully corrected rows to upsert in one batch.
        // upsertCommittedBatch sets syncStatus = PENDING_UPSERT so they
        // are pushed to cloud on the next sync automatically.
        val rowsToUpsert = mutableListOf<DailyStock>()

        for (savedRow in savedRows) {
            if (!processed.add(savedRow.productCode)) continue
            val subsequent = getConsecutiveCommittedRows(savedRow.productCode, savedRow.date)
            if (subsequent.isEmpty()) continue

            val product = productMap[savedRow.productCode] ?: continue
            var prevClose = intArrayOf(savedRow.closeQq, savedRow.closePp,
                                       savedRow.closeNn, savedRow.closeDd)

            for (next in subsequent) {
                val newOpenQq = prevClose[0]; val newOpenPp = prevClose[1]
                val newOpenNn = prevClose[2]; val newOpenDd = prevClose[3]

                val rawQq = newOpenQq - next.closeQq
                val rawPp = newOpenPp - next.closePp
                val rawNn = newOpenNn - next.closeNn
                val rawDd = newOpenDd - next.closeDd

                val hasNegative = rawQq < 0 || rawPp < 0 || rawNn < 0 || rawDd < 0
                if (hasNegative) negativeSaleDates.add(next.date)

                val saleQq = rawQq; val salePp = rawPp
                val saleNn = rawNn; val saleDd = rawDd
                val amtQq  = saleQq * product.qqSalePrice
                val amtPp  = salePp * product.ppSalePrice
                val amtNn  = saleNn * product.nnSalePrice
                val amtDd  = saleDd * product.ddSalePrice
                val newSaleAmount = amtQq + amtPp + amtNn + amtDd

                val unchanged = newOpenQq == next.openQq && newOpenPp == next.openPp &&
                                newOpenNn == next.openNn && newOpenDd == next.openDd &&
                                saleQq == next.saleQq && salePp == next.salePp &&
                                saleNn == next.saleNn && saleDd == next.saleDd

                if (!unchanged) {
                    // Build complete corrected row — CB is preserved (user data),
                    // OB and sale are recalculated. syncStatus = PENDING_UPSERT
                    // via upsertCommittedBatch so cloud gets updated on next sync.
                    rowsToUpsert.add(next.copy(
                        openQq   = newOpenQq, openPp   = newOpenPp,
                        openNn   = newOpenNn, openDd   = newOpenDd,
                        saleQq   = saleQq,    salePp   = salePp,
                        saleNn   = saleNn,    saleDd   = saleDd,
                        amountQq = amtQq,     amountPp = amtPp,
                        amountNn = amtNn,     amountDd = amtDd,
                        saleAmount   = newSaleAmount,
                        lastModified = now
                    ))
                    totalUpdated++
                }

                prevClose = intArrayOf(next.closeQq, next.closePp, next.closeNn, next.closeDd)
            }
        }

        // Single batch upsert — sets PENDING_UPSERT on all cascade-updated rows
        if (rowsToUpsert.isNotEmpty()) {
            dailyStockDao.upsertCommittedBatch(rowsToUpsert)
        }

        return CascadeResult(totalUpdated, negativeSaleDates)
    }

    /**
     * Returns only the IMMEDIATE next committed day after [afterDate].
     *
     * Rationale: a change to day N's closing balance only directly affects day N+1's
     * opening balance. Day N+1's closing was entered by the user knowing its own OB
     * at that time — cascading further without user action is incorrect.
     *
     * If the user re-saves day N+1 after seeing the updated OB, the save of N+1
     * will in turn trigger its own cascade prompt for day N+2, and so on.
     * This makes the cascade explicit and user-controlled at each step.
     */
    private suspend fun getConsecutiveCommittedRows(
        productCode: String,
        afterDate:   String
    ): List<DailyStock> {
        val all = dailyStockDao.getSubsequentCommittedRows(productCode, afterDate)
        if (all.isEmpty()) return emptyList()
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val cal = java.util.Calendar.getInstance()
        cal.time = sdf.parse(afterDate)!!
        cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
        val nextDate = sdf.format(cal.time)
        // Only return the immediate next day if it exists and is committed
        val next = all.firstOrNull { it.date == nextDate }
        return if (next != null) listOf(next) else emptyList()
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    suspend fun deleteDailyStock(date: String, productCode: String) =
        dailyStockDao.deleteDailyStock(date, productCode)

    suspend fun hasNonZeroClosingBalance(productCode: String): Boolean =
        dailyStockDao.hasNonZeroClosingBalance(productCode) > 0

    /**
     * Delete today's committed entry for [productCode] on [date], then cascade-update
     * the next committed day's opening balance so it reflects the new effective CB
     * (= previous committed row's CB, or zero if no prior history).
     *
     * Clearing can never create negative sales on the next day — the new OB will be
     * >= the old OB, so the next day's sale can only stay the same or increase.
     */
    suspend fun clearEntryWithCascade(date: String, productCode: String, products: List<Product>) {
        val prevRow = dailyStockDao.getLastCommittedBeforeDate(productCode, date)
        dailyStockDao.deleteDailyStock(date, productCode)

        // Virtual "from" row: today's new effective CB = previous committed CB (or zeros)
        val virtualRow = DailyStock(
            date        = date,
            productCode = productCode,
            openQq  = 0, openPp  = 0, openNn  = 0, openDd  = 0,
            closeQq = prevRow?.closeQq ?: 0,
            closePp = prevRow?.closePp ?: 0,
            closeNn = prevRow?.closeNn ?: 0,
            closeDd = prevRow?.closeDd ?: 0,
            isCommitted = true
        )
        cascadeRecalculate(listOf(virtualRow), products)
    }

    suspend fun clearDateData(date: String) =
        dailyStockDao.deleteAllForDate(date)

    /**
     * Delete the active opening stock date, then cascade-correct the next committed
     * day's opening balance for every product that was on [date] — mirrors
     * [clearEntryWithCascade]'s per-product pattern, batched over the whole date.
     */
    suspend fun clearOpeningStockWithCascade(date: String, products: List<Product>) {
        val rowsToday = dailyStockDao.getAllDailyStockForDate(date).filter { it.isCommitted }
        dailyStockDao.deleteAllForDate(date)
        val virtualRows = rowsToday.map { row ->
            val prevRow = dailyStockDao.getLastCommittedBeforeDate(row.productCode, date)
            DailyStock(
                date        = date,
                productCode = row.productCode,
                closeQq = prevRow?.closeQq ?: 0, closePp = prevRow?.closePp ?: 0,
                closeNn = prevRow?.closeNn ?: 0, closeDd = prevRow?.closeDd ?: 0,
                isCommitted = true
            )
        }
        cascadeRecalculate(virtualRows, products)
    }

    suspend fun clearAllData() =
        dailyStockDao.deleteAll()

    // ── Sync ──────────────────────────────────────────────────────────────────

    suspend fun getPendingSyncStock(): List<DailyStock> =
        dailyStockDao.getPendingSyncStock()

    /** Returns sync statuses for opening-stock rows on [date] (used for the cloud badge). */
    suspend fun getOpeningStockSyncStatuses(date: String): List<String> =
        dailyStockDao.getOpeningStockSyncStatuses(date)

    suspend fun markStockAsSynced(date: String, productCode: String) =
        dailyStockDao.markStockAsSynced(date, productCode)

    suspend fun markStockSyncError(date: String, productCode: String) =
        dailyStockDao.markStockSyncError(date, productCode)

    suspend fun forceFullResync() =
        dailyStockDao.markAllCommittedAsPending()

    // ── Sort keys ─────────────────────────────────────────────────────────────

    suspend fun updateSortKeys(products: List<Product>) =
        productDao.updateProducts(products)

    suspend fun getInactiveProductsSync(): List<Product> =
        productDao.getInactiveProductsSync()

    suspend fun deactivateProduct(product: Product) =
        productDao.updateProduct(product.copy(isActive = false))

    suspend fun activateProduct(product: Product) =
        productDao.updateProduct(product.copy(isActive = true))
}

// ── Supporting data classes ───────────────────────────────────────────────────

data class CascadePreview(
    val affectedRowCount:  Int,
    val affectedDateCount: Int,
    val earliestDate:      String,
    val latestDate:        String
) {
    val isNeeded: Boolean get() = affectedRowCount > 0

    val dialogMessage: String get() = when {
        !isNeeded -> ""
        else ->
            "Changing this closing balance will update the opening balance for the " +
            "next committed day ($earliestDate) and recalculate its sale figures. " +
            "If you then re-save $earliestDate, its next day will be updated in turn."
    }
}

/**
 * Result of [DailyStockRepository.cascadeRecalculate].
 * [updatedCount]      — rows actually written to DB (unchanged rows are skipped)
 * [negativeSaleDates] — dates where closing exceeds opening after the cascade;
 *                       user must correct the CB on those days manually
 */
data class CascadeResult(
    val updatedCount:       Int,
    val negativeSaleDates:  Set<String>
) {
    val hasNegatives: Boolean get() = negativeSaleDates.isNotEmpty()
}
