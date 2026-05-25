package com.beatiq.music.features.ai_dj

import com.beatiq.music.core.recommendations.HomeAiFeed
import com.beatiq.music.data.model.OnlineRecommendation
import com.beatiq.music.features.home.EnrichedHomeFeed
import com.beatiq.music.features.mood.MoodDefinition
import com.beatiq.music.features.recommendations.intelligence.RecommendationScoringEngine
import com.beatiq.music.features.recommendations.intelligence.RecommendationSignals

object AIDJQueueBuilder {
    private const val TARGET_SIZE = 12
    private const val MIN_SIZE = 6

    fun buildQueue(
        enriched: EnrichedHomeFeed,
        rawFeed: HomeAiFeed,
        signals: RecommendationSignals,
        mood: MoodDefinition,
    ): List<OnlineRecommendation> {
        val primaryPool =
            listOf(
                enriched.featured,
                enriched.trending,
                enriched.gridSixteen,
                enriched.undergroundGems,
                enriched.djMix,
                enriched.newFreeMusic,
                rawFeed.popularPlaylists,
                rawFeed.trendingAlbums,
            ).flatten().distinctBy { it.id }

        var queue =
            RecommendationScoringEngine
                .scoreAll(primaryPool, signals, mood)
                .take(TARGET_SIZE)
                .map { it.item }

        if (queue.size < MIN_SIZE) {
            val fallbackPool =
                (rawFeed.gridSixteen + rawFeed.featured + rawFeed.trending)
                    .distinctBy { it.id }
                    .filter { item -> queue.none { it.id == item.id } }
            val extra =
                RecommendationScoringEngine
                    .scoreAll(fallbackPool, signals, mood)
                    .map { it.item }
            queue = (queue + extra).distinctBy { it.id }.take(TARGET_SIZE)
        }

        if (queue.isEmpty()) {
            queue =
                mood.searchTerms
                    .flatMap { term ->
                        rawFeed.featured.filter { rec ->
                            rec.title.contains(term, true) ||
                                rec.artist.contains(term, true) ||
                                rec.genreHint.contains(term, true)
                        }
                    }
                    .distinctBy { it.id }
                    .take(MIN_SIZE)
        }

        return queue
    }

    fun emptyStateMessage(intent: AIDJIntent): String =
        when (intent) {
            AIDJIntent.UNKNOWN ->
                "I couldn't build a mix yet. Check your connection and try again."
            else ->
                "No picks loaded right now — refresh Home or try another command."
        }
}
