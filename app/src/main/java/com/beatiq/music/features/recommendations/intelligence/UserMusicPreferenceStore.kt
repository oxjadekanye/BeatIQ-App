package com.beatiq.music.features.recommendations.intelligence

import android.content.Context
import org.json.JSONArray

class UserMusicPreferenceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadSignals(): RecommendationSignals =
        RecommendationSignals(
            plays = prefs.getInt(KEY_PLAYS, 0),
            previewCompletions = prefs.getInt(KEY_PREVIEW_DONE, 0),
            saves = prefs.getInt(KEY_SAVES, 0),
            skips = prefs.getInt(KEY_SKIPS, 0),
            providerOpens = prefs.getInt(KEY_PROVIDER_OPENS, 0),
            moodSelections = prefs.getInt(KEY_MOOD_SELECTS, 0),
            searches = prefs.getString(KEY_SEARCHES, null)?.let(::parseList).orEmpty(),
            recentGenres = prefs.getString(KEY_GENRES, null)?.let(::parseList).orEmpty(),
            tasteArtist = prefs.getString(KEY_TASTE_ARTIST, null)?.takeIf { it.isNotBlank() },
        )

    fun record(event: SignalEventType, payload: String? = null) {
        val e = prefs.edit()
        when (event) {
            SignalEventType.PLAY -> e.putInt(KEY_PLAYS, prefs.getInt(KEY_PLAYS, 0) + 1)
            SignalEventType.PREVIEW_COMPLETE -> e.putInt(KEY_PREVIEW_DONE, prefs.getInt(KEY_PREVIEW_DONE, 0) + 1)
            SignalEventType.SAVE -> e.putInt(KEY_SAVES, prefs.getInt(KEY_SAVES, 0) + 1)
            SignalEventType.SKIP -> e.putInt(KEY_SKIPS, prefs.getInt(KEY_SKIPS, 0) + 1)
            SignalEventType.PROVIDER_OPEN -> e.putInt(KEY_PROVIDER_OPENS, prefs.getInt(KEY_PROVIDER_OPENS, 0) + 1)
            SignalEventType.MOOD_SELECT -> e.putInt(KEY_MOOD_SELECTS, prefs.getInt(KEY_MOOD_SELECTS, 0) + 1)
            SignalEventType.SEARCH -> {
                if (!payload.isNullOrBlank()) {
                    val list = parseList(prefs.getString(KEY_SEARCHES, "[]")).toMutableList()
                    list.add(0, payload.trim())
                    e.putString(KEY_SEARCHES, JSONArray(list.take(12)).toString())
                }
            }
            SignalEventType.GENRE_TAP -> {
                if (!payload.isNullOrBlank()) {
                    val list = parseList(prefs.getString(KEY_GENRES, "[]")).toMutableList()
                    list.add(0, payload.trim())
                    e.putString(KEY_GENRES, JSONArray(list.take(8)).toString())
                }
            }
        }
        if (!payload.isNullOrBlank() && event == SignalEventType.PLAY) {
            e.putString(KEY_TASTE_ARTIST, payload)
        }
        e.apply()
    }

    private fun parseList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    add(arr.optString(i))
                }
            }
        }.getOrElse { emptyList() }
    }

    companion object {
        private const val PREFS = "beatiq_rec_signals"
        private const val KEY_PLAYS = "plays"
        private const val KEY_PREVIEW_DONE = "preview_done"
        private const val KEY_SAVES = "saves"
        private const val KEY_SKIPS = "skips"
        private const val KEY_PROVIDER_OPENS = "provider_opens"
        private const val KEY_MOOD_SELECTS = "mood_selects"
        private const val KEY_SEARCHES = "searches"
        private const val KEY_GENRES = "genres"
        private const val KEY_TASTE_ARTIST = "taste_artist"
    }
}
