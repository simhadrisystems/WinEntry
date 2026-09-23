package com.simhadri.winentry

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.simhadri.winentry.data.AppDatabase
import com.simhadri.winentry.data.dao.PurchaseDao
import com.simhadri.winentry.data.entity.Purchase
import com.simhadri.winentry.data.entity.SyncStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PurchaseReplaceLineTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: PurchaseDao

    private fun line(txn: String = "", status: String = SyncStatus.PENDING_INSERT, qq: Int = 12) = Purchase(
        txnId = txn, syncStatus = status, purchaseDate = "2026-06-11", productId = 7,
        productCode = "W1249", productName = "TEST", invoiceNumber = "INV1", supplierName = "",
        qqTotalUnits = qq
    )

    private suspend fun all() = dao.getAllPurchasesSync()
    private suspend fun active() = all().filter { !it.isDeleted }

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        dao = db.purchaseDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun reimport_keepsSyncedTxnId_insteadOfAddingACloudCopy() = runBlocking {
        dao.insert(line("T-OLD", SyncStatus.SYNCED))
        dao.replaceLine(line(qq = 24))

        val rows = all()
        assertEquals(1, rows.size)
        assertEquals("T-OLD", rows[0].txnId)
        assertEquals(24, rows[0].qqTotalUnits)
        assertEquals(SyncStatus.PENDING_UPDATE, rows[0].syncStatus)
    }

    @Test
    fun cloudTxnId_wins_andOtherSyncedCopyIsTombstonedForCloudDelete() = runBlocking {
        dao.insert(line("T-A", SyncStatus.SYNCED))
        dao.replaceLine(line("T-B"))

        assertEquals(listOf("T-B"), active().map { it.txnId })
        val tomb = all().single { it.isDeleted }
        assertEquals("T-A", tomb.txnId)
        assertEquals(SyncStatus.PENDING_DELETE, tomb.syncStatus)
    }

    @Test
    fun unsyncedCopy_isDroppedWithoutTombstone() = runBlocking {
        dao.insert(line("T-NEW", SyncStatus.PENDING_INSERT))
        dao.replaceLine(line("T-B"))

        assertEquals(listOf("T-B"), all().map { it.txnId })
    }

    @Test
    fun restoreRow_staysSynced_andCancelsItsOwnTombstone() = runBlocking {
        dao.insert(line("T-A", SyncStatus.PENDING_DELETE).copy(isDeleted = true))
        dao.replaceLine(line("T-A", SyncStatus.SYNCED))

        val rows = all()
        assertEquals(1, rows.size)
        assertEquals(SyncStatus.SYNCED, rows[0].syncStatus)
        assertEquals(false, rows[0].isDeleted)
    }

    @Test
    fun brandNewLine_getsFreshTxnId() = runBlocking {
        dao.replaceLine(line())
        val row = all().single()
        assertEquals(true, row.txnId.matches(Regex("""\d{8}-\d{6}-[0-9A-F]{4}""")))
        assertEquals(SyncStatus.PENDING_INSERT, row.syncStatus)
    }
}
