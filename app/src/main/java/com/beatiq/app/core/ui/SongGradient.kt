package com.beatiq.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.beatiq.app.data.model.Song

private val palette =
    listOf(
        Color(0xFF6B4EFF) to Color(0xFF2D1B4E),
        Color(0xFFFF4D8D) to Color(0xFF3A0F4A),
        Color(0xFF3EE8C4) to Color(0xFF163A45),
        Color(0xFF8B5CF6) to Color(0xFF1E1033),
        Color(0xFFF59E0B) to Color(0xFF3D2508),
        Color(0xFF38BDF8) to Color(0xFF0B2A3D),
        Color(0xFFA78BFA) to Color(0xFF22142F),
        Color(0xFF22D3EE) to Color(0xFF082029),
    )

@Composable
fun rememberSongGradientBrush(song: Song): Brush {
    val pair =
        remember(song.id) {
            val idx = kotlin.math.abs(song.id.hashCode()) % palette.size
            palette[idx]
        }
    return remember(pair) {
        Brush.linearGradient(listOf(pair.first, pair.second))
    }
}
