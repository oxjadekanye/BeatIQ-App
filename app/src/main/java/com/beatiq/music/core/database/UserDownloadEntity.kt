package com.beatiq.music.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "user_downloads",
    indices = [Index(value = ["downloadManagerId"], unique = false)],
)
data class UserDownloadEntity(
    @PrimaryKey val id: String,
    val sourceUrl: String,
    val displayTitle: String,
    /** PENDING | RUNNING | COMPLETED | FAILED */
    val status: String,
    @ColumnInfo(name = "downloadManagerId") val downloadManagerId: Long?,
    val createdAt: Long,
    val completedAt: Long?,
    @ColumnInfo(name = "localFilePath") val localFilePath: String?,
)
