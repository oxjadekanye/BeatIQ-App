package com.beatiq.app.data.repository

import com.beatiq.app.core.database.SongDao
import com.beatiq.app.core.database.toSong
import com.beatiq.app.data.model.Song
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.map

class RoomMusicRepository(
    private val dao: SongDao,
) : MusicRepository {

    override fun observeAllSongs(): Flow<List<Song>> =
        dao.observeAll().map { entities -> entities.map { it.toSong() } }

    override suspend fun getSongById(id: String): Song? =
        dao.getById(id)?.toSong()

    override suspend fun toggleFavorite(songId: String) {
        val current = dao.getById(songId) ?: return
        dao.setFavorite(songId, !current.isFavorite)
    }

    override suspend fun incrementPlayCount(songId: String) {
        dao.incrementPlayCount(songId)
    }

    override suspend fun markRecentlyPlayed(songId: String) {
        dao.updateRecentlyPlayed(songId, System.currentTimeMillis())
    }
}
