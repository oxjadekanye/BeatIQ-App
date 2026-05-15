package com.beatiq.music.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.beatiq.music.R
import com.beatiq.music.presentation.home.HomeViewModel
import com.beatiq.music.ui.components.BeatIQBackButton
import com.beatiq.music.ui.components.EmptyHighlightCard
import com.beatiq.music.ui.components.LocalAlbumCard
import com.beatiq.music.ui.components.LocalArtistChipCard
import com.beatiq.music.ui.components.LocalSongHeroCard
import com.beatiq.music.ui.components.LocalSongPosterCard
import com.beatiq.music.ui.components.PremiumScreenBackground
import com.beatiq.music.ui.components.SectionHeader
import java.time.LocalTime

@Composable
fun HomeScreen(onBack: () -> Unit) {
    val vm: HomeViewModel = viewModel(factory = HomeViewModel.Factory)
    val state by vm.uiState.collectAsStateWithLifecycle()
    val greeting = greetingLabel()

    PremiumScreenBackground {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, top = 8.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BeatIQBackButton(onBack = onBack)
                }
            }
            item {
                Column(
                    Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = greeting,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.home_headline),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.home_sub),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (state.recentlyAdded.isEmpty() &&
                state.recentlyPlayed.isEmpty() &&
                state.mostPlayed.isEmpty() &&
                state.favorites.isEmpty()
            ) {
                item {
                    EmptyHighlightCard(
                        title = stringResource(R.string.home_empty_library_title),
                        body = stringResource(R.string.home_empty_library_body),
                        icon = Icons.Outlined.LibraryMusic,
                    )
                }
            }

            item {
                SectionHeader(title = stringResource(R.string.home_section_recently_added))
            }
            item {
                if (state.recentlyAdded.isEmpty()) {
                    Text(
                        text = stringResource(R.string.home_empty_library_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                } else {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    ) {
                        items(state.recentlyAdded, key = { it.id }) { song ->
                            LocalSongHeroCard(
                                song = song,
                                onClick = { vm.playSongWithQueue(song, state.recentlyAdded) },
                            )
                        }
                    }
                }
            }

            item {
                SectionHeader(title = stringResource(R.string.home_section_recently_played))
            }
            item {
                if (state.recentlyPlayed.isEmpty()) {
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
                        items(state.recentlyPlayed, key = { it.id }) { song ->
                            LocalSongPosterCard(
                                song = song,
                                onClick = { vm.playSongWithQueue(song, state.recentlyPlayed) },
                            )
                        }
                    }
                }
            }

            item {
                SectionHeader(title = stringResource(R.string.home_section_most_played))
            }
            item {
                if (state.mostPlayed.isEmpty()) {
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
                        items(state.mostPlayed, key = { it.id }) { song ->
                            LocalSongPosterCard(
                                song = song,
                                onClick = { vm.playSongWithQueue(song, state.mostPlayed) },
                            )
                        }
                    }
                }
            }

            item {
                SectionHeader(title = stringResource(R.string.home_section_favourites))
            }
            item {
                if (state.favorites.isEmpty()) {
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
                        items(state.favorites, key = { it.id }) { song ->
                            LocalSongPosterCard(
                                song = song,
                                onClick = { vm.playSongWithQueue(song, state.favorites) },
                            )
                        }
                    }
                }
            }

            item {
                SectionHeader(title = stringResource(R.string.home_section_albums))
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
                                    val queue = album.songs
                                    vm.playSongWithQueue(queue.first(), queue)
                                },
                            )
                        }
                    }
                }
            }

            item {
                SectionHeader(title = stringResource(R.string.home_section_artists))
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
                                    val queue = artist.songs
                                    vm.playSongWithQueue(queue.first(), queue)
                                },
                            )
                        }
                    }
                }
            }

            item {
                Text(
                    text = "TODO: Integrate licensed streaming catalog when rights and APIs are available.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun greetingLabel(): String {
    val hour = LocalTime.now().hour
    return when (hour) {
        in 5..11 -> stringResource(R.string.home_greet_morning)
        in 12..16 -> stringResource(R.string.home_greet_afternoon)
        in 17..21 -> stringResource(R.string.home_greet_evening)
        else -> stringResource(R.string.home_greet_night)
    }
}
