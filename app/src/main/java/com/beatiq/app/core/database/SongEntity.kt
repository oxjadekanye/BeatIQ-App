package com.beatiq.app.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persisted library row. Mirrors [com.beatiq.app.data.model.Song] with extra analytics fields.
 *
 * TODO(Phase-3): Add FTS table for AI-powered semantic search over titles/lyrics snippets.
 */
@Entity(
    tableName = "songs",
    indices = [
        Index(value = ["mediaStoreId"], unique = true),
        Index(value = ["dateAdded"]),
    ],
)
data class SongEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "mediaStoreId") val mediaStoreId: Long,
    val title: String,
    val artist: String,
    val album: String,
    val genre: String,
    val durationMs: Long,
    /** Content Uri or legacy file path string used to build [androidx.media3.common.MediaItem]. */
    val filePath: String,
    val artworkUri: String?,
    val dateAdded: Long,
    val playCount: Int,
    val isFavorite: Boolean,
    @ColumnInfo(name = "recentlyPlayedAt") val recentlyPlayedAt: Long?,
)
