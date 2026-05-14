package com.beatiq.app.data.repository

import com.beatiq.app.data.model.Song
import kotlinx.coroutines.flow.Flow

/**
 * Read/write facade for indexed library content (Room-backed in production).
 */
interface MusicRepository {
    fun observeAllSongs(): Flow<List<Song>>

    suspend fun getSongById(id: String): Song?

    suspend fun toggleFavorite(songId: String)

    suspend fun incrementPlayCount(songId: String)

    suspend fun markRecentlyPlayed(songId: String)
}
