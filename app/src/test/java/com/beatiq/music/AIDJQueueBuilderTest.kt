package com.beatiq.music

import com.beatiq.music.core.recommendations.HomeAiFeed
import com.beatiq.music.data.model.OnlineRecommendation
import com.beatiq.music.data.model.RecommendationProvider
import com.beatiq.music.features.ai_dj.AIDJIntent
import com.beatiq.music.features.ai_dj.AIDJQueueBuilder
import com.beatiq.music.features.home.EnrichedHomeFeed
import com.beatiq.music.features.mood.MoodCatalog
import com.beatiq.music.features.mood.MoodId
import com.beatiq.music.features.recommendations.intelligence.RecommendationSignals
import com.beatiq.music.presentation.home.HomeGenreTab
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AIDJQueueBuilderTest {
    @Test
    fun buildsQueueFromEnrichedPool() {
        val rec = sample("a1")
        val feed =
            HomeAiFeed(
                genreTab = HomeGenreTab.AFROBEATS,
                featured = listOf(rec),
                gridSixteen = emptyList(),
                trending = emptyList(),
                trendingAlbums = emptyList(),
                djMix = emptyList(),
                popularPlaylists = emptyList(),
            )
        val enriched =
            EnrichedHomeFeed(
                base = feed,
                mood = MoodCatalog.byId(MoodId.AFROBEATS_ENERGY),
                featured = listOf(rec),
                gridSixteen = listOf(sample("a2")),
                trending = emptyList(),
                trendingAlbums = emptyList(),
                djMix = emptyList(),
                popularPlaylists = emptyList(),
                undergroundGems = emptyList(),
                newFreeMusic = emptyList(),
            )
        val queue =
            AIDJQueueBuilder.buildQueue(
                enriched,
                feed,
                RecommendationSignals(),
                MoodCatalog.byId(MoodId.AFROBEATS_ENERGY),
            )
        assertTrue(queue.isNotEmpty())
    }

    @Test
    fun emptyStateMessageIsNonBlank() {
        assertFalse(AIDJQueueBuilder.emptyStateMessage(AIDJIntent.UNKNOWN).isBlank())
    }

    private fun sample(id: String) =
        OnlineRecommendation(
            id = id,
            title = "Title $id",
            artist = "Artist",
            album = "Album",
            artworkUrl = "",
            genreHint = "Afrobeats",
            trackViewUrl = "https://www.deezer.com/track/1",
            previewUrl = "https://cdns-preview.dzcdn.net/x.mp3",
            releaseDate = null,
            provider = RecommendationProvider.DEEZER,
            isPlayablePreview = true,
        )
}
