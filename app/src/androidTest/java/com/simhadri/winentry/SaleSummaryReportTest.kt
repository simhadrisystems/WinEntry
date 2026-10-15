package com.simhadri.winentry

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.simhadri.winentry.data.AppDatabase
import com.simhadri.winentry.data.entity.DailyStock
import com.simhadri.winentry.data.entity.Product
import com.simhadri.winentry.data.entity.Purchase
import com.simhadri.winentry.data.entity.stockCode
import com.simhadri.winentry.ui.reports.ReportViewerFragment
import com.simhadri.winentry.ui.reports.ReportViewerViewModel
import com.simhadri.winentry.utils.SaleSummaryGroups
import com.simhadri.winentry.utils.SaleSummaryGroups.Slot
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Daily Sale Summary groups OB / receipts / CB / sale by IML, BEER, RTD and the
 * user-named extra group, following the saved type assignment.
 */
@RunWith(AndroidJUnit4::class)
class SaleSummaryReportTest {

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = instr.targetContext
    private lateinit var db: AppDatabase
    private var realInstance: Any? = null
    private val instanceField = AppDatabase::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }
    private val prev = "2026-10-13"
    private val d    = "2026-10-14"
    private val next = "2026-10-15"

    @Before
    fun setUp() {
        realInstance = instanceField.get(null)
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        instanceField.set(null, db)
        ctx.getSharedPreferences("sale_summary_groups", Context.MODE_PRIVATE).edit().clear().commit()
        ctx.getSharedPreferences("business_info", Context.MODE_PRIVATE).edit()
            .putString("business_name", "SIMHADRI WINES").putString("licence_no", "GSND-07 GBL").commit()
        seed()
    }

    @After
    fun tearDown() {
        instanceField.set(null, realInstance)
        db.close()
        ctx.getSharedPreferences("sale_summary_groups", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun product(type: String, code: String, active: Boolean = true) = Product(
        productName = "$type$code", productType = type, brandCode = code,
        qqCode = "$type${code}QQ", ppCode = "$type${code}PP", nnCode = "$type${code}NN", ddCode = "$type${code}DD",
        displayName = "$type$code", isActive = active)

    private fun row(date: String, code: String, ob: IntArray, cb: IntArray) = DailyStock(
        date = date, productCode = code,
        openQq = ob[0], openPp = ob[1], openNn = ob[2], openDd = ob[3],
        closeQq = cb[0], closePp = cb[1], closeNn = cb[2], closeDd = cb[3],
        isCommitted = true)

    private fun seed() = runBlocking {
        db.productDao().insertProducts(listOf(
            product("W", "A1"), product("Y", "B1"), product("B", "K1"),
            product("R", "C1"), product("E", "W1"), product("V", "X1", active = false)))
        val z = IntArray(4)
        db.dailyStockDao().upsertCommittedBatch(listOf(
            row(prev, "WA1", z, intArrayOf(100, 80, 200, 50)),
            row(prev, "YB1", z, intArrayOf(20, 0, 0, 0)),
            row(prev, "BK1", z, intArrayOf(300, 100, 0, 0)),
            row(prev, "RC1", z, intArrayOf(10, 0, 0, 0)),
            row(prev, "EW1", z, intArrayOf(5, 0, 0, 0)),
            row(prev, "VX1", z, intArrayOf(7, 0, 0, 0)),
            // EW1 untouched on d: no committed row, so CB = OB and sale 0
            row(d, "WA1", intArrayOf(100, 80, 200, 50), intArrayOf(95, 70, 190, 45)),
            row(d, "YB1", intArrayOf(20, 0, 0, 0), intArrayOf(18, 0, 0, 0)),
            row(d, "BK1", intArrayOf(300, 100, 0, 0), intArrayOf(280, 90, 0, 0)),
            row(d, "RC1", intArrayOf(10, 0, 0, 0), intArrayOf(9, 0, 0, 0)),
        ))
        val w = db.productDao().getAllProductsSync().first { it.stockCode == "WA1" }
        db.purchaseDao().insert(Purchase(purchaseDate = d, productId = w.id, productCode = "WA1",
            productName = w.productName, qqTotalUnits = 12, invoiceNumber = "INV1"))
    }

    private fun load(date: String): ReportViewerViewModel {
        lateinit var vm: ReportViewerViewModel
        instr.runOnMainSync {
            vm = ReportViewerViewModel(ctx.applicationContext as Application)
            vm.loadReport(Bundle().apply {
                putString(ReportViewerFragment.ARG_REPORT_TYPE, ReportViewerFragment.TYPE_SALE_SUMMARY)
                putString(ReportViewerFragment.ARG_DATE, date)
                putString(ReportViewerFragment.ARG_TITLE, "Daily Sale Report")
            })
        }
        val end = System.currentTimeMillis() + 10_000
        while (vm.state.value !is ReportViewerViewModel.ReportState.Ready) {
            check(vm.state.value !is ReportViewerViewModel.ReportState.Error) { "report error: ${vm.state.value}" }
            check(System.currentTimeMillis() < end) { "timed out, state=${vm.state.value}" }
            Thread.sleep(50)
        }
        File(ctx.getExternalFilesDir(null), "sale_summary_$date.html").writeText(vm.cachedHtml)
        File(ctx.getExternalFilesDir(null), "sale_summary_$date.txt").writeText(vm.cachedText)
        return vm
    }

    /** OB, Rcpt, CB, Sale on the text line whose label is [label]. */
    private fun nums(text: String, label: String): List<Int> {
        val line = text.lines().firstOrNull { it.trimEnd().startsWith(label) && it.substring(label.length).trim().firstOrNull()?.isDigit() == true }
            ?: error("no line '$label' in:\n$text")
        return line.substring(label.length).trim().split(Regex("\\s+")).map { it.toInt() }
    }

    @Test
    fun defaultGroups_imlIsWhiskyBrandyBySize_restIsRtd() {
        val t = load(d).cachedText
        assertTrue(t.contains("SIMHADRI WINES") && t.contains("Licence: GSND-07 GBL") && t.contains("14/10/2026"))
        assertEquals(listOf(120, 12, 113, 19), nums(t, " QQ"))
        assertEquals(listOf(80, 0, 70, 10), nums(t, " PP"))
        assertEquals(listOf(200, 0, 190, 10), nums(t, " NN"))
        assertEquals(listOf(50, 0, 45, 5), nums(t, " DD"))
        assertEquals(listOf(450, 12, 418, 44), nums(t, " Total"))
        assertEquals(listOf(400, 0, 370, 30), nums(t, "BEER"))
        // rum + untouched wine; inactive vodka excluded
        assertEquals(listOf(15, 0, 14, 1), nums(t, "RTD"))
        assertFalse(t.contains("OTHERS"))
        assertFalse(t.contains("not yet entered"))
    }

    @Test
    fun customGroups_rumToIml_wineToNamedExtra_vodkaNotReported() {
        SaleSummaryGroups.save(ctx, SaleSummaryGroups(
            extraName  = "WINE",
            assignment = mapOf("W" to Slot.IML, "Y" to Slot.IML, "R" to Slot.IML,
                               "E" to Slot.EXTRA, "V" to Slot.NONE),
            bySize     = mapOf(Slot.IML to true)))
        val t = load(d).cachedText
        assertEquals(listOf(130, 12, 122, 20), nums(t, " QQ"))
        assertEquals(listOf(400, 0, 370, 30), nums(t, "BEER"))
        assertEquals(listOf(0, 0, 0, 0), nums(t, "RTD"))
        assertEquals(listOf(5, 0, 5, 0), nums(t, "WINE"))
    }

    @Test
    fun dateWithNoClosingEntered_warns() {
        val vm = load(next)
        assertTrue(vm.cachedText.contains("closing stock not yet entered"))
        assertTrue(vm.cachedHtml.contains("Closing stock has not been entered"))
        assertEquals(listOf(418, 0, 418, 0), nums(vm.cachedText, " Total"))
    }
}
