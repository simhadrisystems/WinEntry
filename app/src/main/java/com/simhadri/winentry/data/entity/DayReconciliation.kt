package com.simhadri.winentry.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Day-end cash reconciliation. One row per date.
 *
 * Records how the day's total sale proceeds were received and what
 * remains as cash for bank deposit.
 *
 * Formula: cashForDeposit = totalDaySales - upiReceipts - dayExpenses
 *
 * totalDaySales is a snapshot copied from the DailyStock sum at save time
 * so this record is self-contained even if stock entries are later edited.
 */
@Entity(tableName = "day_reconciliation")
data class DayReconciliation(
    @PrimaryKey
    val date: String,                   // yyyy-MM-dd

    val totalDaySales: Double = 0.0,    // snapshot of DailyStock sum (system)
    val upiReceipts: Double = 0.0,      // UPI / online receipts (user)
    val dayExpenses: Double = 0.0,      // cash expenses paid out (user)
    val cashForDeposit: Double = 0.0,   // totalDaySales - upiReceipts - dayExpenses
    val notes: String = "",             // optional remarks

    val syncStatus: String = SyncStatus.PENDING_UPSERT,
    val lastModified: Long = System.currentTimeMillis()
) {
    companion object {
        fun calculateCashForDeposit(
            totalDaySales: Double,
            upiReceipts: Double,
            dayExpenses: Double
        ): Double = totalDaySales - upiReceipts - dayExpenses
    }
}
