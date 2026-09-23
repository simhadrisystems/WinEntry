package com.simhadri.winentry.data.entity

/**
 * UI Model for daily entry (not stored in DB)
 */
data class DailyEntry(
    val product: Product,
    val date: String,
    val opening: ProductSizeQty,   // From previous day
    val purchase: ProductSizeQty,  // User enters
    val sale: ProductSizeQty,      // Calculated
    val closing: ProductSizeQty,   // User enters
    val committedAmounts: ProductSizeAmounts? = null,  // locked at commit time; null = not committed
    val isBaseline: Boolean = false                    // row is on an opening-stock baseline date
) {
    // For committed rows, use the locked amounts from DB (price at commit time).
    // For draft rows, compute from current product master prices.
    val saleAmount: Double
        get() = if (committedAmounts != null)
            committedAmounts.qq + committedAmounts.pp + committedAmounts.nn + committedAmounts.dd
        else
            (sale.qq * product.qqSalePrice) +
            (sale.pp * product.ppSalePrice) +
            (sale.nn * product.nnSalePrice) +
            (sale.dd * product.ddSalePrice)

    // True when this committed row was locked at a different sell price than the current master.
    val hasPriceMismatch: Boolean
        get() {
            val ca = committedAmounts ?: return false
            val eps = 0.001
            if (sale.qq > 0 && kotlin.math.abs(ca.qq - sale.qq * product.qqSalePrice) > eps) return true
            if (sale.pp > 0 && kotlin.math.abs(ca.pp - sale.pp * product.ppSalePrice) > eps) return true
            if (sale.nn > 0 && kotlin.math.abs(ca.nn - sale.nn * product.nnSalePrice) > eps) return true
            if (sale.dd > 0 && kotlin.math.abs(ca.dd - sale.dd * product.ddSalePrice) > eps) return true
            return false
        }
}

/** Per-size sale amounts locked at commit time (Double, not Int). */
data class ProductSizeAmounts(
    val qq: Double = 0.0,
    val pp: Double = 0.0,
    val nn: Double = 0.0,
    val dd: Double = 0.0
)

/**
 * Helper class for size quantities
 */
data class ProductSizeQty(
    val qq: Int = 0,
    val pp: Int = 0,
    val nn: Int = 0,
    val dd: Int = 0
)

/**
 * Stock Report Item (not stored in DB)
 */
data class StockItem(
    val product: Product,
    val qqStock: Int = 0,
    val ppStock: Int = 0,
    val nnStock: Int = 0,
    val ddStock: Int = 0,
    val totalStock: Int = 0
)
