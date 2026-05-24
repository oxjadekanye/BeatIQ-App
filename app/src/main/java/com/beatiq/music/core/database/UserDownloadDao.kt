package com.beatiq.music.core.database

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

    @Query("SELECT * FROM user_downloads WHERE status IN ('RUNNING', 'PENDING') ORDER BY createdAt DESC")
    suspend fun getIncomplete(): List<UserDownloadEntity>

    @Query("SELECT * FROM user_downloads ORDER BY createdAt DESC")
    suspend fun getAllOnce(): List<UserDownloadEntity>

    @Query("DELETE FROM user_downloads WHERE id = :id")
    suspend fun deleteById(id: String)
}
