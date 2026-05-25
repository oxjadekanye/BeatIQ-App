package com.beatiq.music

import com.beatiq.music.data.model.OnlineRecommendation
import com.beatiq.music.data.model.RecommendationProvider
import com.beatiq.music.features.mood.MoodCatalog
import com.beatiq.music.features.recommendations.intelligence.RecommendationScoringEngine
import com.beatiq.music.features.recommendations.intelligence.RecommendationSignals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationScoringEngineTest {
    @Test
    fun previewTracksScoreHigherThanBrowseOnly() {
        val preview =
            sample(
                id = "1",
                previewUrl = "https://cdns-preview.dzcdn.net/stream/abc.mp3",
                isPlayablePreview = true,
            )
        val browse =
            sample(
                id = "2",
                provider = RecommendationProvider.YOUTUBE_MUSIC,
                previewUrl = null,
                isPlayablePreview = false,
            )
        val mood = MoodCatalog.byId(com.beatiq.music.features.mood.MoodId.AFROBEATS_ENERGY)
        val signals = RecommendationSignals()
        val scored = RecommendationScoringEngine.scoreAll(listOf(browse, preview), signals, mood)
        assertTrue(scored.first().item.id == "1")
    }

    private fun sample(
        id: String,
        previewUrl: String? = null,
        isPlayablePreview: Boolean = false,
        provider: RecommendationProvider = RecommendationProvider.DEEZER,
    ) = OnlineRecommendation(
        id = id,
        title = "Test",
        artist = "Artist",
        album = "Album",
        artworkUrl = "",
        genreHint = "Afrobeats",
        trackViewUrl = "https://www.deezer.com/track/1",
        previewUrl = previewUrl,
        releaseDate = null,
        provider = provider,
        isPlayablePreview = isPlayablePreview,
    )
}
