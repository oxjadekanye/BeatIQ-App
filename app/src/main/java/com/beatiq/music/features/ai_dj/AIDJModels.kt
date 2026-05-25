package com.beatiq.music.features.ai_dj

import com.beatiq.music.data.model.OnlineRecommendation

data class AIDJResponse(
    val message: String,
    val queue: List<OnlineRecommendation>,
    val appliedMoodLabel: String? = null,
)

data class AIDJCommand(
    val raw: String,
    val intent: AIDJIntent,
    val searchTerms: List<String>,
)

enum class AIDJIntent {
    TRENDING_AFROBEATS,
    LATE_NIGHT,
    GYM_ENERGY,
    UNDERGROUND,
    LIKE_SAVED,
    CALM_FOCUS,
    NIGERIAN_HEAT,
    SURPRISE,
    UNKNOWN,
}
