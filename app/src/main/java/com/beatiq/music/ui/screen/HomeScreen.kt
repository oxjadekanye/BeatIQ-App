package com.beatiq.music.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.beatiq.music.data.model.OnlineRecommendation
import com.beatiq.music.features.library.RepositoryProvider
import com.beatiq.music.presentation.home.HomeRecSection
import com.beatiq.music.presentation.home.HomeSectionLabels
import com.beatiq.music.presentation.home.HomeViewModel
import com.beatiq.music.ui.components.AiRecommendationRow
import com.beatiq.music.ui.components.AiSongGridPager
import com.beatiq.music.ui.components.BeatIQBackButton
import com.beatiq.music.ui.components.HomeGenreTabRow
import com.beatiq.music.ui.components.HomeWelcomeHeader
import com.beatiq.music.ui.components.OnlineTrackDetailSheet
import com.beatiq.music.ui.components.PremiumScreenBackground
import com.beatiq.music.ui.components.SectionHeader
import com.beatiq.music.ui.components.homeGreetingForNow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onBack: () -> Unit,
    onOpenInternalBrowser: (String) -> Unit = {},
) {
    val vm: HomeViewModel = viewModel(factory = HomeViewModel.Factory)
    val state by vm.uiState.collectAsStateWithLifecycle()
    val playback by RepositoryProvider.playerRepository
        .playbackUiState
        .collectAsStateWithLifecycle()
    var selectedTrack by remember { mutableStateOf<OnlineRecommendation?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val isPlayingRec: (OnlineRecommendation) -> Boolean = remember(playback) {
        { rec ->
            if (rec.opensInInternalBrowser) {
                false
            } else {
                val current = playback.currentSong
                playback.isPlaying &&
                    current != null &&
                    current.title.equals(rec.title, ignoreCase = true) &&
                    current.artist.equals(rec.artist, ignoreCase = true)
            }
        }
    }

    val onRecommendationClick: (OnlineRecommendation) -> Unit = { rec ->
        if (rec.opensInInternalBrowser) {
            onOpenInternalBrowser(rec.browserUrl)
        } else {
            selectedTrack = rec
        }
    }

    selectedTrack?.let { track ->
        ModalBottomSheet(
            onDismissRequest = { selectedTrack = null },
            sheetState = sheetState,
        ) {
            OnlineTrackDetailSheet(
                track = track,
                onDismiss = { selectedTrack = null },
                onOpenInternalBrowser = onOpenInternalBrowser,
            )
        }
    }

    val genre = state.selectedGenre
    val contentReady = !state.isLoading

    PremiumScreenBackground {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 28.dp),
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
                HomeWelcomeHeader(
                    greeting = homeGreetingForNow(),
                    subline = HomeSectionLabels.welcomeSubline(),
                )
            }
            item {
                HomeGenreTabRow(
                    selected = genre,
                    onSelect = vm::selectGenre,
                    onRefresh = vm::refreshAll,
                    isLoading = state.isLoading,
                )
            }
            if (state.isLoading) {
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            } else {
                item {
                    AnimatedVisibility(
                        visible = contentReady,
                        enter = fadeIn(tween(320)),
                    ) {
                        SectionHeader(title = HomeSectionLabels.featured(genre))
                    }
                }
                item {
                    AiRecommendationRow(
                        title = "",
                        items = state.featured,
                        onTrackClick = onRecommendationClick,
                        section = HomeRecSection.FEATURED,
                        tasteArtist = state.tasteArtist,
                        isPlaying = isPlayingRec,
                        contentVisible = contentReady,
                    )
                }
                item {
                    AnimatedVisibility(
                        visible = contentReady,
                        enter = fadeIn(tween(360)),
                    ) {
                        SectionHeader(title = HomeSectionLabels.grid(genre))
                    }
                }
                item {
                    AiSongGridPager(
                        tracks = state.gridSixteen,
                        onTrackClick = onRecommendationClick,
                        modifier = Modifier.padding(bottom = 8.dp),
                        tasteArtist = state.tasteArtist,
                        isPlaying = isPlayingRec,
                        contentVisible = contentReady,
                    )
                }
                item {
                    AiRecommendationRow(
                        title = HomeSectionLabels.trending(genre),
                        items = state.trending,
                        onTrackClick = onRecommendationClick,
                        section = HomeRecSection.TRENDING,
                        tasteArtist = state.tasteArtist,
                        isPlaying = isPlayingRec,
                        contentVisible = contentReady,
                    )
                }
                item {
                    AiRecommendationRow(
                        title = HomeSectionLabels.albums(genre),
                        items = state.trendingAlbums,
                        onTrackClick = onRecommendationClick,
                        section = HomeRecSection.ALBUMS,
                        tasteArtist = state.tasteArtist,
                        isPlaying = isPlayingRec,
                        contentVisible = contentReady,
                    )
                }
                item {
                    AiRecommendationRow(
                        title = HomeSectionLabels.djMixes(genre),
                        items = state.djMix,
                        onTrackClick = onRecommendationClick,
                        section = HomeRecSection.DJ_MIX,
                        tasteArtist = state.tasteArtist,
                        isPlaying = isPlayingRec,
                        contentVisible = contentReady,
                    )
                }
                item {
                    AiRecommendationRow(
                        title = HomeSectionLabels.popularPlaylists(genre),
                        items = state.popularPlaylists,
                        onTrackClick = onRecommendationClick,
                        section = HomeRecSection.PLAYLISTS,
                        tasteArtist = state.tasteArtist,
                        isPlaying = isPlayingRec,
                        contentVisible = contentReady,
                    )
                }
            }
        }
    }
}
