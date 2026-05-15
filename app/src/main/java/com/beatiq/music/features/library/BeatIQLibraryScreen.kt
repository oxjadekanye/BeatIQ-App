package com.beatiq.music.features.library

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beatiq.music.R
import com.beatiq.music.core.permissions.AudioReadPermission
import com.beatiq.music.data.model.Song
import com.beatiq.music.presentation.library.AlbumSection
import com.beatiq.music.presentation.library.ArtistSection
import com.beatiq.music.presentation.library.buildAlbumSections
import com.beatiq.music.presentation.library.buildArtistSections
import com.beatiq.music.ui.components.BeatIQBackButton
import com.beatiq.music.ui.components.EmptyHighlightCard
import com.beatiq.music.ui.components.LocalAlbumCard
import com.beatiq.music.ui.components.LocalArtistChipCard
import com.beatiq.music.ui.components.LocalSongPosterCard
import com.beatiq.music.ui.components.PremiumScreenBackground
import com.beatiq.music.ui.components.SectionHeader
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BeatIQLibraryScreen(
    onBack: () -> Unit,
    onSeeAllSongs: () -> Unit,
    onNavigateArtist: (String) -> Unit,
    onNavigateAlbum: (album: String, artist: String) -> Unit,
    onNavigatePlaylist: (String) -> Unit,
    onNavigateGenre: (String) -> Unit,
    onOpenMainDownloads: () -> Unit,
) {
    val context = LocalContext.current
    val tabs = LibraryTab.entries
    var selectedTabIndex by remember { mutableStateOf(LibraryTab.SONGS.ordinal) }

    var hasAudioPermission by remember {
        mutableStateOf(AudioReadPermission.hasAudioReadAccess(context))
    }

    val songs by RepositoryProvider.musicRepository
        .observeAllSongs()
        .collectAsStateWithLifecycle(initialValue = emptyList())

    val albumSections = remember(songs) { buildAlbumSections(songs) }
    val artistSections = remember(songs) { buildArtistSections(songs) }
    val favouriteSongs = remember(songs) { songs.filter { it.isFavorite }.sortedBy { it.title.lowercase() } }
    val genres =
        remember(songs) {
            songs.map { it.genre.trim() }.filter { it.isNotBlank() }.distinct().sortedBy { it.lowercase() }
        }

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
                    LibraryTab.SONGS ->
                        SongsTab(
                            songs = songs,
                            favouriteSongs = favouriteSongs,
                            currentPlayingId = playback.currentSong?.id,
                            onSeeAllSongs = onSeeAllSongs,
                            onSongClick = { song ->
                                scope.launch {
                                    RepositoryProvider.playerRepository.playSongWithQueue(song, songs)
                                }
                            },
                            onToggleFavorite = { id ->
                                scope.launch {
                                    RepositoryProvider.musicRepository.toggleFavorite(id)
                                }
                            },
                        )

                    LibraryTab.ALBUMS ->
                        AlbumsBrowseTab(
                            albums = albumSections,
                            onOpenAlbum = { album ->
                                onNavigateAlbum(album.album, album.artist)
                            },
                        )

                    LibraryTab.ARTISTS ->
                        ArtistsBrowseTab(
                            artists = artistSections,
                            onNavigateArtist = onNavigateArtist,
                            onPlayArtist = { artist ->
                                scope.launch {
                                    val q = artist.songs
                                    RepositoryProvider.playerRepository.playSongWithQueue(q.first(), q)
                                }
                            },
                        )

                    LibraryTab.PLAYLISTS ->
                        PlaylistsTab(
                            onOpenPlaylist = onNavigatePlaylist,
                        )

                    LibraryTab.FOLDERS ->
                        FoldersTab(
                            genres = genres,
                            onGenreClick = onNavigateGenre,
                        )

                    LibraryTab.DOWNLOADS ->
                        DownloadsLibraryTab(
                            onOpenMainDownloads = onOpenMainDownloads,
                        )
                }
            }
        }
    }
}

