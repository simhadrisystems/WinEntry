package com.simhadri.winentry.ui.quicksale

import com.simhadri.winentry.data.entity.Product

/**
 * OB/CB mode: Sale Qty is derived from Opening + Purchase − Closing (typed by hand).
 * DIRECT_QTY mode: Sale Qty is typed directly, OB/PQ/CB are not used at all.
 */
enum class QuickSaleMode { OB_CB, DIRECT_QTY }

/** Per-size quantity — local to this screen, deliberately not DailyStock's ProductSizeQty. */
data class SizeQty(
    val qq: Int = 0,
    val pp: Int = 0,
    val nn: Int = 0,
    val dd: Int = 0
)

fun SizeQty.with(size: String, value: Int): SizeQty = when (size) {
    "QQ" -> copy(qq = value)
    "PP" -> copy(pp = value)
    "NN" -> copy(nn = value)
    "DD" -> copy(dd = value)
    else -> this
}

/**
 * One in-memory row of the Quick Sale Check scratchpad — never persisted to Room.
 * [opening]/[purchase]/[closing] are used in OB_CB mode; [directSale] in DIRECT_QTY mode.
 * Both column sets are kept simultaneously so switching modes never loses what was typed.
 */
data class QuickSaleRow(
    val product: Product,
    val opening: SizeQty = SizeQty(),
    val purchase: SizeQty = SizeQty(),
    val closing: SizeQty = SizeQty(),
    val directSale: SizeQty = SizeQty()
) {
    fun sale(mode: QuickSaleMode): SizeQty = if (mode == QuickSaleMode.DIRECT_QTY) {
        directSale
    } else {
        SizeQty(
            qq = opening.qq + purchase.qq - closing.qq,
            pp = opening.pp + purchase.pp - closing.pp,
            nn = opening.nn + purchase.nn - closing.nn,
            dd = opening.dd + purchase.dd - closing.dd
        )
    }

    fun saleAmount(mode: QuickSaleMode): Double {
        val s = sale(mode)
        return s.qq * product.qqSalePrice +
               s.pp * product.ppSalePrice +
               s.nn * product.nnSalePrice +
               s.dd * product.ddSalePrice
    }

    /**
     * Value of the typed Closing Balance at current sale price. Only meaningful in
     * OB_CB mode — [closing] is never touched in Direct Qty mode, so this is 0 for
     * any row where only a direct Sale Qty was entered.
     */
    fun closingStockValue(): Double =
        closing.qq * product.qqSalePrice +
        closing.pp * product.ppSalePrice +
        closing.nn * product.nnSalePrice +
        closing.dd * product.ddSalePrice

    /** Same as [closingStockValue] but at current purchase price — the cost basis of the stock. */
    fun closingStockValueAtPurchasePrice(): Double =
        closing.qq * product.qqPurchasePrice +
        closing.pp * product.ppPurchasePrice +
        closing.nn * product.nnPurchasePrice +
        closing.dd * product.ddPurchasePrice

    /** True if nothing has been typed into this row yet — safe to drop without data loss. */
    fun isUntouched(): Boolean =
        opening == SizeQty() && purchase == SizeQty() && closing == SizeQty() && directSale == SizeQty()
}
