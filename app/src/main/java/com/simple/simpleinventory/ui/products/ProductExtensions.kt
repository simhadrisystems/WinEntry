package com.simple.simpleinventory.data.entity

/**
 * Extension properties for [Product] to eliminate inline string computation
 * of the brand-level stock code throughout the codebase.
 *
 * The stock code is the canonical identifier used as [DailyStock.productCode]:
 *   productType + brandCode  →  e.g. "W" + "1249"  =  "W1249"
 *
 * Previously this was computed inline as "${product.productType}${product.brandCode}"
 * in 20+ places across ViewModels, Fragments and Repository. A single typo or
 * field rename would silently break all of them differently.
 *
 * Usage:
 *   product.stockCode          // "W1249"
 *   products.associateBy { it.stockCode }
 *   DailyStock(productCode = product.stockCode, ...)
 */
val Product.stockCode: String
    get() = "$productType$brandCode"
