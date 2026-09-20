package com.simhadri.winentry.data.entity

import androidx.room.Entity

/**
 * Daily Stock Entry — ONE row per PRODUCT per date (all 4 sizes combined).
 * Composite Primary Key: date + productCode (brand-level, e.g. "W1249")
 *
 * ── Why one row per product ───────────────────────────────────────────────
 * Previous schema had 4 rows per product (one per size code W1249QQ/PP/NN/DD).
 * With 100 products that meant 400 DB writes per full save.
 * This schema reduces it to 100 writes — a 4× improvement.
 *
 * ── Data integrity ────────────────────────────────────────────────────────
 * All values are snapshots committed at save time and never re-derived:
 *   open*    previous day's closing qty per size (snapshot, not re-queried)
 *   close*   user-entered closing qty per size
 *   sale*    open* + purchaseQty(live) − close* per size, stored at commit
 *   price*   sale price per size at commit time (snapshot)
 *   amount*  sale* × price* per size, stored at commit
 *   saleAmount  sum of all four amounts — quick total for reports
 *
 * Purchase quantities are NOT stored here — they live in the `purchases`
 * table with full invoice detail. The ViewModel reads them live at load time.
 */
@Entity(
    tableName = "daily_stock",
    primaryKeys = ["date", "productCode"]
)
data class DailyStock(
    val date:        String,        // yyyy-MM-dd
    val productCode: String,        // brand-level e.g. "W1249" (not size-level)

    // ── Opening balance snapshots (previous day's closing, locked at commit) ──
    val openQq: Int = 0,
    val openPp: Int = 0,
    val openNn: Int = 0,
    val openDd: Int = 0,

    // ── User-entered closing balances ─────────────────────────────────────────
    val closeQq: Int = 0,
    val closePp: Int = 0,
    val closeNn: Int = 0,
    val closeDd: Int = 0,

    // ── Sale quantities (open + purch − close, stored at commit) ─────────────
    val saleQq: Int = 0,
    val salePp: Int = 0,
    val saleNn: Int = 0,
    val saleDd: Int = 0,

    // ── Sale price snapshots (locked at commit time) ──────────────────────────
    val priceQq: Double = 0.0,
    val pricePp: Double = 0.0,
    val priceNn: Double = 0.0,
    val priceDd: Double = 0.0,

    // ── Sale amounts per size (saleX × priceX, stored at commit) ─────────────
    val amountQq: Double = 0.0,
    val amountPp: Double = 0.0,
    val amountNn: Double = 0.0,
    val amountDd: Double = 0.0,

    // ── Total sale amount (sum of four amounts — fast for reports) ────────────
    val saleAmount: Double = 0.0,

    val isCommitted:  Boolean = false,
    val syncStatus:   String  = SyncStatus.SYNCED,
    val lastModified: Long    = System.currentTimeMillis(),

    // ── Opening-stock baseline marker ──────────────────────────────────────────
    // True only for rows saved via the Opening Stock Setup screen (initial setup,
    // in-place edit, or a re-baseline). The ACTIVE opening stock date is
    // MAX(date) WHERE isOpeningStock = 1 — older marked dates stay as read-only
    // Daily Stock history once a newer baseline is set.
    val isOpeningStock: Boolean = false
)
