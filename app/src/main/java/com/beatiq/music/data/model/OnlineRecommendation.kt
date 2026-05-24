package com.beatiq.music.data.model

import com.beatiq.music.core.browser.DirectAudioDownloadDetector
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Online track, album, or playlist suggestion (Deezer Public API metadata). */
data class OnlineRecommendation(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val artworkUrl: String,
    val genreHint: String,
    /** Deezer web page for this track, album, or playlist. */
    val trackViewUrl: String,
    val previewUrl: String?,
    val releaseDate: String?,
    val isAlbum: Boolean = false,
    /** Human-readable provider label, e.g. "Deezer". */
    val sourceLabel: String? = "Deezer",
    /** True when a 30s Deezer preview stream URL is available. */
    val isPlayablePreview: Boolean = false,
) {
    val browserUrl: String
        get() =
            trackViewUrl.ifBlank {
                "https://www.deezer.com/search?q=${
                    URLEncoder.encode("$title $artist", StandardCharsets.UTF_8.name())
                }"
            }

    val hasPreviewAudio: Boolean
        get() =
            isPlayablePreview &&
                !previewUrl.isNullOrBlank() &&
                DirectAudioDownloadDetector.looksLikeDirectAudioFileUrl(previewUrl)
}
