package com.beatiq.app.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import com.beatiq.app.BeatIQLandingScreen
import com.beatiq.app.R
import com.beatiq.app.features.library.RepositoryProvider
import com.beatiq.app.features.player.FullPlayerScreen
import com.beatiq.app.features.player.MiniPlayerBar
import com.beatiq.app.ui.screen.DiscoverScreen
import com.beatiq.app.ui.screen.DownloadsScreen
import com.beatiq.app.ui.screen.HomeScreen
import com.beatiq.app.ui.screen.LibraryScreen
import com.beatiq.app.ui.screen.ProfileScreen
import kotlinx.coroutines.launch

/** Inner tab routes (scoped to the tab [NavHost] inside [MainShell]). */
private object InnerRoutes {
    const val HOME = "home"
    const val DISCOVER = "discover"
    const val LIBRARY = "library"
    const val DOWNLOADS = "downloads"
    const val PROFILE = "profile"

    val ALL = setOf(HOME, DISCOVER, LIBRARY, DOWNLOADS, PROFILE)
}

private data class TabItem(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
)

@Composable
fun BeatIQNavHost(outerNavController: NavHostController) {
    NavHost(
        navController = outerNavController,
        startDestination = BeatIQRoutes.LANDING,
    ) {
        composable(BeatIQRoutes.LANDING) {
            BeatIQLandingScreen(
                onGetStarted = {
                    outerNavController.navigate(BeatIQRoutes.mainRoute(InnerRoutes.HOME))
                },
                onExploreMusic = {
                    outerNavController.navigate(BeatIQRoutes.mainRoute(InnerRoutes.DISCOVER))
                },
            )
        }
        composable(
            route = BeatIQRoutes.MAIN_PATTERN,
            arguments = listOf(
                navArgument("startTab") {
                    type = NavType.StringType
                    defaultValue = InnerRoutes.HOME
                },
            ),
        ) { backStackEntry ->
            val raw = backStackEntry.arguments?.getString("startTab") ?: InnerRoutes.HOME
            val startTab = if (raw in InnerRoutes.ALL) raw else InnerRoutes.HOME
            key(startTab) {
                MainShell(
                    outerNavController = outerNavController,
                    initialInnerRoute = startTab,
                )
            }
        }
    }
}

@Composable
private fun MainShell(
    outerNavController: NavHostController,
    initialInnerRoute: String,
) {
    val innerNavController = rememberNavController()
    val tabs = listOf(
        TabItem(InnerRoutes.HOME, R.string.nav_home, Icons.Filled.Home),
        TabItem(InnerRoutes.DISCOVER, R.string.nav_discover, Icons.Filled.Search),
        TabItem(InnerRoutes.LIBRARY, R.string.nav_library, Icons.Outlined.LibraryMusic),
        TabItem(InnerRoutes.DOWNLOADS, R.string.nav_downloads, Icons.Outlined.CloudDownload),
        TabItem(InnerRoutes.PROFILE, R.string.nav_profile, Icons.Filled.Person),
    )

    val navBackStackEntry by innerNavController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val popToLanding: () -> Unit = {
        outerNavController.popBackStack(BeatIQRoutes.LANDING, inclusive = false)
    }

    val playbackState by RepositoryProvider.playerRepository.playbackUiState.collectAsStateWithLifecycle()
    var fullPlayerVisible by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = {
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
                            val selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
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
            },
        ) { innerPadding ->
            NavHost(
                navController = innerNavController,
                startDestination = initialInnerRoute,
                modifier = Modifier.padding(innerPadding),
            ) {
                composable(InnerRoutes.HOME) {
                    HomeScreen(onBack = popToLanding)
                }
                composable(InnerRoutes.DISCOVER) {
                    DiscoverScreen(onBack = popToLanding)
                }
                composable(InnerRoutes.LIBRARY) {
                    LibraryScreen(onBack = popToLanding)
                }
                composable(InnerRoutes.DOWNLOADS) {
                    DownloadsScreen(onBack = popToLanding)
                }
                composable(InnerRoutes.PROFILE) {
                    ProfileScreen(onBack = popToLanding)
                }
            }
        }

        AnimatedVisibility(
            visible = fullPlayerVisible,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            FullPlayerScreen(onDismiss = { fullPlayerVisible = false })
        }
    }
}
