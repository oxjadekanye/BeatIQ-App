package com.beatiq.app.data.repository

import com.beatiq.app.data.model.PlaylistSummary
import com.beatiq.app.data.model.Song
import kotlinx.coroutines.flow.Flow

interface PlaylistRepository {
    fun observePlaylists(): Flow<List<PlaylistSummary>>

    fun observeSongsInPlaylist(playlistId: String): Flow<List<Song>>

    suspend fun createPlaylist(name: String): String

    suspend fun renamePlaylist(id: String, name: String)

    suspend fun deletePlaylist(id: String)

    suspend fun addSongToPlaylist(playlistId: String, songId: String)

    suspend fun removeSongFromPlaylist(playlistId: String, songId: String)
}
