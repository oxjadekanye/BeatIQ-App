package com.beatiq.app.features.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.beatiq.app.R
import com.beatiq.app.core.lyrics.LrclibLyricsClient
import com.beatiq.app.core.prefs.BeatIQPreferences
import com.beatiq.app.core.utils.formatDurationMs
import com.beatiq.app.features.library.RepositoryProvider
import kotlinx.coroutines.launch
import kotlin.math.max

@Composable
fun FullPlayerScreen(
    onDismiss: () -> Unit,
    onOpenIdentifyMusic: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val prefs = remember { BeatIQPreferences(context) }
    var showLyrics by remember { mutableStateOf(prefs.showLyricsWhilePlaying) }
    var lyricsBody by remember { mutableStateOf<String?>(null) }
    val state by RepositoryProvider.playerRepository.playbackUiState.collectAsStateWithLifecycle()

    val song = state.currentSong
    if (song == null) {
        onDismiss()
        return
    }

    val accent = rememberAccentFromSong(song.id)
    val infinite = rememberInfiniteTransition(label = "vinyl")
    val rotation by infinite.animateFloat(
        initialValue = 0f,
        targetValue = if (state.isPlaying) 360f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 18_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "vinylRotation",
    )

    LaunchedEffect(song.id, song.title, song.artist, song.album, showLyrics) {
        if (!showLyrics) {
            lyricsBody = null
            return@LaunchedEffect
        }
        lyricsBody = null
        val fetched =
            LrclibLyricsClient.fetchLyrics(
                artist = song.artist,
                title = song.title,
                album = song.album,
            )
        lyricsBody =
            fetched?.plainLyrics?.takeIf { it.isNotBlank() }
                ?: fetched?.syncedLyrics?.takeIf { it.isNotBlank() }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(accent.copy(alpha = 0.85f), Color.Black.copy(alpha = 0.92f)),
                ),
            ),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Close",
                    color = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier
                        .padding(8.dp)
                        .background(Color.White.copy(alpha = 0.08f), CircleShape)
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                        .clickable { onDismiss() },
                )
            }
            Spacer(Modifier.height(12.dp))
            AsyncImage(
                model = song.artworkUri ?: song.filePath,
                contentDescription = song.title,
                modifier = Modifier
                    .size(280.dp)
                    .clip(CircleShape)
                    .rotate(rotation),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = song.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Text(
                text = song.artist,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.85f),
            )
            Spacer(Modifier.height(24.dp))
            val duration = max(state.durationMs, 1L)
            val progress = (state.positionMs.toFloat() / duration).coerceIn(0f, 1f)
            Slider(
                value = progress,
                onValueChange = { fraction ->
                    scope.launch {
                        RepositoryProvider.playerRepository.seekTo((fraction * duration).toLong())
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.25f),
                ),
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(formatDurationMs(state.positionMs), color = Color.White.copy(alpha = 0.8f))
                Text(formatDurationMs(duration), color = Color.White.copy(alpha = 0.8f))
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.player_lyrics_toggle),
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.labelLarge,
                )
                IconButton(
                    onClick = {
                        showLyrics = !showLyrics
                        prefs.showLyricsWhilePlaying = showLyrics
                    },
                ) {
                    Icon(
                        Icons.Outlined.Subtitles,
                        contentDescription = stringResource(R.string.player_lyrics_toggle),
                        tint = if (showLyrics) Color.White else Color.White.copy(alpha = 0.45f),
                    )
                }
            }
            if (showLyrics) {
                val body = lyricsBody
                Text(
                    text =
                        body
                            ?: stringResource(R.string.player_lyrics_loading),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.9f),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = {
                    scope.launch {
                        RepositoryProvider.playerRepository.setShuffle(!state.shuffle)
                    }
                }) {
                    Icon(
                        imageVector = Icons.Filled.Shuffle,
                        contentDescription = null,
                        tint = if (state.shuffle) Color.White else Color.White.copy(alpha = 0.45f),
                    )
                }
                IconButton(onClick = { scope.launch { RepositoryProvider.playerRepository.skipToPrevious() } }) {
                    Icon(Icons.Filled.SkipPrevious, null, tint = Color.White)
                }
                IconButton(
                    onClick = {
                        scope.launch {
                            if (state.isPlaying) {
                                RepositoryProvider.playerRepository.pause()
                            } else {
                                RepositoryProvider.playerRepository.resume()
                            }
                        }
                    },
                    modifier = Modifier
                        .size(72.dp)
                        .background(Color.White.copy(alpha = 0.15f), CircleShape),
                ) {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(40.dp),
                    )
                }
                IconButton(onClick = { scope.launch { RepositoryProvider.playerRepository.skipToNext() } }) {
                    Icon(Icons.Filled.SkipNext, null, tint = Color.White)
                }
                IconButton(onClick = {
                    scope.launch {
                        val next = when (state.repeatMode) {
                            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                            else -> Player.REPEAT_MODE_OFF
                        }
                        RepositoryProvider.playerRepository.setRepeatMode(next)
                    }
                }) {
                    val icon = if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat
                    Icon(icon, null, tint = Color.White)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { /* TODO: Dedicated queue sheet */ }) {
                    Icon(Icons.Outlined.QueueMusic, null, tint = Color.White.copy(alpha = 0.85f))
                }
                IconButton(onClick = onOpenIdentifyMusic) {
                    Icon(
                        Icons.Filled.Mic,
                        contentDescription = stringResource(R.string.identify_title),
                        tint = Color.White.copy(alpha = 0.9f),
                    )
                }
                IconButton(onClick = { scope.launch { RepositoryProvider.playerRepository.toggleFavoriteCurrent() } }) {
                    Icon(
                        imageVector = if (song.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = null,
                        tint = Color.White,
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberAccentFromSong(id: String): Color {
    val palette = listOf(
        Color(0xFF6B4EFF),
        Color(0xFFFF6B9D),
        Color(0xFF38BDF8),
        Color(0xFF14B8A6),
        Color(0xFFF59E0B),
    )
    val index = kotlin.math.abs(id.hashCode()) % palette.size
    return remember(id) { palette[index] }
}
