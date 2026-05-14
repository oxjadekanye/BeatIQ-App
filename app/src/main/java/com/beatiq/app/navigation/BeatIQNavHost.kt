package com.beatiq.app.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.beatiq.app.BeatIQBrandSplashScreen
import com.beatiq.app.BeatIQLandingScreen
import com.beatiq.app.R
import com.beatiq.app.core.auth.AuthPreferences
import com.beatiq.app.data.model.Song
import com.beatiq.app.features.library.RepositoryProvider
import com.beatiq.app.features.player.FullPlayerScreen
import com.beatiq.app.features.player.MiniPlayerBar
import com.beatiq.app.ui.screen.AlbumTracksScreen
import com.beatiq.app.ui.screen.ArtistTracksScreen
import com.beatiq.app.ui.screen.DiscoverScreen
import com.beatiq.app.ui.screen.DownloadsScreen
import com.beatiq.app.ui.screen.HomeScreen
import com.beatiq.app.ui.screen.LibraryAllSongsScreen
import com.beatiq.app.ui.screen.LibraryScreen
import com.beatiq.app.ui.screen.OnlineMusicBrowserScreen
import com.beatiq.app.ui.screen.PlaylistDetailScreen
import com.beatiq.app.ui.screen.PlaylistPickScreen
import com.beatiq.app.ui.screen.ProfileScreen
import com.beatiq.app.ui.screen.RegisterAccountScreen
import com.beatiq.app.ui.screen.SettingsNotificationsScreen
import com.beatiq.app.ui.screen.SettingsPlaybackScreen
import com.beatiq.app.ui.screen.SettingsPrivacyScreen
import com.beatiq.app.ui.screen.SettingsStorageScreen
import com.beatiq.app.ui.LocalSongLongPress
import com.beatiq.app.ui.components.SongLongPressMenuHost
import com.beatiq.app.ui.screen.IdentifyMusicScreen
import kotlinx.coroutines.launch

private data class TabItem(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
)

