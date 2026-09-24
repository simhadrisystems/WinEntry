package com.simhadri.winentry

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.simhadri.winentry.data.AppDatabase
import com.simhadri.winentry.data.entity.DailyStock
import com.simhadri.winentry.data.entity.DayReconciliation
import com.simhadri.winentry.data.entity.Product
import com.simhadri.winentry.data.entity.Purchase
import com.simhadri.winentry.data.entity.SyncStatus
import com.simhadri.winentry.data.repository.BaselineMath
import com.simhadri.winentry.data.repository.CommittedSaleRefresher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IntegrityScenarioTest {

    private lateinit var db: AppDatabase
    private lateinit var refresher: CommittedSaleRefresher
    private var w1Id = 0L

    private val d = "2026-09-10"
    private val d1 = "2026-09-11"

    private fun product(code: String) = Product(
        productName = code, productType = "W", brandCode = code,
        qqCode = "W${code}QQ", ppCode = "W${code}PP", nnCode = "W${code}NN", ddCode = "W${code}DD",
        qqSalePrice = 10.0, ppSalePrice = 10.0, nnSalePrice = 10.0, ddSalePrice = 10.0,
        displayName = code
    )

    private fun purchase(date: String, qq: Int, received: String = "") = Purchase(
        purchaseDate = date, receivedDate = received, productId = w1Id, productCode = "W1",
        productName = "1", invoiceNumber = "INV-$date", qqTotalUnits = qq, txnId = "t-$date")

    private fun committed(date: String, ob: Int, pq: Int, cb: Int) = DailyStock(
        date = date, productCode = "W1", openQq = ob, closeQq = cb, saleQq = ob + pq - cb,
        priceQq = 10.0, amountQq = (ob + pq - cb) * 10.0, saleAmount = (ob + pq - cb) * 10.0,
        isCommitted = true)

    @Before
    fun setUp() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        db.productDao().insertProducts(listOf(product("1")))
        w1Id = db.productDao().getAllProductsSync().first().id
        refresher = CommittedSaleRefresher(db)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun purchaseDeletedAfterCommit_recomputesSaleAndDayTotal() = runBlocking {
        val id = db.purchaseDao().insert(purchase(d, 6))
        db.dailyStockDao().upsertCommittedBatch(listOf(committed(d, 10, 6, 12)))
        db.dayReconciliationDao().insertOrReplaceAll(listOf(DayReconciliation(
            date = d, totalDaySales = 40.0, upiReceipts = 10.0, cashForDeposit = 30.0,
            syncStatus = SyncStatus.SYNCED)))

        val p = db.purchaseDao().getPurchaseById(id)!!
        db.purchaseDao().update(p.copy(isDeleted = true))
        val negatives = refresher.refresh(listOf(d))

        val row = db.dailyStockDao().getDailyStock(d, "W1")!!
        assertEquals(12, row.closeQq)
        assertEquals(-2, row.saleQq)
        assertEquals(-20.0, row.saleAmount, 0.0)
        assertEquals(setOf(d), negatives)
        val rec = db.dayReconciliationDao().getByDate(d)!!
        assertEquals(-20.0, rec.totalDaySales, 0.0)
        assertEquals(-30.0, rec.cashForDeposit, 0.0)
        assertEquals(SyncStatus.PENDING_UPSERT, rec.syncStatus)
    }

    @Test
    fun purchaseAddedAfterCommit_increasesSaleAtStoredPrice() = runBlocking {
        db.dailyStockDao().upsertCommittedBatch(listOf(committed(d, 10, 0, 7).copy(priceQq = 8.0)))
        db.purchaseDao().insert(purchase(d, 5))
        refresher.refresh(listOf(d))
        val row = db.dailyStockDao().getDailyStock(d, "W1")!!
        assertEquals(8, row.saleQq)
        assertEquals(64.0, row.amountQq, 0.0)
    }

    @Test
    fun receivedDateMove_updatesBothDays() = runBlocking {
        db.purchaseDao().insert(purchase(d, 4))
        db.dailyStockDao().upsertCommittedBatch(listOf(committed(d, 10, 4, 10), committed(d1, 10, 0, 10)))
        val p = db.purchaseDao().getActiveByInvoice("INV-$d", d).first()
        db.purchaseDao().update(p.copy(receivedDate = d1))
        refresher.refresh(listOf(d, d1))
        assertEquals(0, db.dailyStockDao().getDailyStock(d, "W1")!!.saleQq)
        assertEquals(4, db.dailyStockDao().getDailyStock(d1, "W1")!!.saleQq)
    }

    @Test
    fun baselineWithoutClosing_movesCbAndCascades() = runBlocking {
        db.dailyStockDao().upsertCommittedBatch(listOf(
            BaselineMath.newBaselineRow(d, "W1", intArrayOf(10, 0, 0, 0), IntArray(4))))
        db.dailyStockDao().upsertCommittedBatch(listOf(committed(d1, 10, 0, 8)))
        db.purchaseDao().insert(purchase(d, 5))
        refresher.refresh(listOf(d))
        val base = db.dailyStockDao().getDailyStock(d, "W1")!!
        assertEquals(15, base.closeQq)
        assertEquals(0, base.saleQq)
        assertTrue(base.isOpeningStock)
        val next = db.dailyStockDao().getDailyStock(d1, "W1")!!
        assertEquals(15, next.openQq)
        assertEquals(7, next.saleQq)
    }
}
