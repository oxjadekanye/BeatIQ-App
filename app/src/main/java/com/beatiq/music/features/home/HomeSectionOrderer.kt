package com.beatiq.music.features.home

import com.beatiq.music.features.mood.MoodDefinition
import com.beatiq.music.features.recommendations.intelligence.RecommendationSignals

enum class PremiumHomeSection {
    HERO_MOOD,
    AI_DJ_ENTRY,
    AI_RECOMMENDED,
    TRENDING_NEAR_YOU,
    NEW_FREE,
    UNDERGROUND,
    MOOD_STATIONS,
    BEAT_FEED_PREVIEW,
    RECENTLY_PLAYED,
    SAVED_CLIPS,
    GLOBAL_HEAT,
    DJ_MIXES,
}

object HomeSectionOrderer {
    fun order(
        signals: RecommendationSignals,
        mood: MoodDefinition,
        isOffline: Boolean,
    ): List<PremiumHomeSection> {
        if (isOffline) {
            return listOf(
                PremiumHomeSection.HERO_MOOD,
                PremiumHomeSection.AI_DJ_ENTRY,
                PremiumHomeSection.SAVED_CLIPS,
                PremiumHomeSection.RECENTLY_PLAYED,
            )
        }
        val hour = java.time.LocalTime.now().hour
        val boostUnderground = signals.searches.any { it.contains("underground", true) }
        val boostTrending = signals.saves > 2 || signals.plays > 5

        val result = mutableListOf<PremiumHomeSection>()
        fun append(vararg sections: PremiumHomeSection) {
            sections.forEach { section ->
                if (section !in result) result.add(section)
            }
        }

        append(PremiumHomeSection.HERO_MOOD, PremiumHomeSection.AI_DJ_ENTRY)
        if (boostTrending) {
            append(PremiumHomeSection.TRENDING_NEAR_YOU, PremiumHomeSection.AI_RECOMMENDED)
        } else {
            append(PremiumHomeSection.AI_RECOMMENDED, PremiumHomeSection.TRENDING_NEAR_YOU)
        }
        append(PremiumHomeSection.NEW_FREE)
        if (boostUnderground) append(PremiumHomeSection.UNDERGROUND)
        append(PremiumHomeSection.MOOD_STATIONS, PremiumHomeSection.BEAT_FEED_PREVIEW)
        if (signals.saves > 0) append(PremiumHomeSection.SAVED_CLIPS)
        if (signals.plays > 0 || signals.previewCompletions > 0) {
            append(PremiumHomeSection.RECENTLY_PLAYED)
        }
        append(PremiumHomeSection.GLOBAL_HEAT)
        if (hour in 17..23) append(PremiumHomeSection.DJ_MIXES)
        append(PremiumHomeSection.UNDERGROUND)
        return result
    }
}
