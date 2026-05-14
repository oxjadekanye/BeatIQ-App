package com.beatiq.app.data.model

/**
 * UI-facing playback snapshot. Driven by Media3 [androidx.media3.session.MediaController] events.
 *
 * TODO(AI): Extend with semantic mood tags / session embeddings for intelligent auto-queueing.
 */
data class PlaybackUiState(
    val currentSong: Song? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val queue: List<Song> = emptyList(),
    val queueIndex: Int = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = 0, // androidx.media3.common.Player.REPEAT_MODE_*
)
