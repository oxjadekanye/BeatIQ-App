package com.beatiq.music.data.repository

import com.beatiq.music.data.model.Song
import kotlinx.coroutines.flow.Flow

/**
 * Read/write facade for indexed library content (Room-backed in production).
 */
interface MusicRepository {
    fun observeAllSongs(): Flow<List<Song>>

    fun observeRecentlyAdded(limit: Int = 40): Flow<List<Song>>

    fun observeRecentlyPlayed(limit: Int = 40): Flow<List<Song>>

    fun observeMostPlayed(limit: Int = 40): Flow<List<Song>>

    fun observeFavoriteSongs(): Flow<List<Song>>

    suspend fun getSongById(id: String): Song?

    suspend fun toggleFavorite(songId: String)

    suspend fun incrementPlayCount(songId: String)

    suspend fun markRecentlyPlayed(songId: String)
}
