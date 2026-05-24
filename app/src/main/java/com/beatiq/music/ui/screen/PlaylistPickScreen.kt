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
import androidx.compose.material.icons.outlined.Add
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beatiq.music.R
import com.beatiq.music.core.library.filterSongsByQuery
import com.beatiq.music.features.library.RepositoryProvider
import com.beatiq.music.features.library.SongListRow
import com.beatiq.music.ui.components.BeatIQBackButton
import com.beatiq.music.ui.components.DiscoverSearchBar
import com.beatiq.music.ui.components.EmptyHighlightCard
import com.beatiq.music.ui.components.PremiumScreenBackground
import com.beatiq.music.ui.feedback.showBeatIQMessage
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

@Composable
fun PlaylistPickScreen(
    playlistId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    val all by RepositoryProvider.musicRepository
        .observeAllSongs()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val inPlaylist by RepositoryProvider.playlistRepository
        .observeSongsInPlaylist(playlistId)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val inIds = remember(inPlaylist) { inPlaylist.map { it.id }.toSet() }
    val available =
        remember(all, inIds, query) {
            filterSongsByQuery(all.filter { it.id !in inIds }, query)
                .sortedBy { it.title.lowercase() }
        }
    val playback by RepositoryProvider.playerRepository
        .playbackUiState
        .collectAsStateWithLifecycle()

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
                        text = stringResource(R.string.playlist_pick_title),
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
                    placeholder = stringResource(R.string.library_search_all_placeholder),
                )
            }
            item {
                Text(
                    text = stringResource(R.string.playlist_pick_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
            if (available.isEmpty()) {
                item {
                    EmptyHighlightCard(
                        title = stringResource(R.string.playlist_pick_empty_title),
                        body =
                            if (all.all { inIds.contains(it.id) }) {
                                stringResource(R.string.playlist_pick_all_added)
                            } else {
                                stringResource(R.string.library_search_no_results)
                            },
                    )
                }
            }
            items(available, key = { it.id }) { song ->
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
                                val ok =
                                    RepositoryProvider.playlistRepository.addSongToPlaylist(
                                        playlistId,
                                        song.id,
                                    )
                                if (ok) {
                                    showBeatIQMessage(
                                        context,
                                        context.getString(R.string.playlist_added_one, song.title),
                                    )
                                } else {
                                    showBeatIQMessage(
                                        context,
                                        context.getString(R.string.playlist_add_failed),
                                    )
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = {
                            scope.launch {
                                val ok =
                                    RepositoryProvider.playlistRepository.addSongToPlaylist(
                                        playlistId,
                                        song.id,
                                    )
                                if (ok) {
                                    showBeatIQMessage(
                                        context,
                                        context.getString(R.string.playlist_added_one, song.title),
                                    )
                                } else {
                                    showBeatIQMessage(
                                        context,
                                        context.getString(R.string.playlist_add_failed),
                                    )
                                }
                            }
                        },
                    ) {
                        Icon(
                            Icons.Outlined.Add,
                            contentDescription = stringResource(R.string.playlist_add_one_cd),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}
