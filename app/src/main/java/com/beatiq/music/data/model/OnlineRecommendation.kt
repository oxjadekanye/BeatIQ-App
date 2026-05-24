package com.beatiq.music.data.model

/** Online track or album suggestion (iTunes Search API metadata). */
data class OnlineRecommendation(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val artworkUrl: String,
    val genreHint: String,
    val trackViewUrl: String,
    val previewUrl: String?,
    val releaseDate: String?,
    val isAlbum: Boolean = false,
) {
    val browserUrl: String
        get() = trackViewUrl.ifBlank { "https://music.apple.com/search?term=${java.net.URLEncoder.encode("$title $artist", Charsets.UTF_8.name())}" }

    val hasPreviewAudio: Boolean
        get() = !previewUrl.isNullOrBlank() && previewUrl.contains(".m4a", ignoreCase = true) ||
            previewUrl?.contains(".mp3", ignoreCase = true) == true
}
