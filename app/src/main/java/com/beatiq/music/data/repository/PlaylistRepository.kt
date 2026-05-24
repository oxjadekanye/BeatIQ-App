package com.beatiq.music.data.repository

import com.beatiq.music.data.model.PlaylistSummary
import com.beatiq.music.data.model.Song
import kotlinx.coroutines.flow.Flow

interface PlaylistRepository {
    fun observePlaylists(): Flow<List<PlaylistSummary>>

    fun observeSongsInPlaylist(playlistId: String): Flow<List<Song>>

    suspend fun createPlaylist(name: String): String

    suspend fun renamePlaylist(id: String, name: String)

    suspend fun deletePlaylist(id: String)

    /** @return true when the song link was saved to the playlist. */
    suspend fun addSongToPlaylist(playlistId: String, songId: String): Boolean

    suspend fun removeSongFromPlaylist(playlistId: String, songId: String)
}