@Composable
private fun SongsTab(
    songs: List<Song>,
    favouriteSongs: List<Song>,
    currentPlayingId: String?,
    onSeeAllSongs: () -> Unit,
    onSongClick: (Song) -> Unit,
    onToggleFavorite: (String) -> Unit,
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
                onActionClick = onSeeAllSongs,
            )
        }
        item {
            if (favouriteSongs.isEmpty()) {
                Text(
                    text = stringResource(R.string.library_favourites_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    items(favouriteSongs, key = { it.id }) { song ->
                        LocalSongPosterCard(
                            song = song,
                            onClick = { onSongClick(song) },
                        )
                    }
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
                isFavorite = song.isFavorite,
                onFavoriteClick = { onToggleFavorite(song.id) },
                onClick = { onSongClick(song) },
            )
        }
    }
}

@Composable
private fun AlbumsBrowseTab(
    albums: List<AlbumSection>,
    onOpenAlbum: (AlbumSection) -> Unit,
) {
    if (albums.isEmpty()) {
        LibraryPlaceholderTab(body = stringResource(R.string.library_empty_albums_body))
    } else {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 96.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(albums, key = { "${it.album}|${it.artist}" }) { album ->
                LocalAlbumCard(
                    albumTitle = album.album,
                    artist = album.artist,
                    artworkUri = album.artworkUri,
                    fallbackFilePath = album.representativePath,
                    onClick = { onOpenAlbum(album) },
                )
            }
        }
    }
}

@Composable
private fun ArtistsBrowseTab(
    artists: List<ArtistSection>,
    onNavigateArtist: (String) -> Unit,
    onPlayArtist: (ArtistSection) -> Unit,
) {
    if (artists.isEmpty()) {
        LibraryPlaceholderTab(body = stringResource(R.string.library_empty_albums_body))
    } else {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 96.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(artists, key = { it.artist }) { artist ->
                LocalArtistChipCard(
                    artist = artist.artist,
                    artworkUri = artist.artworkUri,
                    fallbackFilePath = artist.representativePath,
                    trackCount = artist.songCount,
                    onClick = {
                        if (artist.songCount > 1) {
                            onNavigateArtist(artist.artist)
                        } else {
                            onPlayArtist(artist)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun PlaylistsTab(
    onOpenPlaylist: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var showCreate by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    val playlists by RepositoryProvider.playlistRepository
        .observePlaylists()
        .collectAsStateWithLifecycle(initialValue = emptyList())

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text(stringResource(R.string.playlist_create_dialog_title)) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.playlist_create_hint)) },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val id = RepositoryProvider.playlistRepository.createPlaylist(newName)
                            newName = ""
                            showCreate = false
                            onOpenPlaylist(id)
                        }
                    },
                ) {
                    Text(stringResource(R.string.playlist_create_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreate = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.library_playlists_header),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                TextButton(onClick = { showCreate = true }) {
                    Text(stringResource(R.string.library_playlists_new))
                }
            }
        }
        if (playlists.isEmpty()) {
            item {
                EmptyHighlightCard(
                    title = stringResource(R.string.library_empty_playlists_title),
                    body = stringResource(R.string.library_empty_playlists_body),
                )
            }
        } else {
            items(playlists, key = { it.id }) { pl ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = pl.name,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Text(
                            text = stringResource(R.string.playlist_track_count, pl.trackCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { onOpenPlaylist(pl.id) }) {
                        Text(stringResource(R.string.action_open))
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
            }
        }
    }
}

@Composable
private fun FoldersTab(
    genres: List<String>,
    onGenreClick: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            EmptyHighlightCard(
                title = stringResource(R.string.library_folders_smart_title),
                body = stringResource(R.string.library_folders_smart_body),
            )
        }
        item {
            Text(
                text = stringResource(R.string.library_folders_by_genre),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (genres.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.library_folders_no_genres),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(genres, key = { it }) { genre ->
                TextButton(
                    onClick = { onGenreClick(genre) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = genre,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadsLibraryTab(
    onOpenMainDownloads: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            EmptyHighlightCard(
                title = stringResource(R.string.library_downloads_card_title),
                body = stringResource(R.string.library_downloads_card_body),
            )
        }
        item {
            Button(onClick = onOpenMainDownloads) {
                Text(stringResource(R.string.library_downloads_open_tab))
            }
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
