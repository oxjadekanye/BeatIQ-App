package com.beatiq.music.features.recommendations.intelligence

import com.beatiq.music.data.model.OnlineRecommendation
import com.beatiq.music.data.model.RecommendationProvider
import com.beatiq.music.features.mood.MoodDefinition
import com.beatiq.music.features.mood.MoodEngine
import java.time.LocalDate
import java.time.LocalTime

data class ScoredRecommendation(
    val item: OnlineRecommendation,
    val score: Float,
    val reason: String,
)

object RecommendationScoringEngine {
    fun scoreAll(
        items: List<OnlineRecommendation>,
        signals: RecommendationSignals,
        mood: MoodDefinition?,
        seenIds: Set<String> = emptySet(),
    ): List<ScoredRecommendation> {
        val hour = LocalTime.now().hour
        return items
            .map { item ->
                val s = scoreOne(item, signals, mood, hour, seenIds.contains(item.id))
                val reason = RecommendationReasonGenerator.reason(item, signals, mood, s)
                ScoredRecommendation(
                    item =
                        item.copy(
                            score = s,
                            recommendationReason = reason,
                            moodTags = mood?.preferredGenres?.take(3).orEmpty(),
                        ),
                    score = s,
                    reason = reason,
                )
            }
            .sortedByDescending { it.score }
    }

    private fun scoreOne(
        item: OnlineRecommendation,
        signals: RecommendationSignals,
        mood: MoodDefinition?,
        hour: Int,
        isDuplicate: Boolean,
    ): Float {
        var score = 1f
        if (isDuplicate) score -= 0.5f
        if (item.hasPreviewAudio) score += 0.35f
        if (item.provider == RecommendationProvider.DEEZER) score += 0.15f
        if (!item.releaseDate.isNullOrBlank()) {
            runCatching { LocalDate.parse(item.releaseDate.take(10)) }.getOrNull()?.let { d ->
                if (!d.isBefore(LocalDate.now().minusDays(120))) score += 0.2f
            }
        }
        mood?.let { m ->
            score += MoodEngine.moodMatchScore(m, item.genreHint, item.title, item.artist) * m.recommendationWeight * 0.25f
        }
        signals.tasteArtist?.let { taste ->
            if (item.artist.contains(taste, ignoreCase = true)) score += 0.3f
        }
        if (hour in 21..23 || hour < 5) {
            if (item.genreHint.contains("R&B", ignoreCase = true) || item.genreHint.contains("Soul", ignoreCase = true)) {
                score += 0.15f
            }
        }
        if (signals.saves > 0 && item.hasPreviewAudio) score += 0.1f
        if (item.opensInInternalBrowser) score += 0.05f
        return score.coerceAtLeast(0.1f)
    }

    fun undergroundGems(items: List<ScoredRecommendation>, limit: Int = 8): List<OnlineRecommendation> =
        items
            .filter { it.score in 0.9f..1.25f }
            .take(limit)
            .map { it.item }

    fun dedupeIds(items: List<OnlineRecommendation>): List<OnlineRecommendation> {
        val seen = linkedSetOf<String>()
        return items.filter { seen.add(it.id) }
    }
}
