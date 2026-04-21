package com.simhadri.winentry.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.io.Serializable

/**
 * Product Master - Core product information
 * Brand Code is UNIQUE to prevent duplicate entries
 */
@Entity(
    tableName = "products",
    indices = [androidx.room.Index(value = ["brandCode"], unique = true)]
)
data class Product(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    // Basic Info
    val productName: String,
    val productType: String,
    val category: String = "",
    val brandCode: String,

    // Size Codes (Auto-generated: Type + Brand + Size)
    val qqCode: String,
    val ppCode: String,
    val nnCode: String,
    val ddCode: String,

    // Prices
    val qqPurchasePrice: Double = 0.0,
    val qqSalePrice: Double    = 0.0,
    val ppPurchasePrice: Double = 0.0,
    val ppSalePrice: Double    = 0.0,
    val nnPurchasePrice: Double = 0.0,
    val nnSalePrice: Double    = 0.0,
    val ddPurchasePrice: Double = 0.0,
    val ddSalePrice: Double    = 0.0,

    // Box/Pack Quantities (units per box for each size)
    val qqUnitsPerBox: Int = 12,   // Default: 12 QQ units per box
    val ppUnitsPerBox: Int = 24,   // Default: 24 PP units per box
    val nnUnitsPerBox: Int = 48,   // Default: 48 NN units per box
    val ddUnitsPerBox: Int = 96,   // Default: 96 DD units per box

    // Other fields
    val displayName: String,
    val serialNo: Int        = 1,
    val isActive: Boolean    = true,

    // ── Daily Stock Sort Key ─────────────────────────────────────
    // User-defined 1-999 integer that controls the display order
    // of this product in the Daily Stock entry screen.
    // Default = 999 so new products appear at the bottom until
    // the user assigns a specific position.
    // Ordering: dailySortKey ASC, then displayName ASC as tie-breaker.
    val dailySortKey: Int = 999,
    
    // ── Alternative Brand Codes (Aliases) ────────────────────────
    // Comma-separated list of alternative brand codes for this product.
    // Example: "A538,C538" for a product with primary brandCode "1177"
    // This allows import/search to recognize multiple manufacturer codes
    // as the same product.
    // Empty string if no aliases.
    val aliases: String = ""

) : Serializable

/**
 * Extension function to get all brand codes (primary + aliases) for matching
 */
fun Product.getAllBrandCodes(): List<String> {
    val codes = mutableListOf(brandCode)
    if (aliases.isNotBlank()) {
        codes.addAll(aliases.split(",").map { it.trim() })
    }
    return codes
}

/**
 * Extension function to check if a brand code matches this product (including aliases)
 */
fun Product.matchesBrandCode(code: String): Boolean {
    return brandCode.equals(code, ignoreCase = true) || 
           aliases.split(",").any { it.trim().equals(code, ignoreCase = true) }
}
