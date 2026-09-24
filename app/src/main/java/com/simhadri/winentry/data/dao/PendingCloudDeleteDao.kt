package com.simhadri.winentry.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.simhadri.winentry.data.entity.PendingCloudDelete

@Dao
interface PendingCloudDeleteDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<PendingCloudDelete>)

    @Query("SELECT * FROM pending_cloud_deletes ORDER BY createdAt ASC")
    suspend fun getAll(): List<PendingCloudDelete>

    @Query("SELECT COUNT(*) FROM pending_cloud_deletes")
    suspend fun count(): Int

    @Delete
    suspend fun deleteAll(rows: List<PendingCloudDelete>)

    @Query("DELETE FROM pending_cloud_deletes")
    suspend fun clear()
}
