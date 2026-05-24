package com.beatiq.music.data.repository

import com.beatiq.music.core.database.PlaylistDao
import com.beatiq.music.core.database.PlaylistEntity
import com.beatiq.music.core.database.SongDao
import com.beatiq.music.core.database.toSong
import com.beatiq.music.data.model.PlaylistSummary
import com.beatiq.music.data.model.Song
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class RoomPlaylistRepository(
    private val dao: PlaylistDao,
    private val songDao: SongDao,
) : PlaylistRepository {

    override fun observePlaylists(): Flow<List<PlaylistSummary>> =
        dao.observePlaylistSummaries().map { rows ->
            rows.map {
                PlaylistSummary(
                    id = it.id,
                    name = it.name,
                    createdAt = it.createdAt,
                    updatedAt = it.updatedAt,
                    trackCount = it.trackCount,
                )
            }
        }

    override fun observeSongsInPlaylist(playlistId: String): Flow<List<Song>> =
        dao.observeSongsInPlaylist(playlistId).map { list -> list.map { it.toSong() } }

    override suspend fun createPlaylist(name: String): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        dao.insertPlaylist(
            PlaylistEntity(
                id = id,
                name = name.trim().ifBlank { "Playlist" },
                createdAt = now,
                updatedAt = now,
            ),
        )
        return id
    }

    override suspend fun renamePlaylist(id: String, name: String) {
        val now = System.currentTimeMillis()
        dao.updatePlaylistMeta(id, name.trim().ifBlank { "Playlist" }, now)
    }

    override suspend fun deletePlaylist(id: String) {
        dao.deletePlaylist(id)
    }

    override suspend fun addSongToPlaylist(playlistId: String, songId: String): Boolean =
        withContext(Dispatchers.IO) {
            if (dao.getPlaylist(playlistId) == null) return@withContext false
            if (songDao.getById(songId) == null) return@withContext false
            runCatching {
                dao.addSongToPlaylist(playlistId, songId, System.currentTimeMillis())
            }.isSuccess
        }

    override suspend fun removeSongFromPlaylist(playlistId: String, songId: String) {
        withContext(Dispatchers.IO) {
            dao.removeSongFromPlaylist(playlistId, songId, System.currentTimeMillis())
        }
    }
}
