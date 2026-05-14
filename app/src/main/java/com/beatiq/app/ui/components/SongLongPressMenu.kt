package com.beatiq.app.ui.components

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import com.beatiq.app.R
import com.beatiq.app.data.model.Song
import com.beatiq.app.features.library.RepositoryProvider
import com.beatiq.app.navigation.BeatIQInnerRoutes
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun SongLongPressMenuHost(
    song: Song?,
    onDismiss: () -> Unit,
    innerNavController: NavHostController,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pickPlaylist by remember { mutableStateOf(false) }
    var showMoveHint by remember { mutableStateOf(false) }
    var showFolderHint by remember { mutableStateOf(false) }
    val playlists by RepositoryProvider.playlistRepository
        .observePlaylists()
        .collectAsStateWithLifecycle(initialValue = emptyList())

    if (showMoveHint) {
        AlertDialog(
            onDismissRequest = { showMoveHint = false },
            title = { Text(stringResource(R.string.song_menu_move)) },
            text = { Text(stringResource(R.string.song_menu_move_hint)) },
            confirmButton = {
                TextButton(onClick = { showMoveHint = false }) {
                    Text(stringResource(R.string.song_menu_ok))
                }
            },
        )
    }
    if (showFolderHint) {
        AlertDialog(
            onDismissRequest = { showFolderHint = false },
            title = { Text(stringResource(R.string.song_menu_create_folder)) },
            text = { Text(stringResource(R.string.song_menu_create_folder_hint)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showFolderHint = false
                        innerNavController.navigate(BeatIQInnerRoutes.LIBRARY) {
                            popUpTo(innerNavController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                        onDismiss()
                    },
                ) {
                    Text(stringResource(R.string.song_menu_library_folders))
                }
            },
            dismissButton = {
                TextButton(onClick = { showFolderHint = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (song == null) return

    if (pickPlaylist) {
        AlertDialog(
            onDismissRequest = { pickPlaylist = false },
            title = { Text(stringResource(R.string.song_menu_pick_playlist_title)) },
            text = {
                if (playlists.isEmpty()) {
                    Text(stringResource(R.string.song_menu_no_playlists))
                } else {
                    LazyColumn {
                        items(playlists, key = { it.id }) { pl ->
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        RepositoryProvider.playlistRepository.addSongToPlaylist(pl.id, song.id)
                                        pickPlaylist = false
                                        onDismiss()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(pl.name, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { pickPlaylist = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
        return
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(vertical = 8.dp)) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                TextButton(
                    onClick = {
                        scope.launch {
                            RepositoryProvider.playerRepository.playSongWithQueue(song, listOf(song))
                            onDismiss()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.song_menu_play))
                }
                TextButton(
                    onClick = {
                        scope.launch {
                            RepositoryProvider.musicRepository.toggleFavorite(song.id)
                            onDismiss()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (song.isFavorite) {
                            stringResource(R.string.song_menu_remove_favourite)
                        } else {
                            stringResource(R.string.song_menu_add_favourite)
                        },
                    )
                }
                TextButton(
                    onClick = { pickPlaylist = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.song_menu_add_playlist))
                }
                TextButton(
                    onClick = { showMoveHint = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.song_menu_move))
                }
                TextButton(
                    onClick = { showFolderHint = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.song_menu_create_folder))
                }
                TextButton(
                    onClick = {
                        innerNavController.navigate(BeatIQInnerRoutes.LIBRARY) {
                            popUpTo(innerNavController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.song_menu_library_folders))
                }
                TextButton(
                    onClick = {
                        val path = song.filePath
                        if (path.startsWith("/")) {
                            val f = File(path)
                            if (f.exists()) {
                                val uri =
                                    FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        f,
                                    )
                                val send =
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "audio/*"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                context.startActivity(Intent.createChooser(send, song.title))
                            }
                        }
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.song_menu_share))
                }
                TextButton(
                    onClick = { onDismiss() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        }
    }
}
