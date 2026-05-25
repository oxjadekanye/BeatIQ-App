package com.beatiq.music.core.ui.premium

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.beatiq.music.data.model.OnlineRecommendation
import com.beatiq.music.data.model.RecommendationProvider
import com.beatiq.music.ui.components.MiniEqualizerBars

@Composable
fun AnimatedGradientBackground(
    start: Color,
    end: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val animate = rememberAnimationsEnabled()
    val shift =
        if (animate) {
            val transition = rememberInfiniteTransition(label = "bg")
            val animated by transition.animateFloat(
                0f,
                1f,
                infiniteRepeatable(tween(8000, easing = LinearEasing), RepeatMode.Reverse),
                label = "bgShift",
            )
            animated
        } else {
            0.35f
        }
    val c1 = androidx.compose.ui.graphics.lerp(start, end, shift * 0.35f)
    val c2 = androidx.compose.ui.graphics.lerp(end, start, shift * 0.25f)
    Box(
        modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(c1.copy(alpha = 0.35f), Color(0xFF0A0A0F), c2.copy(alpha = 0.2f))),
            ),
    ) {
        content()
    }
}

@Composable
fun ProviderBadge(
    label: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
fun RecommendationReasonText(
    text: String?,
    modifier: Modifier = Modifier,
) {
    if (text.isNullOrBlank()) return
        Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = Color(0xFFB8B8C8),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
fun MoodChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderColor =
        if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.2f)
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        color =
            if (selected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.72f)
            } else {
                Color(0xFF1E1E28).copy(alpha = 0.9f)
            },
        border = BorderStroke(1.dp, borderColor),
        shadowElevation = if (selected) 6.dp else 0.dp,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) Color.White else Color(0xFFE8E8F0),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

@Composable
fun PremiumMusicCard(
    item: OnlineRecommendation,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = false,
) {
    Card(
        onClick = onClick,
        modifier = modifier.width(168.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A22).copy(alpha = 0.82f)),
        elevation = CardDefaults.cardElevation(4.dp),
    ) {
        Column {
            Box(Modifier.fillMaxWidth().height(148.dp)) {
                AsyncImage(
                    model = item.artworkUrl,
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)),
                            ),
                        ),
                )
                ProviderBadge(
                    label = item.sourceLabel ?: item.provider.name,
                    modifier = Modifier.padding(8.dp),
                )
                if (isPlaying) {
                    Row(Modifier.align(Alignment.BottomEnd).padding(8.dp)) {
                        MiniEqualizerBars(animate = rememberAnimationsEnabled())
                    }
                }
            }
            Column(Modifier.padding(12.dp)) {
                Text(
                    item.title,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFF5F5FA),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    item.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFB0B0BE),
                    maxLines = 1,
                )
                RecommendationReasonText(item.recommendationReason, Modifier.padding(top = 4.dp))
                if (item.isBrowseOnly) {
                    Text(
                        "Opens in YouTube",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun HorizontalMusicRail(
    title: String,
    items: List<OnlineRecommendation>,
    onItemClick: (OnlineRecommendation) -> Unit,
    isPlaying: (OnlineRecommendation) -> Boolean = { false },
) {
    if (items.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        PremiumSectionHeader(title)
        androidx.compose.foundation.lazy.LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(items.size, key = { items[it].id }) { i ->
                PremiumMusicCard(
                    item = items[i],
                    onClick = { onItemClick(items[i]) },
                    isPlaying = isPlaying(items[i]),
                )
            }
        }
    }
}

@Composable
fun PremiumSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

@Composable
fun HeroMoodCard(
    title: String,
    subtitle: String,
    moodLabel: String,
    gradientStart: Color,
    gradientEnd: Color,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(8.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(
                            gradientStart.copy(alpha = 0.92f),
                            gradientEnd.copy(alpha = 0.72f),
                            Color(0xFF0A0A12).copy(alpha = 0.55f),
                        ),
                    ),
                )
                .padding(horizontal = 22.dp, vertical = 22.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "BeatIQ Mood",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    color = Color.White.copy(alpha = 0.92f),
                )
                Text(
                    title,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFF0F0F8),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                ProviderBadge(moodLabel)
            }
        }
    }
}

@Composable
fun AIDJEntryCard(onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2A1F3D)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("BeatIQ Smart DJ", fontWeight = FontWeight.Bold, color = Color.White)
            Text(
                "Ask for trending Afrobeats, gym energy, underground gems…",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.85f),
            )
        }
    }
}

@Composable
fun OfflineHomeBanner(onRetry: () -> Unit) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .clickable(onClick = onRetry),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2B2B35)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("You're offline. Your saved previews are ready.", fontWeight = FontWeight.SemiBold)
            Text("Tap to retry connection", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun MusicAuraBadge(
    name: String,
    description: String,
    tags: List<String>,
    gradientStart: Color,
    gradientEnd: Color,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(listOf(gradientStart.copy(alpha = 0.8f), gradientEnd.copy(alpha = 0.6f))))
                .padding(16.dp),
        ) {
            Column {
                Text("Music Aura", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.9f))
                Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
                Text(description, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.9f))
                Text(tags.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.85f))
            }
        }
    }
}

@Composable
fun HomeSectionSkeleton() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .height(120.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.08f)),
    )
}
