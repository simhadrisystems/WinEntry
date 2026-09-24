package com.simhadri.winentry

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.simhadri.winentry.data.AppDatabase
import com.simhadri.winentry.data.entity.DailyStock
import com.simhadri.winentry.data.entity.DayReconciliation
import com.simhadri.winentry.data.entity.PendingCloudDelete
import com.simhadri.winentry.data.entity.Purchase
import com.simhadri.winentry.data.entity.SyncStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncSafetyTest {

    private lateinit var db: AppDatabase

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    private fun stock(date: String, code: String, cb: Int, status: String = SyncStatus.SYNCED) = DailyStock(
        date = date, productCode = code, openQq = cb, closeQq = cb, isCommitted = true, syncStatus = status)

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun markSynced_skipsRowEditedDuringUpload() = runBlocking {
        val dao = db.dailyStockDao()
        dao.upsertCommittedBatch(listOf(stock("2026-09-01", "W1", 5), stock("2026-09-01", "W2", 5)))
        val sent = dao.getPendingSyncStock()
        dao.upsertCommittedBatch(listOf(stock("2026-09-01", "W2", 9)))
        dao.markStockSyncedIfUnchanged(sent)
        assertEquals(SyncStatus.SYNCED, dao.getDailyStock("2026-09-01", "W1")!!.syncStatus)
        assertEquals(SyncStatus.PENDING_UPSERT, dao.getDailyStock("2026-09-01", "W2")!!.syncStatus)
    }

    @Test
    fun markSynced_purchaseAndSummary() = runBlocking {
        val pDao = db.purchaseDao()
        val id = pDao.insert(Purchase(productId = 1, productCode = "W1", productName = "W1", invoiceNumber = "A",
            purchaseDate = "2026-09-01", txnId = "t1", syncStatus = SyncStatus.PENDING_INSERT))
        val sent = pDao.getPendingSyncPurchases()
        pDao.update(pDao.getPurchaseById(id)!!.copy(notes = "edited", syncStatus = SyncStatus.PENDING_UPDATE,
            updatedAt = System.currentTimeMillis() + 1))
        pDao.markSyncedIfUnchanged(sent)
        assertEquals(SyncStatus.PENDING_UPDATE, pDao.getPurchaseById(id)!!.syncStatus)
        pDao.markSyncedIfUnchanged(pDao.getPendingSyncPurchases())
        assertEquals(SyncStatus.SYNCED, pDao.getPurchaseById(id)!!.syncStatus)

        val rDao = db.dayReconciliationDao()
        rDao.insertOrReplaceAll(listOf(DayReconciliation(date = "2026-09-01", upiReceipts = 1.0)))
        val sentR = rDao.getPendingSync()
        rDao.insertOrReplaceAll(listOf(DayReconciliation(date = "2026-09-01", upiReceipts = 2.0)))
        rDao.markSyncedIfUnchanged(sentR)
        assertEquals(SyncStatus.PENDING_UPSERT, rDao.getByDate("2026-09-01")!!.syncStatus)
    }

    @Test
    fun deletes_areQueuedAndRestoreSkipsThem() = runBlocking {
        val dao = db.dailyStockDao()
        val outbox = db.pendingCloudDeleteDao()
        dao.insertOrReplaceAll(listOf(stock("2026-09-01", "W1", 5), stock("2026-09-02", "W1", 5),
            stock("2026-09-02", "W2", 5)))
        dao.deleteDailyStock("2026-09-01", "W1")
        dao.deleteAllForDate("2026-09-02")
        dao.insertDraftIfAbsent(DailyStock(date = "2026-09-03", productCode = "W1", isCommitted = false))
        dao.deleteDailyStock("2026-09-03", "W1")
        val kinds = outbox.getAll().map { it.kind }.sorted()
        assertEquals(listOf(PendingCloudDelete.STOCK, PendingCloudDelete.STOCK_DATE), kinds)

        val kept = dao.mergeFromCloud(listOf(stock("2026-09-01", "W1", 5), stock("2026-09-02", "W2", 5),
            stock("2026-09-04", "W1", 7)))
        assertEquals(2, kept)
        assertNull(dao.getDailyStock("2026-09-01", "W1"))
        assertNull(dao.getDailyStock("2026-09-02", "W2"))
        assertEquals(7, dao.getDailyStock("2026-09-04", "W1")!!.closeQq)

        outbox.deleteAll(outbox.getAll())
        assertEquals(0, outbox.count())
    }

    @Test
    fun restoreMerge_keepsUnsyncedLocalRows() = runBlocking {
        val dao = db.dailyStockDao()
        dao.upsertCommittedBatch(listOf(stock("2026-09-05", "W1", 3)))
        dao.insertOrReplaceAll(listOf(stock("2026-09-05", "W2", 3)))
        val kept = dao.mergeFromCloud(listOf(stock("2026-09-05", "W1", 99), stock("2026-09-05", "W2", 99)))
        assertEquals(1, kept)
        assertEquals(3, dao.getDailyStock("2026-09-05", "W1")!!.closeQq)
        assertEquals(99, dao.getDailyStock("2026-09-05", "W2")!!.closeQq)

        val rDao = db.dayReconciliationDao()
        rDao.insertOrReplaceAll(listOf(DayReconciliation(date = "2026-09-05", upiReceipts = 1.0)))
        assertEquals(1, rDao.mergeFromCloud(listOf(DayReconciliation(date = "2026-09-05", upiReceipts = 5.0,
            syncStatus = SyncStatus.SYNCED))))
        assertEquals(1.0, rDao.getByDate("2026-09-05")!!.upiReceipts, 0.0)
    }

    @Test
    fun migrate6To7_keepsDataAndAddsOutbox() {
        val name = "migration-test"
        migrationHelper.createDatabase(name, 6).apply {
            execSQL("""INSERT INTO daily_stock (date, productCode, openQq, openPp, openNn, openDd,
                closeQq, closePp, closeNn, closeDd, saleQq, salePp, saleNn, saleDd,
                priceQq, pricePp, priceNn, priceDd, amountQq, amountPp, amountNn, amountDd,
                saleAmount, isCommitted, syncStatus, lastModified, isOpeningStock)
                VALUES ('2026-09-01','W1',5,0,0,0,4,0,0,0,1,0,0,0,10,0,0,0,10,0,0,0,10,1,'SYNCED',1,1)""")
            close()
        }
        val migrated = migrationHelper.runMigrationsAndValidate(name, 7, true, AppDatabase.MIGRATION_6_7)
        migrated.query("SELECT closeQq, isOpeningStock FROM daily_stock WHERE productCode = 'W1'").use {
            assertTrue(it.moveToFirst())
            assertEquals(4, it.getInt(0))
            assertEquals(1, it.getInt(1))
        }
        migrated.query("SELECT COUNT(*) FROM pending_cloud_deletes").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
    }
}
