package com.beatiq.music.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beatiq.music.R
import com.beatiq.music.core.library.filterSongsByQuery
import com.beatiq.music.data.model.Song
import com.beatiq.music.features.library.RepositoryProvider
import com.beatiq.music.features.library.SongListRow
import com.beatiq.music.ui.components.AddToPlaylistDialog
import com.beatiq.music.ui.components.BeatIQBackButton
import com.beatiq.music.ui.components.DiscoverSearchBar
import com.beatiq.music.ui.components.PremiumScreenBackground
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

@Composable
fun LibraryFavouritesScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var playlistPickSong by remember { mutableStateOf<Song?>(null) }
    val all by RepositoryProvider.musicRepository
        .observeFavoriteSongs()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val filtered = remember(all, query) { filterSongsByQuery(all, query) }
    val playback by RepositoryProvider.playerRepository
        .playbackUiState
        .collectAsStateWithLifecycle()

    playlistPickSong?.let { song ->
        AddToPlaylistDialog(
            song = song,
            onDismiss = { playlistPickSong = null },
        )
    }

    PremiumScreenBackground {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 96.dp),
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
                        text = stringResource(R.string.library_favourites_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
            item {
                DiscoverSearchBar(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = stringResource(R.string.library_search_favourites_placeholder),
                )
            }
            if (filtered.isEmpty()) {
                item {
                    Text(
                        text =
                            if (all.isEmpty()) {
                                stringResource(R.string.library_favourites_empty)
                            } else {
                                stringResource(R.string.library_search_no_results)
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            }
            items(filtered, key = { it.id }) { song ->
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SongListRow(
                        song = song,
                        isActive = song.id == playback.currentSong?.id,
                        isFavorite = true,
                        onFavoriteClick = {
                            scope.launch { RepositoryProvider.musicRepository.toggleFavorite(song.id) }
                        },
                        onClick = {
                            scope.launch {
                                RepositoryProvider.playerRepository.playSongWithQueue(song, filtered)
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { playlistPickSong = song }) {
                        Icon(
                            Icons.Outlined.PlaylistAdd,
                            contentDescription = stringResource(R.string.song_menu_add_playlist),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}
