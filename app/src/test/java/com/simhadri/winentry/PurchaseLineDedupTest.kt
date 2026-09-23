package com.simhadri.winentry

import com.simhadri.winentry.data.entity.Product
import com.simhadri.winentry.data.entity.Purchase
import com.simhadri.winentry.sync.CloudSyncManager
import org.junit.Assert.assertEquals
import org.junit.Test

class PurchaseLineDedupTest {

    private val w1249 = Product(
        id = 7, productName = "Test", productType = "W", brandCode = "1249",
        qqCode = "", ppCode = "", nnCode = "", ddCode = "",
        displayName = "TEST", aliases = "1250"
    )
    private val products = listOf(w1249)
    private val productMap = CloudSyncManager.buildProductLookupMap(products)

    private fun row(txn: String, date: String, code: String, invoice: String, received: String = ""): List<Any> =
        MutableList<Any>(29) { "" }.apply {
            this[0] = txn; this[1] = date; this[2] = code; this[4] = invoice; this[28] = received
        }

    private fun purchase(txn: String, code: String = "W1249", invoice: String = "INV1", date: String = "2026-06-11") =
        Purchase(txnId = txn, purchaseDate = date, productId = 7, productCode = code,
            productName = "TEST", invoiceNumber = invoice, supplierName = "")

    @Test fun cloudLineIndex_keepsNewestCopy_andItsReceivedDate() {
        val rows = listOf(
            row("20260301-100000-AAAA", "2026-06-11", "W1249", "INV1", "2026-06-12"),
            row("20260921-193701-BBBB", "2026-06-11", "W1249", "INV1"),
            row("20260501-100000-CCCC", "2026-06-11", "W1250", "INV1"),   // alias of the same product
            row("20260921-193701-DDDD", "2026-06-12", "W1249", "INV2")
        )
        val index = CloudSyncManager.cloudLineIndex(rows, products)
        assertEquals(2, index.size)
        assertEquals("20260921-193701-BBBB", index["W1249|INV1|2026-06-11"]!!.txnId)
        assertEquals("", index["W1249|INV1|2026-06-11"]!!.receivedDate)
        assertEquals("20260921-193701-DDDD", index["W1249|INV2|2026-06-12"]!!.txnId)
    }

    @Test fun newestPerLine_collapsesCopiesAcrossAliases() {
        val kept = CloudSyncManager.newestPerLine(listOf(
            purchase("20260301-100000-AAAA"),
            purchase("20260921-193701-BBBB"),
            purchase("20260501-100000-CCCC", code = "W1250"),
            purchase("20260101-000000-EEEE", invoice = "INV2")
        ), productMap)
        assertEquals(setOf("20260921-193701-BBBB", "20260101-000000-EEEE"), kept.map { it.txnId }.toSet())
    }
}
