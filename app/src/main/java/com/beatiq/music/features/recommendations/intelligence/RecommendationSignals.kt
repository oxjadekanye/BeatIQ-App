package com.beatiq.music.features.recommendations.intelligence

data class RecommendationSignals(
    val plays: Int = 0,
    val previewCompletions: Int = 0,
    val saves: Int = 0,
    val skips: Int = 0,
    val providerOpens: Int = 0,
    val moodSelections: Int = 0,
    val searches: List<String> = emptyList(),
    val recentGenres: List<String> = emptyList(),
    val tasteArtist: String? = null,
)

enum class SignalEventType {
    PLAY,
    PREVIEW_COMPLETE,
    SAVE,
    SKIP,
    PROVIDER_OPEN,
    MOOD_SELECT,
    SEARCH,
    GENRE_TAP,
}
