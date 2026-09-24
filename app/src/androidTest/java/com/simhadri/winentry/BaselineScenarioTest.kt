package com.simhadri.winentry

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.simhadri.winentry.data.AppDatabase
import com.simhadri.winentry.data.dao.DailyStockDao
import com.simhadri.winentry.data.entity.DailyStock
import com.simhadri.winentry.data.entity.Product
import com.simhadri.winentry.data.repository.BaselineMath
import com.simhadri.winentry.data.repository.DailyStockRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineScenarioTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: DailyStockDao
    private lateinit var repo: DailyStockRepository
    private lateinit var products: List<Product>

    private val b1 = "2026-01-31"
    private val dm1 = "2026-09-19"
    private val d = "2026-09-20"
    private val d1 = "2026-09-21"

    private fun product(code: String) = Product(
        productName = code, productType = "W", brandCode = code,
        qqCode = "W${code}QQ", ppCode = "W${code}PP", nnCode = "W${code}NN", ddCode = "W${code}DD",
        qqSalePrice = 10.0, ppSalePrice = 10.0, nnSalePrice = 10.0, ddSalePrice = 10.0,
        displayName = code
    )

    private fun q(qq: Int) = intArrayOf(qq, 0, 0, 0)

    /** Row as Daily Stock builds it: no isOpeningStock, sale = OB + PQ - CB. */
    private fun dailyRow(date: String, code: String, ob: Int, pq: Int, cb: Int) = DailyStock(
        date = date, productCode = code, openQq = ob, closeQq = cb, saleQq = ob + pq - cb,
        priceQq = 10.0, amountQq = (ob + pq - cb) * 10.0, saleAmount = (ob + pq - cb) * 10.0,
        isCommitted = true
    )

    private suspend fun row(date: String, code: String) = dao.getDailyStock(date, code)!!

    @Before
    fun setUp() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        dao = db.dailyStockDao()
        repo = DailyStockRepository(db.productDao(), dao)
        db.productDao().insertProducts(listOf(product("1"), product("2"), product("3")))
        products = db.productDao().getAllProductsSync()

        // Baseline 1 and trading up to D-1
        dao.upsertCommittedBatch(products.map { BaselineMath.newBaselineRow(b1, "W${it.brandCode}", q(50), q(0)) })
        dao.upsertCommittedBatch(products.map { dailyRow(dm1, "W${it.brandCode}", 50, 0, 40) })

        // Baseline 2 on D: new baseline, W1 has 5 purchased that day
        val plan = repo.planOpeningStockSave(
            d, products,
            mapOf("W1" to q(20), "W2" to q(30), "W3" to q(10)),
            mapOf("W1" to q(5)), isNewBaseline = true
        )
        assertEquals(3, plan.size)
        repo.applyOpeningStockSave(d, plan, products)

        // Daily Stock: closings on D for W1, W2 (W3 has none), saved without the flag
        repo.saveAllEntries(listOf(dailyRow(d, "W1", 20, 5, 18), dailyRow(d, "W2", 30, 0, 25)))
        // D+1 for all three
        repo.saveAllEntries(listOf(
            dailyRow(d1, "W1", 18, 0, 15), dailyRow(d1, "W2", 25, 0, 20), dailyRow(d1, "W3", 10, 0, 8)
        ))
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun markerSurvivesDailyStockSaves() = runBlocking {
        assertEquals(d, dao.getLatestOpeningStockDate())
        assertTrue(dao.getAllDailyStockForDate(d).all { it.isOpeningStock })
        assertTrue(dao.getAllDailyStockForDate(d1).none { it.isOpeningStock })
        assertEquals(d, repo.getLatestOpeningStockDateOrHeal())
    }

    @Test
    fun newBaseline_cbIncludesPurchase() = runBlocking {
        val w3 = row(d, "W3")
        assertArrayEquals(q(10), BaselineMath.close(w3))
        assertEquals(0, w3.saleQq)
    }

    @Test
    fun correction_withClosing_changesOnlyThatProduct() = runBlocking {
        val w2Before = row(d, "W2")
        val w1NextBefore = row(d1, "W1")
        val plan = repo.planOpeningStockSave(
            d, products, mapOf("W1" to q(22), "W2" to q(30), "W3" to q(10)), mapOf("W1" to q(5)), false
        )
        assertEquals(1, plan.size)
        assertTrue(plan[0].cbKept)
        repo.applyOpeningStockSave(d, plan, products)

        val w1 = row(d, "W1")
        assertEquals(22, w1.openQq)
        assertEquals(18, w1.closeQq)
        assertEquals(9, w1.saleQq)          // 22 + 5 - 18
        assertEquals(90.0, w1.saleAmount, 0.001)
        assertEquals(w2Before, row(d, "W2"))
        assertEquals(w1NextBefore.copy(lastModified = 0), row(d1, "W1").copy(lastModified = 0))
    }

    @Test
    fun correction_withoutClosing_movesCbAndNextDay() = runBlocking {
        val plan = repo.planOpeningStockSave(d, products, mapOf("W3" to q(14)), emptyMap(), false)
        assertEquals(1, plan.size)
        repo.applyOpeningStockSave(d, plan, products)
        val w3 = row(d, "W3")
        assertEquals(14, w3.openQq); assertEquals(14, w3.closeQq); assertEquals(0, w3.saleQq)
        val next = row(d1, "W3")
        assertEquals(14, next.openQq)
        assertEquals(6, next.saleQq)        // 14 - 8
    }

    @Test
    fun unchangedEntry_nothingToUpdate() = runBlocking {
        val plan = repo.planOpeningStockSave(
            d, products, mapOf("W1" to q(20), "W2" to q(30), "W3" to q(10)), mapOf("W1" to q(5)), false
        )
        assertTrue(plan.isEmpty())
    }

    @Test
    fun changeOnDayBefore_doesNotTouchBaseline() = runBlocking {
        val before = dao.getAllDailyStockForDate(d)
        val edited = listOf(dailyRow(dm1, "W1", 50, 0, 35))
        assertTrue(!repo.previewCascade(edited).isNeeded)
        repo.saveAllEntries(edited)
        repo.cascadeRecalculate(edited, products)
        assertEquals(before.map { it.copy(lastModified = 0) }, dao.getAllDailyStockForDate(d).map { it.copy(lastModified = 0) })
    }

    @Test
    fun clearEntryOnBaseline_keepsObAndMarker() = runBlocking {
        repo.clearEntryWithCascade(d, "W1", products, q(5))
        val w1 = row(d, "W1")
        assertTrue(w1.isOpeningStock)
        assertEquals(20, w1.openQq); assertEquals(25, w1.closeQq); assertEquals(0, w1.saleQq)
        assertEquals(25, row(d1, "W1").openQq)
        assertEquals(10, row(d1, "W1").saleQq)   // 25 - 15
    }

    @Test
    fun clearDateOnBaseline_resetsRows() = runBlocking {
        repo.clearDateData(d, mapOf("W1" to q(5)))
        val rows = dao.getAllDailyStockForDate(d)
        assertEquals(3, rows.size)
        assertTrue(rows.all { it.isOpeningStock && it.saleQq == 0 })
        assertEquals(d, dao.getLatestOpeningStockDate())
    }

    @Test
    fun openingForImport_usesBaselineOb() = runBlocking {
        assertArrayEquals(q(20), repo.getOpeningFor("W1", d))
        assertArrayEquals(q(18), repo.getOpeningFor("W1", d1))
    }

    @Test
    fun picker_repairsPartialMarker_andCanPickOlder() = runBlocking {
        db.openHelper.writableDatabase.execSQL(
            "UPDATE daily_stock SET isOpeningStock = 0 WHERE date = '$d' AND productCode = 'W2'"
        )
        val c = repo.getBaselineCandidates()
        assertEquals(listOf(d, b1), c.map { it.date })
        assertEquals(2, c[0].markedCount); assertEquals(3, c[0].committedCount)

        repo.setActiveBaseline(d, products, emptyMap())
        assertTrue(dao.getAllDailyStockForDate(d).all { it.isOpeningStock })
        assertTrue(dao.getPendingSyncStock().any { it.date == d && it.productCode == "W2" })

        repo.setActiveBaseline(b1, products, emptyMap())
        assertEquals(b1, dao.getLatestOpeningStockDate())
        assertTrue(dao.getAllDailyStockForDate(d).none { it.isOpeningStock })
    }

    @Test
    fun deleteBaseline_previousBecomesActive() = runBlocking {
        repo.clearOpeningStockWithCascade(d, products, mapOf("W1" to q(5)))
        assertEquals(b1, dao.getLatestOpeningStockDate())
        assertEquals(45, row(d1, "W1").openQq)   // D-1 closing 40 + the 5 purchased on D
        assertEquals(40, row(d1, "W2").openQq)
    }

    /** W4 traded before baseline D but has no row on D; a leaked OB of 40 was saved on D+1. */
    private suspend fun addProductMissingFromBaseline(): List<Product> {
        db.productDao().insertProducts(listOf(product("4")))
        dao.upsertCommittedBatch(listOf(dailyRow(dm1, "W4", 50, 0, 40)))
        dao.upsertCommittedBatch(listOf(dailyRow(d1, "W4", 40, 0, 40)))
        return db.productDao().getAllProductsSync()
    }

    @Test
    fun productWithoutBaselineRow_startsFromZero() = runBlocking {
        addProductMissingFromBaseline()
        assertArrayEquals(IntArray(4), repo.getOpeningFor("W4", d))
        assertTrue(repo.getPreviousRow("W4", d1) == null)
        assertTrue("W4" !in repo.getBulkPreviousRows(listOf("W1", "W4"), d1))
        assertEquals(d, repo.getBulkPreviousRows(listOf("W1", "W4"), d1)["W1"]?.date)
    }

    @Test
    fun correction_zeroForProductWithoutRow_writesRowAndCascades() = runBlocking {
        val all = addProductMissingFromBaseline()
        val plan = repo.planOpeningStockSave(
            d, all,
            mapOf("W1" to q(20), "W2" to q(30), "W3" to q(10), "W4" to q(0)),
            mapOf("W1" to q(5)), isNewBaseline = false
        )
        assertEquals(listOf("W4"), plan.map { it.after.productCode })
        val result = repo.applyOpeningStockSave(d, plan, all)

        val w4 = row(d, "W4")
        assertTrue(w4.isOpeningStock)
        assertArrayEquals(IntArray(4), BaselineMath.open(w4))
        assertArrayEquals(IntArray(4), BaselineMath.close(w4))
        val next = row(d1, "W4")
        assertEquals(0, next.openQq)
        assertEquals(40, next.closeQq)       // user's CB kept
        assertEquals(-40, next.saleQq)
        assertTrue(d1 in result.negativeSaleDates)

        assertTrue(repo.planOpeningStockSave(
            d, all, mapOf("W1" to q(20), "W2" to q(30), "W3" to q(10), "W4" to q(0)),
            mapOf("W1" to q(5)), false
        ).isEmpty())
    }

    @Test
    fun correction_productAddedAfterBaseline_canSetOb() = runBlocking {
        db.productDao().insertProducts(listOf(product("5")))
        val all = db.productDao().getAllProductsSync()
        assertArrayEquals(IntArray(4), repo.getOpeningFor("W5", d1))

        val plan = repo.planOpeningStockSave(d, all, mapOf("W5" to q(7)), mapOf("W5" to q(2)), false)
        assertEquals(1, plan.size)
        repo.applyOpeningStockSave(d, plan, all)
        val w5 = row(d, "W5")
        assertEquals(7, w5.openQq); assertEquals(9, w5.closeQq); assertEquals(0, w5.saleQq)
        assertArrayEquals(q(9), repo.getOpeningFor("W5", d1))
    }

    @Test
    fun picker_fillsProductsMissingFromBaseline() = runBlocking {
        val all = addProductMissingFromBaseline()
        val result = repo.setActiveBaseline(d, all, mapOf("W4" to q(3)))
        val w4 = row(d, "W4")
        assertTrue(w4.isOpeningStock)
        assertEquals(0, w4.openQq); assertEquals(3, w4.closeQq)
        assertEquals(3, row(d1, "W4").openQq)
        assertEquals(1, result.updatedCount)
        assertEquals(4, dao.getAllDailyStockForDate(d).size)

        assertEquals(0, repo.setActiveBaseline(d, all, emptyMap()).updatedCount)
        assertEquals(3, row(d, "W4").closeQq)
    }
}
