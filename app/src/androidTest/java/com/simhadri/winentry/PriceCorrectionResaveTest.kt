package com.simhadri.winentry

import android.app.Application
import androidx.lifecycle.Observer
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.simhadri.winentry.data.AppDatabase
import com.simhadri.winentry.data.entity.DailyStock
import com.simhadri.winentry.data.entity.Product
import com.simhadri.winentry.data.entity.stockCode
import com.simhadri.winentry.ui.dailystock.DailyStockViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A master sale-price correction is applied to a committed day when the user re-enters
 * that product's closing (the saved amount and the day total both move to the new price).
 */
@RunWith(AndroidJUnit4::class)
class PriceCorrectionResaveTest {

    private val instr = InstrumentationRegistry.getInstrumentation()
    private lateinit var db: AppDatabase
    private var realInstance: Any? = null
    private val instanceField = AppDatabase::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }
    private val d = "2026-09-23"

    @Before
    fun setUp() {
        realInstance = instanceField.get(null)
        db = Room.inMemoryDatabaseBuilder(instr.targetContext, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        instanceField.set(null, db)
    }

    @After
    fun tearDown() {
        instanceField.set(null, realInstance)
        db.close()
    }

    @Test
    fun reenteredClosing_takesCorrectedMasterPrice() {
        val id = runBlocking {
            db.productDao().insertProducts(listOf(Product(
                productName = "OLD BLU", productType = "W", brandCode = "OB1",
                qqCode = "WOB1QQ", ppCode = "WOB1PP", nnCode = "WOB1NN", ddCode = "WOB1DD",
                qqSalePrice = 140.0, displayName = "OLD BLU")))
            val p = db.productDao().getAllProductsSync().first()
            db.dailyStockDao().upsertCommittedBatch(listOf(DailyStock(
                date = d, productCode = p.stockCode,
                openQq = 10, closeQq = 8, saleQq = 2,
                priceQq = 140.0, amountQq = 280.0, saleAmount = 280.0, isCommitted = true)))
            db.productDao().updateProduct(p.copy(qqSalePrice = 150.0))
            p.id
        }

        lateinit var vm: DailyStockViewModel
        val productsObserver = Observer<List<Product>> { if (it.isNotEmpty()) vm.initializeEntries(it) }
        instr.runOnMainSync {
            vm = DailyStockViewModel(instr.targetContext.applicationContext as Application)
            vm.setDate(d)
            vm.allProducts.observeForever(productsObserver)
        }
        waitUntil { vm.allEntriesForTotals().any { it.product.qqSalePrice == 150.0 } }
        assertEquals(280.0, vm.allEntriesForTotals().sumOf { it.saleAmount }, 0.001)

        instr.runOnMainSync {
            vm.updateClosing(id, "QQ", 8)
            vm.markProductDirty(id)
            vm.saveProductEntry(id)
        }
        waitUntil { vm.hasUnsavedChanges.value == false }
        instr.runOnMainSync { vm.allProducts.removeObserver(productsObserver) }

        val row = runBlocking { db.dailyStockDao().getAllDailyStockForDate(d) }.single()
        assertEquals(150.0, row.priceQq, 0.001)
        assertEquals(300.0, row.saleAmount, 0.001)
        assertEquals(300.0, vm.allEntriesForTotals().sumOf { it.saleAmount }, 0.001)
    }

    private fun waitUntil(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            Thread.sleep(50)
        }
    }
}
