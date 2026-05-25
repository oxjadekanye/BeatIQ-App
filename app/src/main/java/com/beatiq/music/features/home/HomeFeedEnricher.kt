package com.beatiq.music.features.home

import android.content.Context
import com.beatiq.music.core.recommendations.HomeAiFeed
import com.beatiq.music.data.model.OnlineRecommendation
import com.beatiq.music.features.mood.MoodCatalog
import com.beatiq.music.features.mood.MoodDefinition
import com.beatiq.music.features.mood.MoodStore
import com.beatiq.music.features.recommendations.intelligence.RecommendationDebugLogger
import com.beatiq.music.features.recommendations.intelligence.RecommendationScoringEngine
import com.beatiq.music.features.recommendations.intelligence.UserMusicPreferenceStore

data class EnrichedHomeFeed(
    val base: HomeAiFeed,
    val mood: MoodDefinition,
    val featured: List<OnlineRecommendation>,
    val gridSixteen: List<OnlineRecommendation>,
    val trending: List<OnlineRecommendation>,
    val trendingAlbums: List<OnlineRecommendation>,
    val djMix: List<OnlineRecommendation>,
    val popularPlaylists: List<OnlineRecommendation>,
    val undergroundGems: List<OnlineRecommendation>,
    val newFreeMusic: List<OnlineRecommendation>,
)

object HomeFeedEnricher {
    fun enrich(context: Context, feed: HomeAiFeed): EnrichedHomeFeed {
        val mood = MoodCatalog.byId(MoodStore(context).getSelectedMoodId())
        val signals = UserMusicPreferenceStore(context).loadSignals()
        val all = feed.featured + feed.gridSixteen + feed.trending + feed.djMix
        val seen = mutableSetOf<String>()
        fun scoreList(list: List<OnlineRecommendation>): List<OnlineRecommendation> {
            val scored = RecommendationScoringEngine.scoreAll(list, signals, mood, seen)
            scored.forEach { seen.add(it.item.id) }
            return scored.map { it.item }
        }
        val featured = scoreList(feed.featured)
        val grid = scoreList(feed.gridSixteen)
        val trending = scoreList(feed.trending)
        val albums = scoreList(feed.trendingAlbums)
        val dj = scoreList(feed.djMix)
        val playlists = scoreList(feed.popularPlaylists)
        val allScored = RecommendationScoringEngine.scoreAll(all, signals, mood)
        val underground = RecommendationScoringEngine.undergroundGems(allScored)
        val newFree = allScored.filter { it.item.hasPreviewAudio }.map { it.item }.take(10)
        RecommendationDebugLogger.log("Enriched feed mood=${mood.displayName} items=${all.size}")
        return EnrichedHomeFeed(
            base = feed,
            mood = mood,
            featured = featured,
            gridSixteen = grid,
            trending = trending,
            trendingAlbums = albums,
            djMix = dj,
            popularPlaylists = playlists,
            undergroundGems = underground,
            newFreeMusic = newFree,
        )
    }
}
