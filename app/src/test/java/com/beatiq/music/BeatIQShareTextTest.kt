package com.beatiq.music

import com.beatiq.music.data.model.OnlineRecommendation
import com.beatiq.music.data.model.RecommendationProvider
import com.beatiq.music.features.profile_aura.MusicAuraProfile
import com.beatiq.music.features.share.BeatIQShareText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BeatIQShareTextTest {
    @Test
    fun auraShareUsesProfileText() {
        val profile =
            MusicAuraProfile(
                name = "Midnight Dreamer",
                description = "Moody nights",
                topMoodTags = listOf("Late Night"),
                gradientStartHex = 0,
                gradientEndHex = 0,
                shareText = "My BeatIQ Music Aura is Midnight Dreamer",
            )
        assertTrue(BeatIQShareText.auraShare(profile).contains("Midnight Dreamer"))
    }

    @Test
    fun beatFeedShareOmitsStreamUrls() {
        val item =
            OnlineRecommendation(
                id = "1",
                title = "Track",
                artist = "Artist",
                album = "",
                artworkUrl = "",
                genreHint = "",
                trackViewUrl = "https://www.deezer.com/track/1",
                previewUrl = "https://cdns-preview.dzcdn.net/x.mp3",
                releaseDate = null,
                provider = RecommendationProvider.DEEZER,
                recommendationReason = "Fresh pick",
            )
        val text = BeatIQShareText.beatFeedShare(item)
        assertFalse(text.contains(".mp3"))
        assertTrue(BeatIQShareText.isSafeShareText(text))
    }
}
