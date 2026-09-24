package com.simhadri.winentry.data.entity

import androidx.room.Entity

/**
 * A local delete that the cloud sheet has not applied yet. Rows here are sent by
 * SyncCoordinator and removed once the Cloud Function confirms; restores skip them.
 *
 * STOCK:       one daily_stock row (date + productCode)
 * STOCK_DATE:  every daily_stock row on date
 * STOCK_RANGE: every daily_stock row from date to dateTo
 * SUMMARY:     the day_reconciliation row on date
 * SUMMARY_RANGE: day_reconciliation rows from date to dateTo
 */
@Entity(tableName = "pending_cloud_deletes", primaryKeys = ["kind", "date", "productCode"])
data class PendingCloudDelete(
    val kind: String,
    val date: String,
    val productCode: String = "",
    val dateTo: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    fun coversStock(d: String, code: String): Boolean = when (kind) {
        STOCK -> date == d && productCode == code
        STOCK_DATE -> date == d
        STOCK_RANGE -> d in date..dateTo
        else -> false
    }

    fun coversSummary(d: String): Boolean = when (kind) {
        SUMMARY -> date == d
        SUMMARY_RANGE -> d in date..dateTo
        else -> false
    }

    companion object {
        const val STOCK = "STOCK"
        const val STOCK_DATE = "STOCK_DATE"
        const val STOCK_RANGE = "STOCK_RANGE"
        const val SUMMARY = "SUMMARY"
        const val SUMMARY_RANGE = "SUMMARY_RANGE"
    }
}
