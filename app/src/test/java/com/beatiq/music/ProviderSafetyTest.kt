package com.beatiq.music

import com.beatiq.music.data.model.OnlineRecommendation
import com.beatiq.music.data.model.RecommendationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderSafetyTest {
    @Test
    fun deezerPreviewIsNotForcedToInternalBrowser() {
        val rec =
            OnlineRecommendation(
                id = "d1",
                title = "Song",
                artist = "A",
                album = "B",
                artworkUrl = "",
                genreHint = "Pop",
                trackViewUrl = "https://www.deezer.com/track/1",
                previewUrl = "https://cdns-preview.dzcdn.net/stream/x.mp3",
                releaseDate = null,
                provider = RecommendationProvider.DEEZER,
                isPlayablePreview = true,
            )
        assertTrue(rec.isPlayablePreview)
        assertFalse(rec.opensInInternalBrowser)
        assertFalse(rec.isBrowseOnly)
    }

    @Test
    fun browseOnlyCardMustNotExposePlayablePreviewFlag() {
        val rec =
            OnlineRecommendation(
                id = "y2",
                title = "Browse",
                artist = "A",
                album = "",
                artworkUrl = "",
                genreHint = "",
                trackViewUrl = "https://music.youtube.com/search?q=test",
                previewUrl = null,
                releaseDate = null,
                provider = RecommendationProvider.YOUTUBE_MUSIC,
                isPlayablePreview = false,
            )
        assertTrue(rec.isBrowseOnly)
        assertFalse(rec.isPlayablePreview)
    }

    @Test
    fun youtubeMusicOpensInternalBrowserOnly() {
        val rec =
            OnlineRecommendation(
                id = "y1",
                title = "Song",
                artist = "A",
                album = "",
                artworkUrl = "",
                genreHint = "",
                trackViewUrl = "https://music.youtube.com/search?q=test",
                previewUrl = null,
                releaseDate = null,
                provider = RecommendationProvider.YOUTUBE_MUSIC,
            )
        assertTrue(rec.isBrowseOnly)
        assertTrue(rec.opensInInternalBrowser)
    }

    @Test
    fun deezerMarksPreviewCapabilitySeparatelyFromBrowseOnly() {
        val rec =
            OnlineRecommendation(
                id = "d2",
                title = "Preview",
                artist = "A",
                album = "",
                artworkUrl = "",
                genreHint = "Pop",
                trackViewUrl = "https://www.deezer.com/track/2",
                previewUrl = "https://cdns-preview.dzcdn.net/stream/y.mp3",
                releaseDate = null,
                provider = RecommendationProvider.DEEZER,
                isPlayablePreview = true,
            )
        assertTrue(rec.isPlayablePreview)
        assertFalse(rec.isBrowseOnly)
    }
}
