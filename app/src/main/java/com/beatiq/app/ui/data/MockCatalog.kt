package com.beatiq.app.ui.data

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

@Immutable
data class MusicHighlight(
    val id: String,
    val title: String,
    val subtitle: String,
    val accentStart: Color,
    val accentEnd: Color,
)

@Immutable
data class DownloadPlaceholder(
    val id: String,
    val title: String,
    val subtitle: String,
    val progress: Float,
)

/** Static placeholder catalog for UI only; replace with repository later. */
object MockCatalog {
    val featured: List<MusicHighlight> = listOf(
        MusicHighlight("f1", "Midnight Pulse", "Curated for focus", Color(0xFF6B4EFF), Color(0xFF2D1B4E)),
        MusicHighlight("f2", "Neon Skyline", "Synthwave essentials", Color(0xFFFF4D8D), Color(0xFF3A0F4A)),
        MusicHighlight("f3", "Lo-Fi Study", "Soft beats & vinyl", Color(0xFF3EE8C4), Color(0xFF163A45)),
    )

    val trending: List<MusicHighlight> = listOf(
        MusicHighlight("t1", "Global Top 50", "Updated daily", Color(0xFF8B5CF6), Color(0xFF1E1033)),
        MusicHighlight("t2", "Rising Artists", "Fresh voices", Color(0xFFF59E0B), Color(0xFF3D2508)),
        MusicHighlight("t3", "Deep Focus", "No distractions", Color(0xFF38BDF8), Color(0xFF0B2A3D)),
        MusicHighlight("t4", "Indie Mix", "Guitar & groove", Color(0xFFA78BFA), Color(0xFF22142F)),
    )

    val trendingPlaylists: List<MusicHighlight> = listOf(
        MusicHighlight("p1", "Purple Haze Mix", "12 tracks", Color(0xFF7C3AED), Color(0xFF12081F)),
        MusicHighlight("p2", "Sunset Drive", "DJ set", Color(0xFFEC4899), Color(0xFF2A0A18)),
        MusicHighlight("p3", "Bass Therapy", "Heavy & clean", Color(0xFF22D3EE), Color(0xFF082029)),
    )

    val genres: List<String> = listOf(
        "Pop", "Electronic", "Hip-Hop", "Jazz", "Rock", "Classical", "R&B", "Latin",
    )

    val recentSearches: List<String> = listOf(
        "Chill beats", "Jazz focus", "Workout 140 BPM", "Ambient rain",
    )

    val favourites: List<MusicHighlight> = listOf(
        MusicHighlight("l1", "Starlight EP", "Nova Lane", Color(0xFF6366F1), Color(0xFF14122A)),
        MusicHighlight("l2", "Echo Chamber", "Live session", Color(0xFF14B8A6), Color(0xFF0C1F1C)),
        MusicHighlight("l3", "Velvet Room", "Slow jams", Color(0xFFE879F9), Color(0xFF2A0F2E)),
    )

    val activeDownloads: List<DownloadPlaceholder> = listOf(
        DownloadPlaceholder("d1", "Crystal Waves.flac", "Queued · Wi‑Fi only", 0.62f),
        DownloadPlaceholder("d2", "Neon Pulse EP", "Downloading…", 0.18f),
    )
}
