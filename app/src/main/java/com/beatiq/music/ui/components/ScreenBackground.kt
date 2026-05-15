package com.beatiq.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.beatiq.music.ui.theme.BeatIQDeepViolet
import com.beatiq.music.ui.theme.BeatIQGlowBlue
import com.beatiq.music.ui.theme.BeatIQGlowPink
import com.beatiq.music.ui.theme.BeatIQMidnight
import com.beatiq.music.ui.theme.BeatIQVioletElevated

@Composable
fun PremiumScreenBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        BeatIQMidnight,
                        BeatIQDeepViolet,
                        BeatIQVioletElevated,
                        BeatIQMidnight,
                    ),
                ),
            ),
    ) {
        val wPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val hPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(BeatIQGlowPink, Color.Transparent),
                        center = Offset(wPx * 0.22f, hPx * 0.08f),
                        radius = wPx * 0.95f,
                    ),
                ),
        )
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(BeatIQGlowBlue, Color.Transparent),
                        center = Offset(wPx * 0.92f, hPx * 0.22f),
                        radius = wPx * 0.65f,
                    ),
                ),
        )
        Box(Modifier.fillMaxSize()) {
            content()
        }
    }
}
