package com.simhadri.winentry.data.repository

import com.simhadri.winentry.data.entity.DailyStock

/**
 * Pure per-row arithmetic for opening-stock baselines. Quantities are [qq, pp, nn, dd].
 * On a baseline date OB belongs to Opening Stock and CB belongs to Daily Stock.
 */
object BaselineMath {

    fun open(r: DailyStock) = intArrayOf(r.openQq, r.openPp, r.openNn, r.openDd)
    fun close(r: DailyStock) = intArrayOf(r.closeQq, r.closePp, r.closeNn, r.closeDd)
    fun sale(r: DailyStock) = intArrayOf(r.saleQq, r.salePp, r.saleNn, r.saleDd)

    /** A closing has been entered on this row when any size recorded a sale. */
    fun hasClosing(r: DailyStock) = sale(r).any { it != 0 }

    fun newBaselineRow(date: String, productCode: String, ob: IntArray, pq: IntArray) = DailyStock(
        date = date, productCode = productCode,
        openQq = ob[0], openPp = ob[1], openNn = ob[2], openDd = ob[3],
        closeQq = ob[0] + pq[0], closePp = ob[1] + pq[1],
        closeNn = ob[2] + pq[2], closeDd = ob[3] + pq[3],
        isCommitted = true, isOpeningStock = true
    )

    /**
     * OB correction on an existing baseline row.
     * Closing entered: CB kept, sale = newOB + PQ - CB, amounts at the row's stored prices.
     * No closing yet:  CB moves by the OB delta, sale stays 0.
     */
    fun correctRow(existing: DailyStock, newOb: IntArray, pq: IntArray): DailyStock {
        if (hasClosing(existing)) {
            val cb = close(existing)
            val s = IntArray(4) { newOb[it] + pq[it] - cb[it] }
            return withSale(existing, s).copy(
                openQq = newOb[0], openPp = newOb[1], openNn = newOb[2], openDd = newOb[3],
                isOpeningStock = true
            )
        }
        val oldOb = open(existing)
        val cb = close(existing)
        return existing.copy(
            openQq = newOb[0], openPp = newOb[1], openNn = newOb[2], openDd = newOb[3],
            closeQq = cb[0] + newOb[0] - oldOb[0], closePp = cb[1] + newOb[1] - oldOb[1],
            closeNn = cb[2] + newOb[2] - oldOb[2], closeDd = cb[3] + newOb[3] - oldOb[3],
            isOpeningStock = true
        )
    }

    /** Clears the day's closing on a baseline row: CB back to OB + PQ, no sale, OB and marker kept. */
    fun resetRow(r: DailyStock): DailyStock {
        val cb = close(r); val s = sale(r)
        return r.copy(
            closeQq = cb[0] + s[0], closePp = cb[1] + s[1],
            closeNn = cb[2] + s[2], closeDd = cb[3] + s[3],
            saleQq = 0, salePp = 0, saleNn = 0, saleDd = 0,
            amountQq = 0.0, amountPp = 0.0, amountNn = 0.0, amountDd = 0.0,
            saleAmount = 0.0,
            isOpeningStock = true
        )
    }

    fun withSale(r: DailyStock, s: IntArray): DailyStock {
        val aQq = s[0] * r.priceQq; val aPp = s[1] * r.pricePp
        val aNn = s[2] * r.priceNn; val aDd = s[3] * r.priceDd
        return r.copy(
            saleQq = s[0], salePp = s[1], saleNn = s[2], saleDd = s[3],
            amountQq = aQq, amountPp = aPp, amountNn = aNn, amountDd = aDd,
            saleAmount = aQq + aPp + aNn + aDd
        )
    }
}
