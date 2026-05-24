package com.beatiq.music.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.beatiq.music.R
import com.beatiq.music.presentation.home.HomeRecBadge
import java.time.LocalTime

@Composable
fun HomeWelcomeHeader(
    greeting: String,
    subline: String,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.horizontalGradient(
                    colors =
                        listOf(
                            primary.copy(alpha = 0.18f),
                            surface.copy(alpha = 0.35f),
                            primary.copy(alpha = 0.08f),
                        ),
                ),
            )
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Text(
            text = greeting,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = subline,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            text = stringResource(R.string.home_sub_ai),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
fun HomeRecBadgeChip(badge: HomeRecBadge) {
    val (label, container, content) =
        when (badge) {
            HomeRecBadge.NEW ->
                Triple(
                    stringResource(R.string.home_badge_new),
                    MaterialTheme.colorScheme.tertiary.copy(alpha = 0.85f),
                    MaterialTheme.colorScheme.onTertiary,
                )
            HomeRecBadge.POPULAR ->
                Triple(
                    stringResource(R.string.home_badge_popular),
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                    Color.White,
                )
            HomeRecBadge.MATCHES_TASTE ->
                Triple(
                    stringResource(R.string.home_badge_matches_taste),
                    MaterialTheme.colorScheme.secondary.copy(alpha = 0.9f),
                    MaterialTheme.colorScheme.onSecondary,
                )
            HomeRecBadge.PLAYABLE_PREVIEW ->
                Triple(
                    stringResource(R.string.home_badge_playable_preview),
                    Color(0xFF2E7D5A).copy(alpha = 0.92f),
                    Color.White,
                )
            HomeRecBadge.FREE_PREVIEW ->
                Triple(
                    stringResource(R.string.home_badge_free_preview),
                    Color(0xFF3D6B8C).copy(alpha = 0.92f),
                    Color.White,
                )
            HomeRecBadge.OPENS_IN_YOUTUBE ->
                Triple(
                    stringResource(R.string.home_badge_opens_youtube),
                    Color(0xFFB3261E).copy(alpha = 0.92f),
                    Color.White,
                )
        }
    val onContent =
        if (content.luminance() < 0.45f) Color.White else MaterialTheme.colorScheme.onSurface
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = container,
        shadowElevation = 0.dp,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = onContent,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
fun MiniEqualizerBars(
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "eq")
    val h1 by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(380, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "eq1",
    )
    val h2 by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 0.3f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(420, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "eq2",
    )
    val h3 by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.9f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(360, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "eq3",
    )
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        listOf(h1, h2, h3).forEach { fraction ->
            Box(
                Modifier
                    .width(3.dp)
                    .height((10.dp * fraction).coerceAtLeast(3.dp))
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White),
            )
        }
    }
}

/** Stable soft tint inferred from artwork URL (no async palette extraction). */
fun artworkAccentColor(artworkUrl: String): Color {
    val hue = (artworkUrl.hashCode() and 0x7FFFFFFF) % 360
    return Color.hsl(hue.toFloat(), 0.42f, 0.48f)
}

@Composable
fun homeGreetingForNow(): String {
    val hour = LocalTime.now().hour
    return when (hour) {
        in 5..11 -> stringResource(R.string.home_greet_morning)
        in 12..16 -> stringResource(R.string.home_greet_afternoon)
        in 17..21 -> stringResource(R.string.home_greet_evening)
        else -> stringResource(R.string.home_greet_night)
    }
}
