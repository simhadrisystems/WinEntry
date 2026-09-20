package com.simhadri.winentry.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.simhadri.winentry.data.dao.DailyStockDao
import com.simhadri.winentry.data.dao.DayReconciliationDao
import com.simhadri.winentry.data.dao.ProductDao
import com.simhadri.winentry.data.dao.PurchaseDao
import com.simhadri.winentry.data.entity.DailyStock
import com.simhadri.winentry.data.entity.DayReconciliation
import com.simhadri.winentry.data.entity.Product
import com.simhadri.winentry.data.entity.Purchase

/**
 * Single Room database for the app — version 2, stable baseline.
 *
 * Version history:
 *   v1 — initial schema (products, purchases, daily_stock size-level, day_reconciliation)
 *   v2 — daily_stock redesigned: one row per PRODUCT per date (was one per size-code).
 *        All prior incremental migrations collapsed into this baseline.
 *   v3 — products table: removed unused columns quantity, reorderLevel, createdAt.
 *   v4 → v5: add receivedDate column to purchases.
 *   v5 → v6: add isOpeningStock column to daily_stock.
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
    version = 6,
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
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .build()
                .also { INSTANCE = it }
            }

        /**
         * v5 → v6: add isOpeningStock marker column to daily_stock, replacing the
         * open==close/sale==0 heuristic used previously to detect opening-stock rows.
         * Backfill uses that same heuristic (row-level: open>0, sale=0) so existing
         * opening stock keeps working — same false-positive risk as before, not
         * worsened. Every row saved going forward sets the flag explicitly.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE daily_stock ADD COLUMN isOpeningStock INTEGER NOT NULL DEFAULT 0")
                db.execSQL("""
                    UPDATE daily_stock SET isOpeningStock = 1, syncStatus = 'PENDING_UPSERT'
                    WHERE isCommitted = 1
                      AND (openQq+openPp+openNn+openDd) > 0
                      AND (saleQq+salePp+saleNn+saleDd) = 0
                """)
            }
        }

        /**
         * v4 → v5: add receivedDate column to purchases.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE purchases ADD COLUMN receivedDate TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * v3 → v4: add deposits column to day_reconciliation.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE day_reconciliation ADD COLUMN deposits REAL NOT NULL DEFAULT 0.0"
                )
            }
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
