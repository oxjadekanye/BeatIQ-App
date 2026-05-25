package com.beatiq.music.features.search

import com.beatiq.music.presentation.home.HomeGenreTab

data class SmartSearchMapping(
    val providerQuery: String,
    val genreTab: HomeGenreTab?,
    val moodHint: String?,
)

object SmartSearchMapper {
    private val shortcuts =
        mapOf(
            "sad afrobeats" to SmartSearchMapping("sad afrobeats soul", HomeGenreTab.AFROBEATS, "Heartbreak"),
            "late night drive" to SmartSearchMapping("late night r&b drive", HomeGenreTab.RNB, "Late Night Drive"),
            "gym amapiano" to SmartSearchMapping("gym amapiano workout", HomeGenreTab.LATIN, "Gym Mode"),
            "calm gospel" to SmartSearchMapping("calm gospel worship", HomeGenreTab.GOSPEL, "Prayer Vibes"),
            "trending lagos sound" to SmartSearchMapping("trending afrobeats lagos", HomeGenreTab.AFROBEATS, "Afrobeats Energy"),
            "underground afrobeats" to SmartSearchMapping("underground afrobeats", HomeGenreTab.AFROBEATS, "Chill Discovery"),
        )

    val chipSuggestions: List<String> = shortcuts.keys.toList()

    fun map(query: String): SmartSearchMapping {
        val q = query.trim().lowercase()
        shortcuts[q]?.let { return it }
        shortcuts.entries.firstOrNull { q.contains(it.key) }?.value?.let { return it }
        return SmartSearchMapping(
            providerQuery = query.trim().ifBlank { "afrobeats" },
            genreTab = null,
            moodHint = null,
        )
    }
}
