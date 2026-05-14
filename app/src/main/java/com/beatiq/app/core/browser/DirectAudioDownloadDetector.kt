package com.beatiq.app.core.browser

import java.util.Locale

/**
 * Heuristic detection of direct downloadable audio file URLs (user-initiated taps only).
 * Does not crawl pages or bypass DRM.
 */
object DirectAudioDownloadDetector {
    private val extensions = listOf(
        ".mp3", ".flac", ".ogg", ".oga", ".opus", ".wav", ".m4a", ".aac", ".aiff", ".alac",
    )

    fun looksLikeDirectAudioFileUrl(url: String): Boolean {
        val lower = url.trim().lowercase(Locale.US)
        if (!HttpsUrlValidator.isAllowedHttpsUrl(lower)) return false
        return extensions.any { lower.split('?', limit = 2)[0].endsWith(it) }
    }
}
