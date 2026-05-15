package com.beatiq.music.data.repository

import com.beatiq.music.data.model.PlaybackUiState
import com.beatiq.music.data.model.Song
import kotlinx.coroutines.flow.StateFlow

/**
 * Playback control surface backed by Media3 in production.
 */
interface PlayerRepository {
    val playbackUiState: StateFlow<PlaybackUiState>

    suspend fun playQueue(songs: List<Song>, startIndex: Int = 0)

    suspend fun playSongWithQueue(song: Song, queue: List<Song>)

    suspend fun pause()

    suspend fun resume()

    suspend fun seekTo(positionMs: Long)

    suspend fun skipToNext()

    suspend fun skipToPrevious()

    suspend fun setShuffle(enabled: Boolean)

    /** @param repeatMode androidx.media3.common.Player repeat constant. */
    suspend fun setRepeatMode(repeatMode: Int)

    suspend fun toggleFavoriteCurrent()

    /** @deprecated Phase-1 hook — routes to [playSongWithQueue] when possible. */
    @Deprecated("Use playSongWithQueue", ReplaceWith("playSongWithQueue(...)"))
    suspend fun preparePlaceholder(songId: String)

    @Deprecated("No-op in Media3 path", ReplaceWith("pause()"))
    suspend fun clearPlaceholder()
}
