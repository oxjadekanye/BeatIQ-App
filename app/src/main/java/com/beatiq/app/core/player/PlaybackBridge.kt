package com.beatiq.app.core.player

import androidx.media3.common.Player

/**
 * Lightweight bridge so [BeatPlaybackService] can notify UI/controller without tight coupling.
 */
object PlaybackBridge {
    var listener: Listener? = null

    interface Listener {
        fun onPlaybackStateChanged(playbackState: Int)
    }

    fun onPlaybackStateChanged(playbackState: Int) {
        listener?.onPlaybackStateChanged(playbackState)
    }

    fun mapEnded(state: Int): Boolean = state == Player.STATE_ENDED
}
