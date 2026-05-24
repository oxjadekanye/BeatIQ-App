package com.beatiq.music.ui.components

import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.AsyncImage
import com.beatiq.music.R
import com.beatiq.music.core.browser.DirectAudioDownloadDetector
import com.beatiq.music.data.model.OnlineRecommendation
import com.beatiq.music.data.model.RecommendationProvider
import com.beatiq.music.features.library.RepositoryProvider
import com.beatiq.music.ui.feedback.showBeatIQMessage
import kotlinx.coroutines.launch

@Composable
fun OnlineTrackDetailSheet(
    track: OnlineRecommendation,
    onDismiss: () -> Unit,
    onOpenInternalBrowser: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var previewPlaying by remember { mutableStateOf(false) }

    val previewPlayer =
        remember {
            ExoPlayer.Builder(context).build().apply {
                repeatMode = Player.REPEAT_MODE_OFF
            }
        }

    DisposableEffect(previewPlayer) {
        onDispose {
            previewPlayer.release()
        }
    }

    val accent = artworkAccentColor(track.artworkUrl)

    fun openExternalBrowser() {
        runCatching {
            CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(track.browserUrl))
        }.onFailure {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(track.browserUrl)))
        }
    }

    Surface(
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 10.dp,
        shadowElevation = 12.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .background(
                    Brush.verticalGradient(
                        colors =
                            listOf(
                                accent.copy(alpha = 0.12f),
                                MaterialTheme.colorScheme.surface,
                            ),
                    ),
                )
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = track.artworkUrl,
                    contentDescription = track.title,
                    modifier =
                        Modifier
                            .size(88.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop,
                )
                Column(Modifier.padding(start = 14.dp).weight(1f)) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = track.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (track.album.isNotBlank()) {
                        Text(
                            text = track.album,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val label =
                        track.sourceLabel
                            ?: when (track.provider) {
                                RecommendationProvider.YOUTUBE_MUSIC -> stringResource(R.string.home_youtube_source)
                                else -> stringResource(R.string.home_deezer_source)
                            }
                    Text(
                        text = listOfNotNull(track.genreHint.takeIf { it.isNotBlank() }, label)
                            .joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (track.isYoutubeOnly) {
                Text(
                    text = stringResource(R.string.home_opens_in_youtube),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (track.opensInInternalBrowser) {
                Button(
                    onClick = {
                        onOpenInternalBrowser(track.browserUrl)
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.home_open_in_beatiq_browser))
                }
                OutlinedButton(
                    onClick = {
                        openExternalBrowser()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.home_open_external_browser))
                }
            } else {
                Button(
                    onClick = {
                        openExternalBrowser()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.home_open_on_deezer))
                }
                val preview = track.previewUrl
                if (track.isPlayablePreview && !preview.isNullOrBlank()) {
                    OutlinedButton(
                        onClick = {
                            if (previewPlaying) {
                                previewPlayer.pause()
                                previewPlaying = false
                            } else {
                                previewPlayer.setMediaItem(MediaItem.fromUri(preview))
                                previewPlayer.prepare()
                                previewPlayer.play()
                                previewPlaying = true
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(
                                if (previewPlaying) {
                                    R.string.home_stop_preview
                                } else {
                                    R.string.home_play_preview
                                },
                            ),
                        )
                    }
                }
                if (!preview.isNullOrBlank() && DirectAudioDownloadDetector.looksLikeDirectAudioFileUrl(preview)) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val result =
                                    RepositoryProvider.downloadsRepository.enqueueLegalFileDownload(
                                        preview,
                                        "${track.artist} — ${track.title}",
                                    )
                                if (result.isSuccess) {
                                    showBeatIQMessage(context, context.getString(R.string.home_download_queued))
                                } else {
                                    showBeatIQMessage(
                                        context,
                                        context.getString(R.string.home_download_unavailable),
                                    )
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.home_download_preview))
                    }
                } else if (!track.isPlayablePreview) {
                    Text(
                        text = stringResource(R.string.home_download_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_close))
            }
        }
    }
}
