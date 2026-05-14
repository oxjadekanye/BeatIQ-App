package com.beatiq.app.core.prefs

import android.content.Context

class BeatIQPreferences(
    context: Context,
) {
    private val p =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var streamQuality: String
        get() = p.getString(KEY_STREAM_QUALITY, VALUE_STANDARD) ?: VALUE_STANDARD
        set(value) {
            p.edit().putString(KEY_STREAM_QUALITY, value).apply()
        }

    var wifiOnlyDownloads: Boolean
        get() = p.getBoolean(KEY_WIFI_ONLY_DOWNLOADS, false)
        set(value) {
            p.edit().putBoolean(KEY_WIFI_ONLY_DOWNLOADS, value).apply()
        }

    var loudnessNormalization: Boolean
        get() = p.getBoolean(KEY_LOUDNESS, true)
        set(value) {
            p.edit().putBoolean(KEY_LOUDNESS, value).apply()
        }

    var showLyricsWhilePlaying: Boolean
        get() = p.getBoolean(KEY_SHOW_LYRICS, false)
        set(value) {
            p.edit().putBoolean(KEY_SHOW_LYRICS, value).apply()
        }

    companion object {
        private const val PREFS_NAME = "beatiq_settings"
        private const val KEY_STREAM_QUALITY = "stream_quality"
        private const val KEY_WIFI_ONLY_DOWNLOADS = "wifi_only_downloads"
        private const val KEY_LOUDNESS = "loudness_normalization"
        private const val KEY_SHOW_LYRICS = "show_lyrics_while_playing"

        const val VALUE_HIGH = "HIGH"
        const val VALUE_STANDARD = "STANDARD"
        const val VALUE_DATA_SAVER = "DATA_SAVER"
    }
}
