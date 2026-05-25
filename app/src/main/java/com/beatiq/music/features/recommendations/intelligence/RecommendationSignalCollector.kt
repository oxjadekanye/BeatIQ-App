package com.beatiq.music.features.recommendations.intelligence

import android.content.Context

object RecommendationSignalCollector {
    fun store(context: Context): UserMusicPreferenceStore = UserMusicPreferenceStore(context)

    fun recordPlay(context: Context, artist: String) {
        store(context).record(SignalEventType.PLAY, artist)
    }

    fun recordPreviewComplete(context: Context) {
        store(context).record(SignalEventType.PREVIEW_COMPLETE)
    }

    fun recordSave(context: Context) {
        store(context).record(SignalEventType.SAVE)
    }

    fun recordSkip(context: Context) {
        store(context).record(SignalEventType.SKIP)
    }

    fun recordProviderOpen(context: Context) {
        store(context).record(SignalEventType.PROVIDER_OPEN)
    }

    fun recordMoodSelect(context: Context) {
        store(context).record(SignalEventType.MOOD_SELECT)
    }

    fun recordSearch(context: Context, query: String) {
        store(context).record(SignalEventType.SEARCH, query)
    }

    fun recordGenreTap(context: Context, genre: String) {
        store(context).record(SignalEventType.GENRE_TAP, genre)
    }
}
