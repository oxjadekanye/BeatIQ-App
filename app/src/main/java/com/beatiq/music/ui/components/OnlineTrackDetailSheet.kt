package com.beatiq.music.ui.components

import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.beatiq.music.R
import com.beatiq.music.core.browser.DirectAudioDownloadDetector
import com.beatiq.music.data.model.OnlineRecommendation
import com.beatiq.music.features.library.RepositoryProvider
import com.beatiq.music.ui.feedback.showBeatIQMessage
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

@Composable
fun OnlineTrackDetailSheet(
    track: OnlineRecommendation,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Surface(
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(20.dp),
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
                    Text(
                        text = track.genreHint,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Button(
                onClick = {
                    runCatching {
                        CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(track.browserUrl))
                    }.onFailure {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(track.browserUrl)))
                    }
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.home_open_in_browser))
            }
            val preview = track.previewUrl
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
                                showBeatIQMessage(context, context.getString(R.string.home_download_unavailable))
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.home_download_preview))
                }
            } else {
                Text(
                    text = stringResource(R.string.home_download_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_close))
            }
        }
    }
}
