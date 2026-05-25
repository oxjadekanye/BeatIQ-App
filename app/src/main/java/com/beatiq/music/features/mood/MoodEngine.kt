package com.beatiq.music.features.mood

import com.beatiq.music.presentation.home.HomeGenreTab
import java.time.LocalTime

/**
 * Rules-based mood engine. No remote AI required.
 * TODO(Phase-2): plug [MoodAiAdapter] for backend mood inference.
 */
object MoodEngine {
    fun suggestMoodForTime(): MoodId {
        val hour = LocalTime.now().hour
        return when (hour) {
            in 5..10 -> MoodId.SUNDAY_CALM
            in 11..16 -> MoodId.CHILL_DISCOVERY
            in 17..20 -> MoodId.AFROBEATS_ENERGY
            in 21..23 -> MoodId.LATE_NIGHT_DRIVE
            else -> MoodId.DEEP_FOCUS
        }
    }

    fun genreTabForMood(mood: MoodDefinition): HomeGenreTab {
        val hint = mood.preferredGenres.firstOrNull()?.lowercase().orEmpty()
        return HomeGenreTab.all.firstOrNull { tab ->
            tab.label.lowercase() in hint || tab.searchTerm.lowercase() in hint
        } ?: HomeGenreTab.AFROBEATS
    }

    fun moodMatchScore(mood: MoodDefinition, genreHint: String, title: String, artist: String): Float {
        val blob = "$genreHint $title $artist".lowercase()
        var score = 0.5f
        mood.preferredGenres.forEach { g ->
            if (blob.contains(g.lowercase())) score += 0.2f
        }
        mood.searchTerms.forEach { t ->
            if (blob.contains(t.lowercase())) score += 0.15f
        }
        return score.coerceIn(0f, 1.5f)
    }
}

interface MoodAiAdapter {
    suspend fun inferMood(signals: Map<String, Any>): MoodId?
}
