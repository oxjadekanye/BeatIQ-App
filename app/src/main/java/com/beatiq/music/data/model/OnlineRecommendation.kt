package com.beatiq.music.data.model

import com.beatiq.music.core.browser.DirectAudioDownloadDetector
import com.beatiq.music.core.recommendations.YoutubeMusicLinks
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Online track, album, or playlist suggestion (Deezer / YouTube Music browse metadata). */
data class OnlineRecommendation(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val artworkUrl: String,
    val genreHint: String,
    /** Provider web page (Deezer track page or YouTube Music search). */
    val trackViewUrl: String,
    val previewUrl: String?,
    val releaseDate: String?,
    val isAlbum: Boolean = false,
    val provider: RecommendationProvider = RecommendationProvider.DEEZER,
    /** Human-readable provider label, e.g. "Deezer" or "YouTube Music". */
    val sourceLabel: String? = null,
    /** True when a legal direct preview stream URL is available (e.g. Deezer 30s MP3). */
    val isPlayablePreview: Boolean = false,
    /** Local intelligence score (rules-based, not remote AI). */
    val score: Float = 0f,
    /** Short on-card reason generated locally. */
    val recommendationReason: String? = null,
    val moodTags: List<String> = emptyList(),
) {
    val externalUrl: String
        get() = browserUrl

    val isBrowseOnly: Boolean
        get() = opensInInternalBrowser
    val browserUrl: String
        get() =
            trackViewUrl.ifBlank {
                when (provider) {
                    RecommendationProvider.YOUTUBE_MUSIC ->
                        YoutubeMusicLinks.searchUrlForTrack(title, artist)
                    RecommendationProvider.SOUNDCLOUD ->
                        "https://soundcloud.com/search?q=${
                            URLEncoder.encode("$title $artist", StandardCharsets.UTF_8.name())
                        }"
                    RecommendationProvider.DEEZER ->
                        "https://www.deezer.com/search?q=${
                            URLEncoder.encode("$title $artist", StandardCharsets.UTF_8.name())
                        }"
                }
            }

    val hasPreviewAudio: Boolean
        get() =
            provider == RecommendationProvider.DEEZER &&
                isPlayablePreview &&
                !previewUrl.isNullOrBlank() &&
                DirectAudioDownloadDetector.looksLikeDirectAudioFileUrl(previewUrl)

    /** No legal in-app audio stream — open provider in BeatIQ WebView instead. */
    val isYoutubeOnly: Boolean
        get() = provider == RecommendationProvider.YOUTUBE_MUSIC && !hasPreviewAudio

    val opensInInternalBrowser: Boolean
        get() = isYoutubeOnly
}
