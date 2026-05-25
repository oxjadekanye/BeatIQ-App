package com.beatiq.music.features.globalheat

import com.beatiq.music.core.recommendations.YoutubeMusicLinks
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class GlobalHeatCard(
    val id: String,
    val locationName: String,
    val focusLabel: String,
    val searchQuery: String,
    val browseUrl: String,
    val gradientStartHex: Long,
    val gradientEndHex: Long,
)

object GlobalHeatCatalog {
    val cards: List<GlobalHeatCard> =
        listOf(
            card("lagos", "Lagos Heat", "Afrobeats · Street", "afrobeats lagos heat 2024", 0xFFFF6B35, 0xFFFFD23F),
            card("london", "London Vibes", "UK Afrobeats · R&B", "uk afrobeats london", 0xFF1A1A2E, 0xFF4A4E8A),
            card("accra", "Accra Rising", "Highlife · Afrobeats", "accra ghana afrobeats", 0xFFE63946, 0xFFFFB703),
            card("joburg", "Johannesburg Amapiano", "Amapiano · Club", "amapiano johannesburg", 0xFF2D6A4F, 0xFF95D5B2),
            card("nyc", "New York Discoveries", "Hip-Hop · R&B", "new york r&b discoveries", 0xFF212121, 0xFF7B2CBF),
            card("paris", "Paris Chill", "Electronic · Chill", "paris chill electronic", 0xFF3D5A80, 0xFFE0FBFC),
            card("toronto", "Toronto Afrobeats", "Afrobeats · Diaspora", "toronto afrobeats", 0xFF264653, 0xFF2A9D8F),
        )

    private fun card(
        id: String,
        name: String,
        focus: String,
        query: String,
        start: Long,
        end: Long,
    ): GlobalHeatCard =
        GlobalHeatCard(
            id = id,
            locationName = name,
            focusLabel = focus,
            searchQuery = query,
            browseUrl = YoutubeMusicLinks.searchUrlForQuery(query),
            gradientStartHex = start,
            gradientEndHex = end,
        )

    fun deezerSearchUrl(query: String): String =
        "https://www.deezer.com/search?q=${URLEncoder.encode(query, StandardCharsets.UTF_8.name())}"
}