@Composable
fun BeatIQNavHost(outerNavController: NavHostController) {
    val app = LocalContext.current.applicationContext as android.app.Application
    NavHost(
        navController = outerNavController,
        startDestination = BeatIQRoutes.BRAND_SPLASH,
    ) {
        composable(BeatIQRoutes.BRAND_SPLASH) {
            BeatIQBrandSplashScreen(
                onGoLanding = {
                    outerNavController.navigate(BeatIQRoutes.LANDING) {
                        popUpTo(BeatIQRoutes.BRAND_SPLASH) { inclusive = true }
                    }
                },
                onGoMainHome = {
                    outerNavController.navigate(BeatIQRoutes.mainRoute(BeatIQInnerRoutes.HOME)) {
                        popUpTo(BeatIQRoutes.BRAND_SPLASH) { inclusive = true }
                    }
                },
            )
        }
        composable(BeatIQRoutes.LANDING) {
            BeatIQLandingScreen(
                onRegister = {
                    outerNavController.navigate(BeatIQRoutes.REGISTER)
                },
                onSignedIn = { startTab ->
                    outerNavController.navigate(BeatIQRoutes.mainRoute(startTab)) {
                        popUpTo(BeatIQRoutes.LANDING) { inclusive = true }
                    }
                },
            )
        }
        composable(BeatIQRoutes.REGISTER) {
            RegisterAccountScreen(
                onBack = { outerNavController.popBackStack() },
                onRegistered = { outerNavController.popBackStack() },
            )
        }
        composable(
            route = BeatIQRoutes.MAIN_PATTERN,
            arguments = listOf(
                navArgument("startTab") {
                    type = NavType.StringType
                    defaultValue = BeatIQInnerRoutes.HOME
                },
            ),
        ) { backStackEntry ->
            val raw = backStackEntry.arguments?.getString("startTab") ?: BeatIQInnerRoutes.HOME
            val startTab = if (raw in BeatIQInnerRoutes.bottomTabRoutes) raw else BeatIQInnerRoutes.HOME
            key(startTab) {
                MainShell(
                    outerNavController = outerNavController,
                    initialInnerRoute = startTab,
                    onLogout = {
                        RepositoryProvider.shutdown(app)
                        AuthPreferences(app).clear()
                        outerNavController.navigate(BeatIQRoutes.LANDING) {
                            popUpTo(outerNavController.graph.id) { inclusive = true }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun MainShell(
    outerNavController: NavHostController,
    initialInnerRoute: String,
    onLogout: () -> Unit,
) {
    val innerNavController = rememberNavController()
    val tabs = listOf(
        TabItem(BeatIQInnerRoutes.HOME, R.string.nav_home, Icons.Filled.Home),
        TabItem(BeatIQInnerRoutes.DISCOVER, R.string.nav_discover, Icons.Filled.Search),
        TabItem(BeatIQInnerRoutes.LIBRARY, R.string.nav_library, Icons.Outlined.LibraryMusic),
        TabItem(BeatIQInnerRoutes.DOWNLOADS, R.string.nav_downloads, Icons.Outlined.CloudDownload),
        TabItem(BeatIQInnerRoutes.PROFILE, R.string.nav_profile, Icons.Filled.Person),
    )

    val navBackStackEntry by innerNavController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val currentRoute = currentDestination?.route
    val immersiveBrowser = currentRoute == BeatIQInnerRoutes.ONLINE_BROWSER

    val popToLanding: () -> Unit = {
        outerNavController.popBackStack(BeatIQRoutes.LANDING, inclusive = false)
    }

    val playbackState by RepositoryProvider.playerRepository.playbackUiState.collectAsStateWithLifecycle()
    var fullPlayerVisible by rememberSaveable { mutableStateOf(false) }
    var longPressSong by remember { mutableStateOf<Song?>(null) }
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(
            LocalSongLongPress provides { song -> longPressSong = song },
        ) {
            Scaffold(
            bottomBar = {
                if (!immersiveBrowser) {
                    Column {
                        if (playbackState.currentSong != null) {
                            MiniPlayerBar(
                                state = playbackState,
                                onExpand = { fullPlayerVisible = true },
                                onPlayPause = {
                                    scope.launch {
                                        if (playbackState.isPlaying) {
                                            RepositoryProvider.playerRepository.pause()
                                        } else {
                                            RepositoryProvider.playerRepository.resume()
                                        }
                                    }
                                },
                            )
                        }
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ) {
                            tabs.forEach { tab ->
                                val selected =
                                    when (tab.route) {
                                        BeatIQInnerRoutes.LIBRARY ->
                                            isLibraryStackRoute(currentRoute)
                                        BeatIQInnerRoutes.DISCOVER ->
                                            isDiscoverStackRoute(currentRoute)
                                        BeatIQInnerRoutes.PROFILE ->
                                            isProfileStackRoute(currentRoute)
                                        else ->
                                            currentDestination?.hierarchy?.any { it.route == tab.route } == true
                                    }
                                NavigationBarItem(
                                    icon = { Icon(tab.icon, contentDescription = null) },
                                    label = { Text(stringResource(tab.labelRes)) },
                                    selected = selected,
                                    onClick = {
                                        innerNavController.navigate(tab.route) {
                                            popUpTo(innerNavController.graph.findStartDestination().id) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.primary,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    ),
                                )
                            }
                        }
                    }
                }
            },
        ) { innerPadding ->
            NavHost(
                navController = innerNavController,
                startDestination = initialInnerRoute,
                modifier =
                    if (immersiveBrowser) {
                        Modifier.fillMaxSize()
                    } else {
                        Modifier.padding(innerPadding)
                    },
            ) {
                composable(BeatIQInnerRoutes.HOME) {
                    HomeScreen(onBack = popToLanding)
                }
                composable(BeatIQInnerRoutes.DISCOVER) {
                    DiscoverScreen(
                        onBack = popToLanding,
                        onOpenOnlineBrowser = {
                            innerNavController.navigate(BeatIQInnerRoutes.ONLINE_BROWSER)
                        },
                        onOpenIdentifyMusic = {
                            innerNavController.navigate(BeatIQInnerRoutes.IDENTIFY_MUSIC)
                        },
                    )
                }
                composable(BeatIQInnerRoutes.LIBRARY) {
                    LibraryScreen(
                        onBack = popToLanding,
                        innerNavController = innerNavController,
                    )
                }
                composable(BeatIQInnerRoutes.DOWNLOADS) {
                    DownloadsScreen(onBack = popToLanding)
                }
                composable(BeatIQInnerRoutes.PROFILE) {
                    ProfileScreen(
                        onBack = popToLanding,
                        onLogout = onLogout,
                        onOpenNotifications = {
                            innerNavController.navigate(BeatIQInnerRoutes.SETTINGS_NOTIFICATIONS)
                        },
                        onOpenPlayback = {
                            innerNavController.navigate(BeatIQInnerRoutes.SETTINGS_PLAYBACK)
                        },
                        onOpenStorage = {
                            innerNavController.navigate(BeatIQInnerRoutes.SETTINGS_STORAGE)
                        },
                        onOpenPrivacy = {
                            innerNavController.navigate(BeatIQInnerRoutes.SETTINGS_PRIVACY)
                        },
                    )
                }
                composable(BeatIQInnerRoutes.ONLINE_BROWSER) {
                    OnlineMusicBrowserScreen(
                        onBack = { innerNavController.popBackStack() },
                    )
                }
                composable(BeatIQInnerRoutes.IDENTIFY_MUSIC) {
                    IdentifyMusicScreen(onBack = { innerNavController.popBackStack() })
                }
                composable(
                    route = BeatIQInnerRoutes.ARTIST_TRACKS_PATTERN,
                    arguments = listOf(navArgument("artistKey") { type = NavType.StringType }),
                ) { entry ->
                    val key = entry.arguments?.getString("artistKey").orEmpty()
                    val artist = runCatching { NavEncoding.decode(key) }.getOrDefault("")
                    ArtistTracksScreen(
                        artistName = artist,
                        onBack = { innerNavController.popBackStack() },
                    )
                }
                composable(
                    route = BeatIQInnerRoutes.ALBUM_TRACKS_PATTERN,
                    arguments = listOf(navArgument("albumKey") { type = NavType.StringType }),
                ) { entry ->
                    val key = entry.arguments?.getString("albumKey").orEmpty()
                    val (album, artist) =
                        runCatching { NavEncoding.decodeAlbumArtist(key) }.getOrElse { "" to "" }
                    AlbumTracksScreen(
                        albumTitle = album,
                        albumArtist = artist,
                        onBack = { innerNavController.popBackStack() },
                    )
                }
                composable(BeatIQInnerRoutes.LIBRARY_ALL_SONGS) {
                    LibraryAllSongsScreen(
                        genreFilter = null,
                        onBack = { innerNavController.popBackStack() },
                    )
                }
                composable(
                    route = BeatIQInnerRoutes.LIBRARY_GENRE_PATTERN,
                    arguments = listOf(navArgument("genreKey") { type = NavType.StringType }),
                ) { entry ->
                    val key = entry.arguments?.getString("genreKey").orEmpty()
                    val genre = runCatching { NavEncoding.decode(key) }.getOrDefault("")
                    LibraryAllSongsScreen(
                        genreFilter = genre.ifBlank { null },
                        onBack = { innerNavController.popBackStack() },
                    )
                }
                composable(
                    route = BeatIQInnerRoutes.PLAYLIST_DETAIL_PATTERN,
                    arguments = listOf(navArgument("playlistId") { type = NavType.StringType }),
                ) { entry ->
                    val id = entry.arguments?.getString("playlistId").orEmpty()
                    PlaylistDetailScreen(
                        playlistId = id,
                        onBack = { innerNavController.popBackStack() },
                        onAddTracks = {
                            innerNavController.navigate(BeatIQInnerRoutes.playlistPick(id))
                        },
                    )
                }
                composable(
                    route = BeatIQInnerRoutes.PLAYLIST_PICK_PATTERN,
                    arguments = listOf(navArgument("playlistId") { type = NavType.StringType }),
                ) { entry ->
                    val id = entry.arguments?.getString("playlistId").orEmpty()
                    PlaylistPickScreen(
                        playlistId = id,
                        onBack = { innerNavController.popBackStack() },
                    )
                }
                composable(BeatIQInnerRoutes.SETTINGS_NOTIFICATIONS) {
                    SettingsNotificationsScreen(onBack = { innerNavController.popBackStack() })
                }
                composable(BeatIQInnerRoutes.SETTINGS_PLAYBACK) {
                    SettingsPlaybackScreen(onBack = { innerNavController.popBackStack() })
                }
                composable(BeatIQInnerRoutes.SETTINGS_STORAGE) {
                    SettingsStorageScreen(onBack = { innerNavController.popBackStack() })
                }
                composable(BeatIQInnerRoutes.SETTINGS_PRIVACY) {
                    SettingsPrivacyScreen(onBack = { innerNavController.popBackStack() })
                }
            }
        }
        }

        SongLongPressMenuHost(
            song = longPressSong,
            onDismiss = { longPressSong = null },
            innerNavController = innerNavController,
        )

        AnimatedVisibility(
            visible = fullPlayerVisible,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            FullPlayerScreen(
                onDismiss = { fullPlayerVisible = false },
                onOpenIdentifyMusic = {
                    fullPlayerVisible = false
                    innerNavController.navigate(BeatIQInnerRoutes.IDENTIFY_MUSIC)
                },
            )
        }
    }
}
