package com.simple.simpleinventory.util

import com.simple.simpleinventory.data.entity.Product
import com.simple.simpleinventory.data.entity.stockCode

/**
 * Resolves a purchase productCode (which may be an alias) to the canonical
 * primary product code stored in the products table.
 *
 * Background
 * ----------
 * Products have a primary brandCode (e.g. "1182") and an optional aliases field
 * (e.g. "A542,C542"). The productType prefix (e.g. "W") is prepended to form
 * the full code (e.g. "W1182", "WA542", "WC542").
 *
 * Purchases may be entered or imported using an alias code. All downstream logic
 * (daily stock, reports, cloud sync) uses only the primary code. Alias resolution
 * must happen at write time — before a purchase is saved to the DB.
 *
 * Resolution rules (in priority order)
 * -------------------------------------
 * 1. Exact primary match:  product.stockCode == code
 * 2. Alias match:          code brand-part is in product.aliases (comma-separated)
 *    where "brand-part" = code with productType prefix stripped
 *
 * Returns
 * -------
 * The matching Product if found, or null if the code is unrecognised.
 * Callers replace productCode/productId with the canonical values from the result.
 */
object ProductCodeResolver {

    /**
     * Find the product whose primary or alias code matches [inputCode].
     * [inputCode] is the full code including productType prefix (e.g. "WC542").
     */
    fun resolve(inputCode: String, products: List<Product>): Product? {
        val code = inputCode.trim()
        if (code.isEmpty()) return null

        // 1 — exact primary match
        products.firstOrNull { primaryCode(it) == code }?.let { return it }

        // 2 — alias match: strip any single-letter productType prefix and check aliases
        //     We try stripping each product's own productType from the front of code.
        //     This handles mixed-prefix situations (e.g. "WC542" where productType="W").
        for (product in products) {
            val prefix = product.productType
            if (code.startsWith(prefix, ignoreCase = true)) {
                val brandPart = code.removePrefix(prefix).trim()
                if (matchesAlias(brandPart, product)) return product
            }
        }

        // 3 — fallback: check aliases without any prefix stripping (plain brand code used)
        for (product in products) {
            if (matchesAlias(code, product)) return product
        }

        return null
    }

    /** Primary code: productType + brandCode, e.g. "W1182". */
    fun primaryCode(product: Product): String =
        product.stockCode

    /** Returns true if [brandPart] matches any alias in product.aliases. */
    private fun matchesAlias(brandPart: String, product: Product): Boolean {
        if (product.aliases.isBlank()) return false
        return product.aliases.split(",").any { alias ->
            alias.trim().equals(brandPart, ignoreCase = true)
        }
    }

    /**
     * Build a map of ALL codes (primary + every alias) → Product.
     * Used to normalise old alias-coded DB rows to primary codes during dedup.
     *
     * Example for product W1249 with aliases "A360,C360":
     *   "W1249" → Product, "WA360" → Product, "WC360" → Product
     */
    fun buildFullLookupMap(products: List<Product>): Map<String, Product> {
        val map = mutableMapOf<String, Product>()
        for (p in products) {
            // Primary
            map[p.stockCode] = p
            // All aliases
            if (p.aliases.isNotBlank()) {
                p.aliases.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { alias ->
                    map["${p.productType}$alias"] = p
                }
            }
        }
        return map
    }
}
