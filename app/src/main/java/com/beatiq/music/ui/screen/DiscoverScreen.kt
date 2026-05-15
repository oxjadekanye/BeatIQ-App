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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.beatiq.music.R
import android.net.Uri
import com.beatiq.music.core.browser.DirectAudioDownloadDetector
import com.beatiq.music.core.browser.HttpsUrlValidator
import com.beatiq.music.features.library.RepositoryProvider
import kotlinx.coroutines.launch
import com.beatiq.music.presentation.discover.DiscoverViewModel
import com.beatiq.music.ui.components.BeatIQBackButton
import com.beatiq.music.ui.components.DiscoverSearchBar
import com.beatiq.music.ui.components.GenreFilterChip
import com.beatiq.music.ui.components.LocalAlbumCard
import com.beatiq.music.ui.components.LocalArtistChipCard
import com.beatiq.music.ui.components.LocalSongPosterCard
import com.beatiq.music.ui.components.PremiumScreenBackground
import com.beatiq.music.ui.components.SectionHeader
import com.beatiq.music.ui.components.EmptyHighlightCard
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SearchOff

@Composable
fun DiscoverScreen(
    onBack: () -> Unit,
    onOpenOnlineBrowser: () -> Unit,
    onOpenIdentifyMusic: () -> Unit,
) {
    val vm: DiscoverViewModel = viewModel(factory = DiscoverViewModel.Factory)
    val state by vm.uiState.collectAsStateWithLifecycle()
    val allLabel = stringResource(R.string.discover_genre_all)
    val genreLabels = listOf(allLabel) + state.genres
    var selectedGenreIndex by remember { mutableIntStateOf(0) }

    val scope = rememberCoroutineScope()
    var pasteUrl by remember { mutableStateOf("") }
    var pendingDownload by remember { mutableStateOf<String?>(null) }

    pendingDownload?.let { url ->
        AlertDialog(
            onDismissRequest = { pendingDownload = null },
            title = { Text(stringResource(R.string.browser_download_dialog_title)) },
            text = {
                Text(
                    stringResource(R.string.browser_disclaimer),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val u = url
                        pendingDownload = null
                        scope.launch {
                            val title = Uri.parse(u).lastPathSegment?.take(80) ?: "download"
                            RepositoryProvider.downloadsRepository.enqueueLegalFileDownload(u, title)
                        }
                    },
                ) {
                    Text(stringResource(R.string.browser_download_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDownload = null }) {
                    Text(stringResource(R.string.browser_download_cancel))
                }
            },
        )
    }

    PremiumScreenBackground {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
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
                        text = stringResource(R.string.screen_discover),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
            item {
                DiscoverSearchBar(
                    value = state.searchQuery,
                    onValueChange = { vm.setSearchQuery(it) },
                    placeholder = stringResource(R.string.discover_search_placeholder),
                )
            }
            item {
                Text(
                    text = stringResource(R.string.discover_section_genres),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
            item {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                ) {
                    itemsIndexed(genreLabels) { index, label ->
                        GenreFilterChip(
                            label = label,
                            selected = index == selectedGenreIndex,
                            onClick = {
                                selectedGenreIndex = index
                                vm.selectGenre(if (index == 0) null else label)
                            },
                        )
                    }
                }
            }
            item {
                SectionHeader(title = stringResource(R.string.discover_section_online_browser))
            }
            item {
                Text(
                    text = stringResource(R.string.discover_online_browser_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                Button(
                    onClick = onOpenOnlineBrowser,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                ) {
                    Text(stringResource(R.string.discover_open_online_browser))
                }
            }
            item {
                SectionHeader(title = stringResource(R.string.discover_identify_section))
            }
            item {
                Text(
                    text = stringResource(R.string.discover_identify_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                Button(
                    onClick = onOpenIdentifyMusic,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                ) {
                    Text(stringResource(R.string.discover_open_identify))
                }
            }
            item {
                SectionHeader(title = stringResource(R.string.discover_paste_title))
            }
            item {
                Text(
                    text = stringResource(R.string.discover_paste_explain),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                OutlinedTextField(
                    value = pasteUrl,
                    onValueChange = { pasteUrl = it },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.discover_paste_hint)) },
                )
                Button(
                    onClick = {
                        val normalized = HttpsUrlValidator.normalizedHttpsUrl(pasteUrl.trim())
                        if (normalized != null &&
                            DirectAudioDownloadDetector.looksLikeDirectAudioFileUrl(normalized)
                        ) {
                            pendingDownload = normalized
                        }
                    },
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                ) {
                    Text(stringResource(R.string.discover_paste_submit))
                }
            }
            item {
                SectionHeader(title = stringResource(R.string.discover_section_suggested))
            }
            item {
                if (state.suggestedSongs.isEmpty()) {
                    Text(
                        text = stringResource(R.string.home_empty_library_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                } else {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    ) {
                        items(state.suggestedSongs, key = { it.id }) { song ->
                            LocalSongPosterCard(
                                song = song,
                                onClick = {
                                    vm.playSongWithQueue(song, state.suggestedSongs)
                                },
                            )
                        }
                    }
                }
            }
            item {
                SectionHeader(title = stringResource(R.string.discover_section_results))
            }
            item {
                if (state.filteredSongs.isEmpty()) {
                    EmptyHighlightCard(
                        title = stringResource(R.string.discover_empty_search_title),
                        body = stringResource(R.string.discover_empty_search_body),
                        icon = Icons.Outlined.SearchOff,
                    )
                } else {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    ) {
                        items(state.filteredSongs, key = { it.id }) { song ->
                            LocalSongPosterCard(
                                song = song,
                                onClick = {
                                    vm.playSongWithQueue(song, state.filteredSongs)
                                },
                            )
                        }
                    }
                }
            }
            item {
                SectionHeader(title = stringResource(R.string.discover_section_albums))
            }
            item {
                if (state.albums.isEmpty()) {
                    Text(
                        text = stringResource(R.string.home_empty_library_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                } else {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    ) {
                        items(state.albums, key = { "${it.album}|${it.artist}" }) { album ->
                            LocalAlbumCard(
                                albumTitle = album.album,
                                artist = album.artist,
                                artworkUri = album.artworkUri,
                                fallbackFilePath = album.representativePath,
                                onClick = {
                                    val q = album.songs
                                    vm.playSongWithQueue(q.first(), q)
                                },
                            )
                        }
                    }
                }
            }
            item {
                SectionHeader(title = stringResource(R.string.discover_section_artists))
            }
            item {
                if (state.artists.isEmpty()) {
                    Text(
                        text = stringResource(R.string.home_empty_library_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                } else {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    ) {
                        items(state.artists, key = { it.artist }) { artist ->
                            LocalArtistChipCard(
                                artist = artist.artist,
                                artworkUri = artist.artworkUri,
                                fallbackFilePath = artist.representativePath,
                                trackCount = artist.songCount,
                                onClick = {
                                    val q = artist.songs
                                    vm.playSongWithQueue(q.first(), q)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
