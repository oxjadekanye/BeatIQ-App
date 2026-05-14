package com.beatiq.app.features.library

import com.beatiq.app.data.model.PlaybackUiState
import com.beatiq.app.data.model.Song
import com.beatiq.app.data.repository.MockMusicLibraryStore
import com.beatiq.app.data.repository.MusicRepository
import com.beatiq.app.data.repository.PlayerRepository
import com.beatiq.app.data.repository.ScannerRepository
import com.beatiq.app.features.scanner.MusicScanner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/**
 * Phase 1 mock implementations retained for previews/tests — not wired in production builds.
 */
internal class MockMusicRepository(
    private val store: MockMusicLibraryStore,
) : MusicRepository {
    override fun observeAllSongs(): Flow<List<Song>> = store.observe()

    override fun observeRecentlyAdded(limit: Int): Flow<List<Song>> =
        store.observe().map { list -> list.sortedByDescending { it.dateAdded }.take(limit) }

    override fun observeRecentlyPlayed(limit: Int): Flow<List<Song>> =
        store.observe().map { emptyList() }

    override fun observeMostPlayed(limit: Int): Flow<List<Song>> =
        store.observe().map { list -> list.sortedByDescending { it.playCount }.take(limit) }

    override fun observeFavoriteSongs(): Flow<List<Song>> =
        store.observe().map { list -> list.filter { it.isFavorite }.sortedBy { it.title } }

    override suspend fun getSongById(id: String): Song? = store.snapshot().firstOrNull { it.id == id }

    override suspend fun toggleFavorite(songId: String) {
        val updated = store.snapshot().map { song ->
            if (song.id == songId) song.copy(isFavorite = !song.isFavorite) else song
        }
        store.replaceAll(updated)
    }

    override suspend fun incrementPlayCount(songId: String) {
        val updated = store.snapshot().map { song ->
            if (song.id == songId) song.copy(playCount = song.playCount + 1) else song
        }
        store.replaceAll(updated)
    }

    override suspend fun markRecentlyPlayed(songId: String) {
        // No-op for mock store — Room path persists timestamps.
    }
}

internal class MockScannerRepository(
    private val store: MockMusicLibraryStore,
    private val scanner: MusicScanner,
) : ScannerRepository {
    override suspend fun scanLibrary(): List<Song> {
        val results = scanner.scanMockSongs()
        store.replaceAll(results)
        return store.snapshot()
    }
}

internal class MockPlayerRepository : PlayerRepository {
    private val _playbackUiState = MutableStateFlow(PlaybackUiState())
    override val playbackUiState = _playbackUiState.asStateFlow()

    override suspend fun playQueue(songs: List<Song>, startIndex: Int) {}
    override suspend fun playSongWithQueue(song: Song, queue: List<Song>) {}
    override suspend fun pause() {}
    override suspend fun resume() {}
    override suspend fun seekTo(positionMs: Long) {}
    override suspend fun skipToNext() {}
    override suspend fun skipToPrevious() {}
    override suspend fun setShuffle(enabled: Boolean) {}
    override suspend fun setRepeatMode(repeatMode: Int) {}
    override suspend fun toggleFavoriteCurrent() {}
    override suspend fun preparePlaceholder(songId: String) {}
    override suspend fun clearPlaceholder() {}
}
