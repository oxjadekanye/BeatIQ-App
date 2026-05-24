package com.beatiq.music.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beatiq.music.R
import com.beatiq.music.data.model.PlaylistSummary
import com.beatiq.music.data.model.Song
import com.beatiq.music.features.library.RepositoryProvider
import com.beatiq.music.ui.feedback.showBeatIQMessage
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

@Composable
fun AddToPlaylistDialog(
    song: Song,
    onDismiss: () -> Unit,
    onAdded: (PlaylistSummary) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playlists by RepositoryProvider.playlistRepository
        .observePlaylists()
        .collectAsStateWithLifecycle(initialValue = emptyList())

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(vertical = 8.dp)) {
                Text(
                    text = stringResource(R.string.song_menu_pick_playlist_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                if (playlists.isEmpty()) {
                    Text(
                        text = stringResource(R.string.song_menu_no_playlists),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                } else {
                    Column(
                        Modifier
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        playlists.forEach { pl ->
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        val ok =
                                            RepositoryProvider.playlistRepository.addSongToPlaylist(
                                                pl.id,
                                                song.id,
                                            )
                                        if (ok) {
                                            showBeatIQMessage(
                                                context,
                                                context.getString(R.string.playlist_added_to, pl.name),
                                            )
                                            onAdded(pl)
                                            onDismiss()
                                        } else {
                                            showBeatIQMessage(
                                                context,
                                                context.getString(R.string.playlist_add_failed),
                                            )
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(pl.name, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        }
    }
}
