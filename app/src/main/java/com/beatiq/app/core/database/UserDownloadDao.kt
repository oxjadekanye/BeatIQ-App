package com.beatiq.app.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDownloadDao {
    @Query("SELECT * FROM user_downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<UserDownloadEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: UserDownloadEntity)

    @Update
    suspend fun update(entity: UserDownloadEntity)

    @Query("SELECT * FROM user_downloads WHERE downloadManagerId = :dmId LIMIT 1")
    suspend fun getByDownloadManagerId(dmId: Long): UserDownloadEntity?
}
