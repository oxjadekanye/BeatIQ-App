package com.beatiq.app.ui.screen

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.beatiq.app.R
import com.beatiq.app.features.library.RepositoryProvider
import com.beatiq.app.features.library.SongListRow
import com.beatiq.app.ui.components.BeatIQBackButton
import com.beatiq.app.ui.components.PremiumScreenBackground
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

@Composable
fun AlbumTracksScreen(
    albumTitle: String,
    albumArtist: String,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val all by RepositoryProvider.musicRepository
        .observeAllSongs()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val tracks =
        remember(albumTitle, albumArtist, all) {
            all
                .filter {
                    it.album == albumTitle && it.artist == albumArtist
                }
                .sortedBy { it.title.lowercase() }
        }
    val playback by RepositoryProvider.playerRepository
        .playbackUiState
        .collectAsStateWithLifecycle()

    PremiumScreenBackground {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 96.dp),
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
                        text = albumTitle,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
            item {
                Text(
                    text = albumArtist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
            item {
                Text(
                    text = stringResource(R.string.album_tracks_subtitle, tracks.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            items(tracks, key = { it.id }) { song ->
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
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                )
            }
        }
    }
}
