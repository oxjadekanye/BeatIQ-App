package com.beatiq.music.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beatiq.music.R
import com.beatiq.music.features.library.RepositoryProvider
import com.beatiq.music.features.library.SongListRow
import com.beatiq.music.ui.components.BeatIQBackButton
import com.beatiq.music.ui.components.PremiumScreenBackground
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

@Composable
fun PlaylistDetailScreen(
    playlistId: String,
    onBack: () -> Unit,
    onAddTracks: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val playlists by RepositoryProvider.playlistRepository
        .observePlaylists()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val defaultPlaylistTitle = stringResource(R.string.playlist_default_name)
    val title =
        remember(playlistId, playlists, defaultPlaylistTitle) {
            playlists.find { it.id == playlistId }?.name ?: defaultPlaylistTitle
        }
    val tracks by RepositoryProvider.playlistRepository
        .observeSongsInPlaylist(playlistId)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val playback by RepositoryProvider.playerRepository
        .playbackUiState
        .collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize()) {
        PremiumScreenBackground {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 4.dp, top = 8.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BeatIQBackButton(onBack = onBack)
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
                item {
                    Text(
                        text = stringResource(R.string.playlist_track_count, tracks.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                }
                items(tracks, key = { it.id }) { song ->
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SongListRow(
                            song = song,
                            isActive = song.id == playback.currentSong?.id,
                            isFavorite = song.isFavorite,
                            onFavoriteClick = {
                                scope.launch { RepositoryProvider.musicRepository.toggleFavorite(song.id) }
                            },
                            onClick = {
                                scope.launch {
                                    RepositoryProvider.playerRepository.playSongWithQueue(song, tracks)
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = {
                                scope.launch {
                                    RepositoryProvider.playlistRepository.removeSongFromPlaylist(playlistId, song.id)
                                }
                            },
                        ) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = stringResource(R.string.playlist_remove_track_cd),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = onAddTracks,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(20.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.playlist_add_tracks_cd))
        }
    }
}
