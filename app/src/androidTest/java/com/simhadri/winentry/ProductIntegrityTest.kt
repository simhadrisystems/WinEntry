package com.simhadri.winentry

import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.simhadri.winentry.data.AppDatabase
import com.simhadri.winentry.data.entity.Product
import com.simhadri.winentry.data.repository.DailyStockRepository
import com.simhadri.winentry.utils.ExcelHelper
import kotlinx.coroutines.runBlocking
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ProductIntegrityTest {

    private lateinit var db: AppDatabase
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun product(code: String, sort: Int = 5) = Product(
        productName = "P$code", productType = "W", brandCode = code,
        qqCode = "W${code}QQ", ppCode = "W${code}PP", nnCode = "W${code}NN", ddCode = "W${code}DD",
        qqSalePrice = 100.0, displayName = "P$code", dailySortKey = sort, isActive = true
    )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    /** Name, Type, Category, Brand, then blank cells except QQ sale price (col 9); rows 1 and 3, row 2 blank. */
    private fun sheet(vararg rows: Pair<Int, List<Any?>>): Uri {
        val wb = XSSFWorkbook()
        val sh = wb.createSheet()
        sh.createRow(0).createCell(0).setCellValue("Product Name")
        for ((r, cells) in rows) {
            val row = sh.createRow(r)
            cells.forEachIndexed { i, v ->
                when (v) {
                    is String -> row.createCell(i).setCellValue(v)
                    is Double -> row.createCell(i).setCellValue(v)
                }
            }
        }
        val f = File(ctx.cacheDir, "products-test.xlsx")
        f.outputStream().use { wb.write(it) }
        return Uri.fromFile(f)
    }

    @Test
    fun reimport_keepsIdsTypeAndBlankValues() = runBlocking {
        db.productDao().insertProducts(listOf(product("1249", sort = 7), product("300", sort = 3)))
        val before = db.productDao().getAllProductsSync().associateBy { it.brandCode }

        val uri = sheet(
            1 to listOf("P1249", "B", "", "1249", null, null, null, null, null, 120.0),
            3 to listOf("P300", "W", "", "300", null, null, null, null, null, 90.0)
        )
        val result = ExcelHelper(ctx).importProducts(uri, before.values.toList())
        db.productDao().insertProducts(result.products)
        val after = db.productDao().getAllProductsSync().associateBy { it.brandCode }

        assertEquals(2, result.products.size)
        assertEquals(before["1249"]!!.id, after["1249"]!!.id)
        assertEquals(before["300"]!!.id, after["300"]!!.id)
        assertEquals("W", after["1249"]!!.productType)
        assertTrue(result.notes.single().contains("type kept"))
        assertEquals(120.0, after["1249"]!!.qqSalePrice, 0.0)
        assertEquals(7, after["1249"]!!.dailySortKey)
        assertTrue(after["1249"]!!.isActive)
    }

    @Test
    fun sortOrderSave_keepsPriceEditedMeanwhile() = runBlocking {
        db.productDao().insertProducts(listOf(product("1")))
        val stale = db.productDao().getAllProductsSync().single()
        db.productDao().updateProduct(stale.copy(qqSalePrice = 150.0))
        DailyStockRepository(db.productDao(), db.dailyStockDao()).updateSortKeys(listOf(stale.copy(dailySortKey = 1)))
        val now = db.productDao().getAllProductsSync().single()
        assertEquals(150.0, now.qqSalePrice, 0.0)
        assertEquals(1, now.dailySortKey)
    }
}
