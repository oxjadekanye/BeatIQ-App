package com.beatiq.music.features.mood

import androidx.compose.ui.graphics.Color

enum class MoodId {
    AFROBEATS_ENERGY,
    LATE_NIGHT_DRIVE,
    GYM_MODE,
    HEARTBREAK,
    SOFT_LIFE,
    PRAYER_VIBES,
    DEEP_FOCUS,
    STREET_ENERGY,
    WEEKEND_PARTY,
    SUNDAY_CALM,
    ROMANTIC_EVENING,
    AMAPIANO_WAVE,
    CHILL_DISCOVERY,
}

data class MoodDefinition(
    val id: MoodId,
    val displayName: String,
    val shortDescription: String,
    val gradientStart: Color,
    val gradientEnd: Color,
    val preferredGenres: List<String>,
    val searchTerms: List<String>,
    val bpmMin: Int,
    val bpmMax: Int,
    val animationIntensity: Float,
    val recommendationWeight: Float,
    val emptyStateCopy: String,
)
