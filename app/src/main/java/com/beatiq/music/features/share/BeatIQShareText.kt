package com.beatiq.music.features.share

import com.beatiq.music.data.model.OnlineRecommendation
import com.beatiq.music.features.profile_aura.MusicAuraProfile

/**
 * Safe share payloads — never include preview streams, download URLs, or DRM links.
 */
object BeatIQShareText {
    fun auraShare(profile: MusicAuraProfile): String = profile.shareText.trim()

    fun beatFeedShare(item: OnlineRecommendation): String =
        buildString {
            append("Discovered on BeatIQ: ")
            append(item.title.trim())
            append(" — ")
            append(item.artist.trim())
            val reason = item.recommendationReason?.trim()
            if (!reason.isNullOrBlank()) {
                append("\n")
                append(reason)
            }
            append("\n")
            append("Listen legally in BeatIQ (previews & browse-only where required).")
        }

    /** Returns true when [text] is safe to share (no direct audio/download URLs). */
    fun isSafeShareText(text: String): Boolean {
        val lower = text.lowercase()
        val blocked =
            listOf(
                ".mp3",
                ".m4a",
                ".flac",
                "preview.dzcdn",
                "download?",
                "blob:",
            )
        return blocked.none { lower.contains(it) }
    }
}
