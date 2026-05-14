package com.beatiq.app.data.repository

import com.beatiq.app.core.player.PlaybackController
import com.beatiq.app.data.model.PlaybackUiState
import com.beatiq.app.data.model.Song
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

class Media3PlayerRepository(
    private val controller: PlaybackController,
    private val musicRepository: MusicRepository,
) : PlayerRepository {

    override val playbackUiState: StateFlow<PlaybackUiState> = controller.state

    override suspend fun playQueue(songs: List<Song>, startIndex: Int) {
        controller.playQueue(songs, startIndex)
    }

    override suspend fun playSongWithQueue(song: Song, queue: List<Song>) {
        controller.playSongWithQueue(song, queue)
    }

    override suspend fun pause() {
        controller.pause()
    }

    override suspend fun resume() {
        controller.resume()
    }

    override suspend fun seekTo(positionMs: Long) {
        controller.seekTo(positionMs)
    }

    override suspend fun skipToNext() {
        controller.skipToNext()
    }

    override suspend fun skipToPrevious() {
        controller.skipToPrevious()
    }

    override suspend fun setShuffle(enabled: Boolean) {
        controller.setShuffle(enabled)
    }

    override suspend fun setRepeatMode(repeatMode: Int) {
        controller.setRepeatMode(repeatMode)
    }

    override suspend fun toggleFavoriteCurrent() {
        val id = playbackUiState.value.currentSong?.id ?: return
        musicRepository.toggleFavorite(id)
    }

    override suspend fun preparePlaceholder(songId: String) {
        val all = musicRepository.observeAllSongs().first()
        val song = all.firstOrNull { it.id == songId } ?: return
        controller.playSongWithQueue(song, all)
    }

    override suspend fun clearPlaceholder() {
        controller.pause()
    }
}
