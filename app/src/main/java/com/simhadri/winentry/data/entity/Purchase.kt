package com.simhadri.winentry.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.text.SimpleDateFormat
import java.util.*

/**
 * Purchase order / receipt.
 *
 * One row per purchase entry. Supports four size variants (QQ/PP/NN/DD)
 * each with boxes, loose units, price and cost.
 *
 * Sync:
 *   txnId      — stable identifier generated once at creation, never changed.
 *                Format: yyyyMMdd-HHmmss-XXXX. Used as the Sheets lookup key.
 *   syncStatus — current cloud sync state (see SyncStatus).
 *   isDeleted  — soft-delete: row stays locally until cloud confirms removal.
 */
@Entity(tableName = "purchases")
data class Purchase(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val txnId: String = "",
    val syncStatus: String = SyncStatus.PENDING_INSERT,
    val isDeleted: Boolean = false,

    val purchaseDate: String,
    val productId: Long,
    val productCode: String,
    val productName: String,

    // QQ
    val qqBoxes: Int = 0,
    val qqLoose: Int = 0,
    val qqUnitsPerBox: Int = 0,
    val qqTotalUnits: Int = 0,
    val qqUnitPrice: Double = 0.0,
    val qqTotalCost: Double = 0.0,

    // PP
    val ppBoxes: Int = 0,
    val ppLoose: Int = 0,
    val ppUnitsPerBox: Int = 0,
    val ppTotalUnits: Int = 0,
    val ppUnitPrice: Double = 0.0,
    val ppTotalCost: Double = 0.0,

    // NN
    val nnBoxes: Int = 0,
    val nnLoose: Int = 0,
    val nnUnitsPerBox: Int = 0,
    val nnTotalUnits: Int = 0,
    val nnUnitPrice: Double = 0.0,
    val nnTotalCost: Double = 0.0,

    // DD
    val ddBoxes: Int = 0,
    val ddLoose: Int = 0,
    val ddUnitsPerBox: Int = 0,
    val ddTotalUnits: Int = 0,
    val ddUnitPrice: Double = 0.0,
    val ddTotalCost: Double = 0.0,

    val totalCost: Double = 0.0,
    val supplierName: String = "",
    val invoiceNumber: String = "",
    val receivedDate: String = "",   // blank = same as purchaseDate
    val notes: String = "",

    val isProcessed: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        /**
         * Generate a unique transaction ID.
         * Format: yyyyMMdd-HHmmss-XXXX  e.g. 20260319-143022-A3F9
         * Call only when creating a new Purchase — never on edit.
         */
        fun generateTxnId(): String {
            val date   = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val suffix = (0..0xFFFF).random().toString(16).uppercase().padStart(4, '0')
            return "$date-$suffix"
        }

        fun calculateTotalUnits(boxes: Int, loose: Int, unitsPerBox: Int): Int =
            boxes * unitsPerBox + loose

        fun calculateTotalCost(totalUnits: Int, unitPrice: Double): Double =
            totalUnits * unitPrice
    }
}

/** Convenience wrapper for display with product master data. */
data class PurchaseWithProduct(
    val purchase: Purchase,
    val product: Product
)
