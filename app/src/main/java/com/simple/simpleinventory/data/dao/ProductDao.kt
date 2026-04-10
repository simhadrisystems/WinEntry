package com.simple.simpleinventory.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.simple.simpleinventory.data.entity.Product

@Dao
interface ProductDao {
    @Query("SELECT * FROM products ORDER BY serialNo ASC, displayName ASC")
    fun getAllProductsBySerial(): LiveData<List<Product>>
    
    @Query("SELECT * FROM products ORDER BY displayName ASC")
    fun getAllProductsByName(): LiveData<List<Product>>
    
    @Query("SELECT * FROM products ORDER BY productType ASC, serialNo ASC, displayName ASC")
    fun getAllProductsByType(): LiveData<List<Product>>
    
    @Query("SELECT * FROM products ORDER BY displayName")
    fun getAllProducts(): LiveData<List<Product>>
    
    @Query("SELECT * FROM products WHERE isActive = 1 ORDER BY displayName")
    fun getActiveProducts(): LiveData<List<Product>>

    @Query("SELECT * FROM products WHERE isActive = 1 ORDER BY dailySortKey ASC, displayName ASC")
    fun getActiveProductsByDailySortKey(): LiveData<List<Product>>

    @Query("SELECT * FROM products WHERE isActive = 1 ORDER BY dailySortKey ASC, displayName ASC")
    suspend fun getActiveProductsByDailySortKeySync(): List<Product>

    @Query("SELECT * FROM products WHERE isActive = 0 ORDER BY displayName ASC")
    suspend fun getInactiveProductsSync(): List<Product>

    @Query("SELECT * FROM products WHERE isActive = 1 ORDER BY displayName")
    suspend fun getAllActiveProductsSync(): List<Product>
    
    /**
     * Get active products sorted by a specific key (LiveData version)
     * Used by Daily Stock module for UI observation
     */
    @Query("""
        SELECT * FROM products 
        WHERE isActive = 1 
        ORDER BY 
            CASE :sortKey
                WHEN 'serial' THEN serialNo
                WHEN 'type' THEN productType
                ELSE displayName
            END ASC,
            displayName ASC
    """)
    fun getActiveProductsBySortKey(sortKey: String): LiveData<List<Product>>
    
    /**
     * Get active products sorted by a specific key (Sync version)
     * Used for one-time queries without observation
     */
    @Query("""
        SELECT * FROM products 
        WHERE isActive = 1 
        ORDER BY 
            CASE :sortKey
                WHEN 'serial' THEN serialNo
                WHEN 'type' THEN productType
                ELSE displayName
            END ASC,
            displayName ASC
    """)
    suspend fun getActiveProductsBySortKeySync(sortKey: String): List<Product>
    
    @Query("SELECT * FROM products ORDER BY displayName")
    suspend fun getAllProductsSync(): List<Product>
    
    @Query("SELECT * FROM products WHERE id = :id")
    suspend fun getProductById(id: Long): Product?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProduct(product: Product): Long
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProducts(products: List<Product>)
    
    @Update
    suspend fun updateProduct(product: Product)

    @Update
    suspend fun updateProducts(products: List<Product>)
    
    @Delete
    suspend fun deleteProduct(product: Product)
    
    @Query("DELETE FROM products")
    suspend fun deleteAll()
}
