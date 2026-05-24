package com.beatiq.music.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material3.FilterChip
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

private enum class LibrarySongSort {
    TITLE,
    ARTIST,
    ALBUM,
    DATE_ADDED,
}

@Composable
fun LibraryAllSongsScreen(
    genreFilter: String?,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var sort by remember { mutableStateOf(LibrarySongSort.TITLE) }
    var query by remember { mutableStateOf("") }
    var playlistPickSong by remember { mutableStateOf<Song?>(null) }
    val all by RepositoryProvider.musicRepository
        .observeAllSongs()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val genreFiltered =
        remember(genreFilter, all) {
            if (genreFilter.isNullOrBlank()) {
                all
            } else {
                all.filter { it.genre.equals(genreFilter, ignoreCase = true) }
            }
        }
    val searched = remember(genreFiltered, query) { filterSongsByQuery(genreFiltered, query) }
    val sorted =
        remember(sort, searched) {
            when (sort) {
                LibrarySongSort.TITLE -> searched.sortedBy { it.title.lowercase() }
                LibrarySongSort.ARTIST -> searched.sortedWith(
                    compareBy({ it.artist.lowercase() }, { it.title.lowercase() }),
                )
                LibrarySongSort.ALBUM -> searched.sortedWith(
                    compareBy({ it.album.lowercase() }, { it.title.lowercase() }),
                )
                LibrarySongSort.DATE_ADDED -> searched.sortedByDescending { it.dateAdded }
            }
        }
    val playback by RepositoryProvider.playerRepository
        .playbackUiState
        .collectAsStateWithLifecycle()

    val title =
        if (genreFilter.isNullOrBlank()) {
            stringResource(R.string.library_all_songs_title)
        } else {
            stringResource(R.string.library_genre_songs_title, genreFilter)
        }

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
                        text = title,
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
                    text = stringResource(R.string.library_sort_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
                )
            }
            item {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                ) {
                    item {
                        FilterChip(
                            selected = sort == LibrarySongSort.TITLE,
                            onClick = { sort = LibrarySongSort.TITLE },
                            label = { Text(stringResource(R.string.library_sort_title)) },
                        )
                    }
                    item {
                        FilterChip(
                            selected = sort == LibrarySongSort.ARTIST,
                            onClick = { sort = LibrarySongSort.ARTIST },
                            label = { Text(stringResource(R.string.library_sort_artist)) },
                        )
                    }
                    item {
                        FilterChip(
                            selected = sort == LibrarySongSort.ALBUM,
                            onClick = { sort = LibrarySongSort.ALBUM },
                            label = { Text(stringResource(R.string.library_sort_album)) },
                        )
                    }
                    item {
                        FilterChip(
                            selected = sort == LibrarySongSort.DATE_ADDED,
                            onClick = { sort = LibrarySongSort.DATE_ADDED },
                            label = { Text(stringResource(R.string.library_sort_date_added)) },
                        )
                    }
                }
            }
            if (sorted.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.library_search_no_results),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            }
            items(sorted, key = { it.id }) { song ->
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
                                RepositoryProvider.playerRepository.playSongWithQueue(song, sorted)
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
