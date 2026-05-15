package com.beatiq.music.ui.screen

import androidx.compose.runtime.Composable
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import com.beatiq.music.features.library.BeatIQLibraryScreen
import com.beatiq.music.navigation.BeatIQInnerRoutes
import com.beatiq.music.navigation.NavEncoding

@Composable
fun LibraryScreen(
    onBack: () -> Unit,
    innerNavController: NavHostController,
) {
    BeatIQLibraryScreen(
        onBack = onBack,
        onSeeAllSongs = { innerNavController.navigate(BeatIQInnerRoutes.LIBRARY_ALL_SONGS) },
        onNavigateArtist = { artist ->
            innerNavController.navigate(BeatIQInnerRoutes.artistTracks(NavEncoding.encode(artist)))
        },
        onNavigateAlbum = { album, artist ->
            val key = NavEncoding.encodeAlbumArtist(album, artist)
            innerNavController.navigate(BeatIQInnerRoutes.albumTracks(key))
        },
        onNavigatePlaylist = { id ->
            innerNavController.navigate(BeatIQInnerRoutes.playlistDetail(id))
        },
        onNavigateGenre = { genre ->
            innerNavController.navigate(BeatIQInnerRoutes.libraryGenre(NavEncoding.encode(genre)))
        },
        onOpenMainDownloads = {
            innerNavController.navigate(BeatIQInnerRoutes.DOWNLOADS) {
                popUpTo(innerNavController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        },
    )
}
