package com.simple.simpleinventory.data.entity

/**
 * UI Model for daily entry (not stored in DB)
 */
data class DailyEntry(
    val product: Product,
    val date: String,
    val opening: ProductSizeQty,   // From previous day
    val purchase: ProductSizeQty,  // User enters
    val sale: ProductSizeQty,      // Calculated
    val closing: ProductSizeQty    // User enters
) {
    /**
     * Calculate total sale amount (revenue) for this product today
     * Formula: (SQ_QQ × QQSalePrice) + (SQ_PP × PPSalePrice) + (SQ_NN × NNSalePrice) + (SQ_DD × DDSalePrice)
     */
    val saleAmount: Double
        get() = (sale.qq * product.qqSalePrice) +
                (sale.pp * product.ppSalePrice) +
                (sale.nn * product.nnSalePrice) +
                (sale.dd * product.ddSalePrice)
}

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
