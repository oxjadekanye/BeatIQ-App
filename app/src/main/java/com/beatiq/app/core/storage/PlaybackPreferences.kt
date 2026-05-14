package com.beatiq.app.core.storage

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.media3.common.Player

/**
 * Persists the last playback queue, index, position, and transport controls for resume after
 * process death or service teardown.
 */
class PlaybackPreferences private constructor(app: Application) {

    private val prefs: SharedPreferences =
        app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun readSnapshot(): Snapshot? {
        val raw = prefs.getString(KEY_QUEUE_IDS, null) ?: return null
        val ids = raw.split(DELIMITER).filter { it.isNotEmpty() }
        if (ids.isEmpty()) return null
        return Snapshot(
            queueIds = ids,
            queueIndex = prefs.getInt(KEY_QUEUE_INDEX, 0),
            positionMs = prefs.getLong(KEY_POSITION_MS, 0L),
            shuffle = prefs.getBoolean(KEY_SHUFFLE, false),
            repeatMode = prefs.getInt(KEY_REPEAT_MODE, Player.REPEAT_MODE_OFF),
            wasPlaying = prefs.getBoolean(KEY_WAS_PLAYING, false),
        )
    }

    fun saveFromPlayer(player: Player) {
        val count = player.mediaItemCount
        if (count == 0) {
            prefs.edit().clear().apply()
            return
        }
        val ids = (0 until count).mapNotNull { i ->
            player.getMediaItemAt(i).mediaId?.takeIf { it.isNotEmpty() }
        }
        if (ids.isEmpty()) {
            prefs.edit().clear().apply()
            return
        }
        prefs.edit()
            .putString(KEY_QUEUE_IDS, ids.joinToString(DELIMITER))
            .putInt(KEY_QUEUE_INDEX, player.currentMediaItemIndex.coerceIn(0, ids.lastIndex))
            .putLong(KEY_POSITION_MS, player.currentPosition.coerceAtLeast(0L))
            .putBoolean(KEY_SHUFFLE, player.shuffleModeEnabled)
            .putInt(KEY_REPEAT_MODE, player.repeatMode)
            .putBoolean(KEY_WAS_PLAYING, player.playWhenReady && player.playbackState != Player.STATE_ENDED)
            .apply()
    }

    data class Snapshot(
        val queueIds: List<String>,
        val queueIndex: Int,
        val positionMs: Long,
        val shuffle: Boolean,
        val repeatMode: Int,
        val wasPlaying: Boolean,
    )

    companion object {
        private const val PREFS_NAME = "beatiq_playback_prefs"
        private const val DELIMITER = "\u001f"

        private const val KEY_QUEUE_IDS = "queue_ids"
        private const val KEY_QUEUE_INDEX = "queue_index"
        private const val KEY_POSITION_MS = "position_ms"
        private const val KEY_SHUFFLE = "shuffle"
        private const val KEY_REPEAT_MODE = "repeat_mode"
        private const val KEY_WAS_PLAYING = "was_playing"

        @Volatile
        private var instance: PlaybackPreferences? = null

        fun get(context: Context): PlaybackPreferences {
            val app = context.applicationContext as Application
            val existing = instance
            if (existing != null) return existing
            return synchronized(this) {
                instance?.let { return it }
                PlaybackPreferences(app).also { instance = it }
            }
        }
    }
}
