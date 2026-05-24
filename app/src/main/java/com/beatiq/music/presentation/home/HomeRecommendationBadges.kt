package com.beatiq.music.presentation.home

import com.beatiq.music.data.model.OnlineRecommendation
import java.time.LocalDate

enum class HomeRecSection {
    FEATURED,
    GRID,
    TRENDING,
    ALBUMS,
    DJ_MIX,
    PLAYLISTS,
}

enum class HomeRecBadge {
    NEW,
    POPULAR,
    MATCHES_TASTE,
    PLAYABLE_PREVIEW,
    FREE_PREVIEW,
}

object HomeRecommendationBadges {

    fun forItem(
        rec: OnlineRecommendation,
        section: HomeRecSection,
        tasteArtist: String?,
        itemIndex: Int = 0,
    ): List<HomeRecBadge> {
        val out = linkedSetOf<HomeRecBadge>()
        if (rec.isPlayablePreview) {
            out.add(HomeRecBadge.PLAYABLE_PREVIEW)
            if (rec.hasPreviewAudio) {
                out.add(HomeRecBadge.FREE_PREVIEW)
            }
        }
        if (isRecentRelease(rec.releaseDate) || (section == HomeRecSection.FEATURED && itemIndex < 2)) {
            out.add(HomeRecBadge.NEW)
        }
        if (section == HomeRecSection.TRENDING || section == HomeRecSection.PLAYLISTS) {
            out.add(HomeRecBadge.POPULAR)
        }
        val taste = tasteArtist?.trim().orEmpty()
        if (taste.isNotBlank() && rec.artist.contains(taste, ignoreCase = true)) {
            out.add(HomeRecBadge.MATCHES_TASTE)
        }
        return out.take(2)
    }

    private fun isRecentRelease(releaseDate: String?): Boolean {
        if (releaseDate.isNullOrBlank()) return false
        val date =
            runCatching { LocalDate.parse(releaseDate.take(10)) }
                .getOrElse {
                    return false
                }
        return !date.isBefore(LocalDate.now().minusDays(90))
    }
}
