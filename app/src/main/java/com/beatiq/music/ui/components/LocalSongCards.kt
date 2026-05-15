package com.beatiq.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.beatiq.music.core.ui.rememberSongGradientBrush
import com.beatiq.music.data.model.Song
import com.beatiq.music.ui.theme.BeatIQCardStroke

@Composable
fun LocalSongHeroCard(
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(22.dp)
    val brush = rememberSongGradientBrush(song)
    Card(
        onClick = onClick,
        modifier = modifier.width(280.dp),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
    ) {
        Box(
            Modifier
                .height(168.dp)
                .fillMaxWidth()
                .clip(shape)
                .border(1.dp, BeatIQCardStroke, shape),
        ) {
            SongArtworkBackground(song = song, brush = brush, modifier = Modifier.matchParentSize())
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(16.dp),
            ) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = song.artist.ifBlank { song.album },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.88f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .size(28.dp),
            )
        }
    }
}

@Composable
fun LocalSongPosterCard(
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(18.dp)
    val brush = rememberSongGradientBrush(song)
    Card(
        onClick = onClick,
        modifier = modifier.width(148.dp),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column {
            Box(
                Modifier
                    .height(132.dp)
                    .fillMaxWidth()
                    .clip(shape)
                    .border(1.dp, BeatIQCardStroke, shape),
            ) {
                SongArtworkBackground(song = song, brush = brush, modifier = Modifier.matchParentSize())
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = song.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Text(
                text = song.artist.ifBlank { song.genre },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
fun LocalAlbumCard(
    albumTitle: String,
    artist: String,
    artworkUri: String?,
    fallbackFilePath: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(18.dp)
    val key = "$albumTitle|$artist"
    val brush =
        remember(key) {
            val idx = kotlin.math.abs(key.hashCode()) % 8
            Brush.linearGradient(
                listOf(
                    SongGradientPalette.colors[idx * 2 % SongGradientPalette.colors.size],
                    SongGradientPalette.colors[(idx * 2 + 1) % SongGradientPalette.colors.size],
                ),
            )
        }
    Card(
        onClick = onClick,
        modifier = modifier.width(148.dp),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column {
            Box(
                Modifier
                    .height(132.dp)
                    .fillMaxWidth()
                    .clip(shape)
                    .border(1.dp, BeatIQCardStroke, shape),
            ) {
                val model = artworkUri ?: fallbackFilePath
                if (model != null) {
                    AsyncImage(
                        model = model,
                        contentDescription = albumTitle,
                        modifier = Modifier.matchParentSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(Modifier.matchParentSize().background(brush))
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = albumTitle.ifBlank { "—" },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Text(
                text = artist.ifBlank { "—" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

private object SongGradientPalette {
    val colors =
        listOf(
            Color(0xFF6B4EFF),
            Color(0xFF2D1B4E),
            Color(0xFFFF4D8D),
            Color(0xFF3A0F4A),
            Color(0xFF3EE8C4),
            Color(0xFF163A45),
            Color(0xFF8B5CF6),
            Color(0xFF1E1033),
        )
}

@Composable
fun LocalArtistChipCard(
    artist: String,
    artworkUri: String?,
    fallbackFilePath: String?,
    trackCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    val brush =
        remember(artist) {
            val idx = kotlin.math.abs(artist.hashCode()) % 8
            Brush.linearGradient(
                listOf(
                    SongGradientPalette.colors[idx % SongGradientPalette.colors.size],
                    SongGradientPalette.colors[(idx + 3) % SongGradientPalette.colors.size],
                ),
            )
        }
    Surface(
        onClick = onClick,
        modifier = modifier.width(112.dp),
        shape = shape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.45f),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .border(1.dp, BeatIQCardStroke, CircleShape),
            ) {
                val model = artworkUri ?: fallbackFilePath
                if (model != null) {
                    AsyncImage(
                        model = model,
                        contentDescription = artist,
                        modifier = Modifier.matchParentSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(Modifier.matchParentSize().background(brush)) {
                        Text(
                            text = artist.take(1).uppercase().ifBlank { "?" },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.align(Alignment.Center),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = artist.ifBlank { "—" },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "$trackCount tracks",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SongArtworkBackground(
    song: Song,
    brush: Brush,
    modifier: Modifier = Modifier,
) {
    val model = song.artworkUri ?: song.filePath
    Box(modifier) {
        Box(Modifier.matchParentSize().background(brush))
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = song.title,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}
