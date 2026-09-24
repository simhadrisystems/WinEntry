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
import com.simhadri.winentry.data.repository.DailyStockRepository
import com.simhadri.winentry.data.repository.IntegrityChecker
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
    private val d2 = "2026-09-12"
    private val dm1 = "2026-09-09"

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

    private fun repo() = DailyStockRepository(db.productDao(), db.dailyStockDao())
    private fun q(n: Int) = intArrayOf(n, 0, 0, 0)

    @Test
    fun clearEntry_nextDayOpeningKeepsThatDaysPurchases() = runBlocking {
        val products = db.productDao().getAllProductsSync()
        db.dailyStockDao().upsertCommittedBatch(listOf(
            committed(dm1, 10, 0, 10), committed(d, 10, 5, 12), committed(d1, 12, 0, 9)))
        val result = repo().clearEntryWithCascade(d, "W1", products, q(5))
        val next = db.dailyStockDao().getDailyStock(d1, "W1")!!
        assertEquals(15, next.openQq)
        assertEquals(6, next.saleQq)
        assertTrue(result.negativeSaleDates.isEmpty())
        assertEquals(1, db.pendingCloudDeleteDao().count())
    }

    @Test
    fun clearDate_cascadesToNextDay() = runBlocking {
        db.dailyStockDao().upsertCommittedBatch(listOf(
            committed(dm1, 10, 0, 10), committed(d, 10, 0, 4), committed(d1, 4, 0, 4)))
        repo().clearDateData(d, mapOf("W1" to q(2)))
        val next = db.dailyStockDao().getDailyStock(d1, "W1")!!
        assertEquals(12, next.openQq)
        assertEquals(8, next.saleQq)
    }

    @Test
    fun clearNextDay_updatesDayAfter() = runBlocking {
        val products = db.productDao().getAllProductsSync()
        db.dailyStockDao().upsertCommittedBatch(listOf(
            committed(d, 10, 0, 8), committed(d1, 8, 3, 6), committed(d2, 6, 0, 6)))
        val today = db.dailyStockDao().getDailyStock(d, "W1")!!
        repo().clearNextDayWithCascade(listOf(today), products) { mapOf("W1" to q(3)) }
        assertEquals(null, db.dailyStockDao().getDailyStock(d1, "W1"))
        val after = db.dailyStockDao().getDailyStock(d2, "W1")!!
        assertEquals(11, after.openQq)
        assertEquals(5, after.saleQq)
    }

    @Test
    fun integrityCheck_findsAndRepairs() = runBlocking {
        db.dailyStockDao().upsertCommittedBatch(listOf(BaselineMath.newBaselineRow(dm1, "W1", q(10), IntArray(4))))
        // Stale sale: committed with sale 4, but 5 more were purchased that day
        db.dailyStockDao().upsertCommittedBatch(listOf(committed(d, 10, 0, 6)))
        db.purchaseDao().insert(purchase(d, 5).copy(txnId = "", invoiceNumber = "A"))
        // Duplicate line: same product, invoice and date twice
        db.purchaseDao().insert(purchase(d1, 2).copy(txnId = "t1", invoiceNumber = "B", syncStatus = SyncStatus.SYNCED))
        db.purchaseDao().insert(purchase(d1, 2).copy(txnId = "t2", invoiceNumber = "B", syncStatus = SyncStatus.SYNCED))
        // Opening mismatch: d1 opens at 9 but d closed at 6
        db.dailyStockDao().upsertCommittedBatch(listOf(committed(d1, 9, 2, 9)))

        val checker = IntegrityChecker(db)
        val report = checker.check()
        assertTrue(d in report.staleSaleDates)
        assertEquals(1, report.blankTxnIds.size)
        assertEquals(1, report.duplicateLines.size)
        assertEquals(listOf("W1 on $d1"), report.openingMismatches)

        val after = checker.repair(report)
        assertTrue(after.staleSaleDates.isEmpty())
        assertTrue(after.blankTxnIds.isEmpty())
        assertTrue(after.duplicateLines.isEmpty())
        assertEquals(9, db.dailyStockDao().getDailyStock(d, "W1")!!.saleQq)
        assertEquals(1, db.purchaseDao().getActiveByInvoice("B", d1).size)
        // The tombstoned copy is queued so the cloud copy is deleted too
        assertTrue(db.purchaseDao().getPendingSyncPurchases().any { it.isDeleted })
        assertEquals(listOf("W1 on $d1"), after.openingMismatches)
    }
}
