package com.simple.simpleinventory.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.simple.simpleinventory.data.dao.DailyStockDao
import com.simple.simpleinventory.data.dao.DayReconciliationDao
import com.simple.simpleinventory.data.dao.ProductDao
import com.simple.simpleinventory.data.dao.PurchaseDao
import com.simple.simpleinventory.data.entity.DailyStock
import com.simple.simpleinventory.data.entity.DayReconciliation
import com.simple.simpleinventory.data.entity.Product
import com.simple.simpleinventory.data.entity.Purchase

/**
 * Single Room database for the app — version 2, stable baseline.
 *
 * Version history:
 *   v1 — initial schema (products, purchases, daily_stock size-level, day_reconciliation)
 *   v2 — daily_stock redesigned: one row per PRODUCT per date (was one per size-code).
 *        All prior incremental migrations collapsed into this baseline.
 *   v3 — products table: removed unused columns quantity, reorderLevel, createdAt.
 *
 * Any future structural change must:
 *   1. Increment version (e.g. version = 3)
 *   2. Write an explicit Migration(2, 3) using ALTER TABLE / CREATE TABLE
 *   3. Add it to .addMigrations(...) in getInstance()
 *
 * fallbackToDestructiveMigration() is intentionally absent.
 * Room will throw an exception on any unhandled version mismatch —
 * this is correct; silent data loss is never acceptable.
 *
 * ── Table summary ─────────────────────────────────────────────────────────
 * products          — product master (brand, sizes, prices, sort order)
 * purchases         — purchase transactions with full invoice detail
 * daily_stock       — one committed row per product per date; all 4 sizes
 *                     inline with opening/closing/sale/price/amount snapshots
 * day_reconciliation— day-end UPI, expenses, cash deposit, total sales snapshot
 */
@Database(
    entities = [
        Product::class,
        Purchase::class,
        DailyStock::class,
        DayReconciliation::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun productDao(): ProductDao
    abstract fun purchaseDao(): PurchaseDao
    abstract fun dailyStockDao(): DailyStockDao
    abstract fun dayReconciliationDao(): DayReconciliationDao

    companion object {

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "inventory_database"
                )
                // Fresh installs with a v1 database (previous test install)
                // are allowed to migrate destructively — v1→v2 was a full
                // daily_stock schema redesign; no v1 data is worth preserving
                // since all real data lives in Google Sheets.
                // v2 → future versions must use explicit addMigrations().
                .fallbackToDestructiveMigrationFrom(1)
                .addMigrations(MIGRATION_2_3)
                .build()
                .also { INSTANCE = it }
            }

        /**
         * v2 → v3: drop unused columns quantity, reorderLevel, createdAt from products.
         * SQLite < 3.35 has no DROP COLUMN, so we recreate the table.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `products_new` (
                        `id`               INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `productName`      TEXT    NOT NULL,
                        `productType`      TEXT    NOT NULL,
                        `category`         TEXT    NOT NULL,
                        `brandCode`        TEXT    NOT NULL,
                        `qqCode`           TEXT    NOT NULL,
                        `ppCode`           TEXT    NOT NULL,
                        `nnCode`           TEXT    NOT NULL,
                        `ddCode`           TEXT    NOT NULL,
                        `qqPurchasePrice`  REAL    NOT NULL,
                        `qqSalePrice`      REAL    NOT NULL,
                        `ppPurchasePrice`  REAL    NOT NULL,
                        `ppSalePrice`      REAL    NOT NULL,
                        `nnPurchasePrice`  REAL    NOT NULL,
                        `nnSalePrice`      REAL    NOT NULL,
                        `ddPurchasePrice`  REAL    NOT NULL,
                        `ddSalePrice`      REAL    NOT NULL,
                        `qqUnitsPerBox`    INTEGER NOT NULL,
                        `ppUnitsPerBox`    INTEGER NOT NULL,
                        `nnUnitsPerBox`    INTEGER NOT NULL,
                        `ddUnitsPerBox`    INTEGER NOT NULL,
                        `displayName`      TEXT    NOT NULL,
                        `serialNo`         INTEGER NOT NULL,
                        `isActive`         INTEGER NOT NULL,
                        `dailySortKey`     INTEGER NOT NULL,
                        `aliases`          TEXT    NOT NULL
                    )
                """)
                db.execSQL("""
                    INSERT INTO `products_new`
                        (id, productName, productType, category, brandCode,
                         qqCode, ppCode, nnCode, ddCode,
                         qqPurchasePrice, qqSalePrice, ppPurchasePrice, ppSalePrice,
                         nnPurchasePrice, nnSalePrice, ddPurchasePrice, ddSalePrice,
                         qqUnitsPerBox, ppUnitsPerBox, nnUnitsPerBox, ddUnitsPerBox,
                         displayName, serialNo, isActive, dailySortKey, aliases)
                    SELECT  id, productName, productType, category, brandCode,
                            qqCode, ppCode, nnCode, ddCode,
                            qqPurchasePrice, qqSalePrice, ppPurchasePrice, ppSalePrice,
                            nnPurchasePrice, nnSalePrice, ddPurchasePrice, ddSalePrice,
                            qqUnitsPerBox, ppUnitsPerBox, nnUnitsPerBox, ddUnitsPerBox,
                            displayName, serialNo, isActive, dailySortKey, aliases
                    FROM `products`
                """)
                db.execSQL("DROP TABLE `products`")
                db.execSQL("ALTER TABLE `products_new` RENAME TO `products`")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_products_brandCode` ON `products` (`brandCode`)"
                )
            }
        }
    }
}
