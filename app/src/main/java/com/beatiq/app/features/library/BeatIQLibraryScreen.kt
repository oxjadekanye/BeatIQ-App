package com.beatiq.app.features.library

import android.Manifest
import android.os.Build
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beatiq.app.R
import com.beatiq.app.core.permissions.AudioReadPermission
import com.beatiq.app.data.model.Song
import com.beatiq.app.ui.components.BeatIQBackButton
import com.beatiq.app.ui.components.EmptyHighlightCard
import com.beatiq.app.ui.components.PremiumScreenBackground
import com.beatiq.app.ui.components.SectionHeader
import com.beatiq.app.ui.components.TrendingMusicCard
import com.beatiq.app.ui.data.MusicHighlight
import com.beatiq.app.ui.data.MockCatalog
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BeatIQLibraryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val favourites = remember { MockCatalog.favourites }
    val tabs = LibraryTab.entries
    var selectedTabIndex by remember { mutableStateOf(LibraryTab.SONGS.ordinal) }

    var hasAudioPermission by remember {
        mutableStateOf(AudioReadPermission.hasAudioReadAccess(context))
    }

    val songs by RepositoryProvider.musicRepository
        .observeAllSongs()
        .collectAsStateWithLifecycle(initialValue = emptyList())

    val playback by RepositoryProvider.playerRepository
        .playbackUiState
        .collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()

    val permissionToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasAudioPermission = granted
    }

    LaunchedEffect(hasAudioPermission) {
        if (hasAudioPermission) {
            RepositoryProvider.scannerRepository.scanLibrary()
        }
    }

    PremiumScreenBackground {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, top = 8.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BeatIQBackButton(onBack = onBack)
                Text(
                    text = stringResource(R.string.screen_library),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }

            if (!hasAudioPermission) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.library_permission_rationale),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { permissionLauncher.launch(permissionToRequest) }) {
                        Text(text = stringResource(R.string.library_permission_cta))
                    }
                }
            }

            PrimaryScrollableTabRow(
                selectedTabIndex = selectedTabIndex,
                edgePadding = 12.dp,
            ) {
                tabs.forEachIndexed { index, tab ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        text = {
                            Text(
                                text = stringResource(tab.labelRes),
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                            )
                        },
                    )
                }
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
            )

            val selected = tabs[selectedTabIndex]
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                when (selected) {
                    LibraryTab.SONGS -> SongsTab(
                        songs = songs,
                        favourites = favourites,
                        currentPlayingId = playback.currentSong?.id,
                        onSongClick = { song ->
                            scope.launch {
                                RepositoryProvider.playerRepository.playSongWithQueue(song, songs)
                            }
                        },
                    )

                    LibraryTab.ALBUMS -> LibraryPlaceholderTab(
                        body = stringResource(R.string.library_tab_placeholder_body),
                    )

                    LibraryTab.ARTISTS -> LibraryPlaceholderTab(
                        body = stringResource(R.string.library_tab_placeholder_body),
                    )

                    LibraryTab.PLAYLISTS -> LibraryPlaceholderTab(
                        body = stringResource(R.string.library_tab_placeholder_body),
                    )

                    LibraryTab.FOLDERS -> LibraryPlaceholderTab(
                        body = stringResource(R.string.library_tab_placeholder_body),
                    )

                    LibraryTab.DOWNLOADS -> LibraryPlaceholderTab(
                        body = stringResource(R.string.library_tab_downloads_placeholder_body),
                    )
                }
            }
        }
    }
}

@Composable
private fun SongsTab(
    songs: List<Song>,
    favourites: List<MusicHighlight>,
    currentPlayingId: String?,
    onSongClick: (Song) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionHeader(
                title = stringResource(R.string.library_section_favourites),
                actionLabel = stringResource(R.string.action_see_all),
                onActionClick = { /* TODO: favourites collection */ },
            )
        }
        item {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(favourites, key = { it.id }) { item ->
                    TrendingMusicCard(item = item)
                }
            }
        }
        item {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.library_section_tracks),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        items(songs, key = { it.id }) { song ->
            SongListRow(
                song = song,
                isActive = song.id == currentPlayingId,
                onClick = { onSongClick(song) },
            )
        }
    }
}

@Composable
private fun LibraryPlaceholderTab(
    body: String,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            EmptyHighlightCard(
                title = stringResource(R.string.library_tab_placeholder_title),
                body = body,
            )
        }
    }
}
