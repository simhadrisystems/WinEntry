package com.simhadri.winentry

import com.simhadri.winentry.sync.CloudSyncManager
import org.junit.Assert.assertEquals
import org.junit.Test

class ReceivedDateParseTest {

    private fun row(txn: String, date: String, received: Any?): List<Any> =
        MutableList<Any>(28) { "" }.apply {
            this[0] = txn; this[1] = date
            if (received != null) add(received)
        }

    @Test fun readsColumnAc_onlyWhenLaterThanInvoice() {
        val rows = listOf(
            row("T1", "2026-09-18", "2026-09-20"),   // kept
            row("T2", "2026-09-18", "2026-09-18"),   // same day = blank
            row("T3", "2026-09-18", ""),             // blank
            row("T4", "2026-09-18", null),           // short row (old A:AB read)
            row("T5", "2026-09-18", "20/09/2026"),   // Indian format
            row("T6", "2026-09-18", "2099-01-01")    // future, rejected
        )
        assertEquals(mapOf("T1" to "2026-09-20", "T5" to "2026-09-20"), CloudSyncManager.parseReceivedDates(rows))
    }
}
