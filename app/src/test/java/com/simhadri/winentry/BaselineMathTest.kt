package com.simhadri.winentry

import com.simhadri.winentry.data.entity.DailyStock
import com.simhadri.winentry.data.repository.BaselineMath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BaselineMathTest {

    private fun row(ob: IntArray, cb: IntArray, sale: IntArray, price: Double = 10.0) = DailyStock(
        date = "2026-09-20", productCode = "W1",
        openQq = ob[0], openPp = ob[1], openNn = ob[2], openDd = ob[3],
        closeQq = cb[0], closePp = cb[1], closeNn = cb[2], closeDd = cb[3],
        saleQq = sale[0], salePp = sale[1], saleNn = sale[2], saleDd = sale[3],
        priceQq = price, pricePp = price, priceNn = price, priceDd = price,
        isCommitted = true, isOpeningStock = true
    )

    @Test fun closingEntered_keepsCb_recalculatesSale() {
        // OB 10, PQ 5, CB 12 -> sale 3; correct OB to 12 -> sale 5
        val before = row(intArrayOf(10, 0, 0, 0), intArrayOf(12, 0, 0, 0), intArrayOf(3, 0, 0, 0))
        val after = BaselineMath.correctRow(before, intArrayOf(12, 0, 0, 0), intArrayOf(5, 0, 0, 0))
        assertArrayEquals(intArrayOf(12, 0, 0, 0), BaselineMath.open(after))
        assertArrayEquals(intArrayOf(12, 0, 0, 0), BaselineMath.close(after))
        assertArrayEquals(intArrayOf(5, 0, 0, 0), BaselineMath.sale(after))
        assertEquals(50.0, after.saleAmount, 0.001)
        assertTrue(after.isOpeningStock)
    }

    @Test fun closingEntered_lowerOb_givesNegativeSale() {
        val before = row(intArrayOf(10, 0, 0, 0), intArrayOf(8, 0, 0, 0), intArrayOf(2, 0, 0, 0))
        val after = BaselineMath.correctRow(before, intArrayOf(6, 0, 0, 0), IntArray(4))
        assertArrayEquals(intArrayOf(8, 0, 0, 0), BaselineMath.close(after))
        assertArrayEquals(intArrayOf(-2, 0, 0, 0), BaselineMath.sale(after))
    }

    @Test fun noClosing_cbMovesWithOb_saleStaysZero() {
        // OB 10, PQ 4, CB 14 (no closing yet); correct OB to 7 -> CB 11
        val before = row(intArrayOf(10, 2, 0, 0), intArrayOf(14, 2, 0, 0), IntArray(4))
        val after = BaselineMath.correctRow(before, intArrayOf(7, 2, 0, 0), intArrayOf(4, 0, 0, 0))
        assertArrayEquals(intArrayOf(11, 2, 0, 0), BaselineMath.close(after))
        assertArrayEquals(IntArray(4), BaselineMath.sale(after))
        assertEquals(0.0, after.saleAmount, 0.001)
    }

    @Test fun newBaseline_cbIsObPlusPurchase() {
        val r = BaselineMath.newBaselineRow("2026-09-20", "W1", intArrayOf(5, 1, 0, 0), intArrayOf(2, 0, 0, 3))
        assertArrayEquals(intArrayOf(5, 1, 0, 0), BaselineMath.open(r))
        assertArrayEquals(intArrayOf(7, 1, 0, 3), BaselineMath.close(r))
        assertArrayEquals(IntArray(4), BaselineMath.sale(r))
        assertTrue(r.isOpeningStock && r.isCommitted)
    }

    @Test fun resetRow_restoresObPlusPurchase_keepsObAndMarker() {
        // OB 10, PQ 5, CB 12, sale 3 -> reset CB 15
        val before = row(intArrayOf(10, 0, 0, 0), intArrayOf(12, 0, 0, 0), intArrayOf(3, 0, 0, 0))
        val r = BaselineMath.resetRow(before.copy(amountQq = 30.0, saleAmount = 30.0))
        assertArrayEquals(intArrayOf(10, 0, 0, 0), BaselineMath.open(r))
        assertArrayEquals(intArrayOf(15, 0, 0, 0), BaselineMath.close(r))
        assertArrayEquals(IntArray(4), BaselineMath.sale(r))
        assertEquals(0.0, r.saleAmount, 0.001)
        assertTrue(r.isOpeningStock)
    }

    @Test fun hasClosing_detectsAnyNonZeroSale() {
        assertTrue(BaselineMath.hasClosing(row(IntArray(4), IntArray(4), intArrayOf(0, 0, -1, 0))))
        assertTrue(!BaselineMath.hasClosing(row(intArrayOf(5, 0, 0, 0), intArrayOf(5, 0, 0, 0), IntArray(4))))
    }
}
