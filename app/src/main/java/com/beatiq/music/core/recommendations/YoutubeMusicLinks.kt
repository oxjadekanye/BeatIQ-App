package com.beatiq.music.core.recommendations

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Builds HTTPS YouTube / YouTube Music browse URLs only.
 * Does not extract, scrape, or resolve stream URLs.
 */
object YoutubeMusicLinks {

    private const val YOUTUBE_MUSIC_SEARCH = "https://music.youtube.com/search?q="
    private const val YOUTUBE_SEARCH = "https://www.youtube.com/results?search_query="

    fun searchUrlForQuery(query: String): String {
        val q = query.trim().ifBlank { "music" }
        return YOUTUBE_MUSIC_SEARCH + URLEncoder.encode(q, StandardCharsets.UTF_8.name())
    }

    fun searchUrlForTrack(title: String, artist: String): String {
        val combined = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")
        return searchUrlForQuery(combined)
    }

    fun isYoutubeBrowseHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val h = host.lowercase()
        return h == "www.youtube.com" ||
            h == "youtube.com" ||
            h == "m.youtube.com" ||
            h == "music.youtube.com" ||
            h.endsWith(".youtube.com")
    }
}
