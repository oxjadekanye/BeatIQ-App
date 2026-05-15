package com.beatiq.music.data.repository

import com.beatiq.music.core.database.SongDao
import com.beatiq.music.core.database.toSong
import com.beatiq.music.data.model.Song
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomMusicRepository(
    private val dao: SongDao,
) : MusicRepository {

    override fun observeAllSongs(): Flow<List<Song>> =
        dao.observeAll().map { entities -> entities.map { it.toSong() } }

    override fun observeRecentlyAdded(limit: Int): Flow<List<Song>> =
        dao.observeRecentlyAdded(limit).map { entities -> entities.map { it.toSong() } }

    override fun observeRecentlyPlayed(limit: Int): Flow<List<Song>> =
        dao.observeRecentlyPlayed(limit).map { entities -> entities.map { it.toSong() } }

    override fun observeMostPlayed(limit: Int): Flow<List<Song>> =
        dao.observeMostPlayed(limit).map { entities -> entities.map { it.toSong() } }

    override fun observeFavoriteSongs(): Flow<List<Song>> =
        dao.observeFavorites().map { entities -> entities.map { it.toSong() } }

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
