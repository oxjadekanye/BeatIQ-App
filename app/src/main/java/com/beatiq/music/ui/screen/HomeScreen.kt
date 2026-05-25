package com.beatiq.music.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.beatiq.music.core.features.BeatIQFeatureFlags
import com.beatiq.music.data.model.OnlineRecommendation
import com.beatiq.music.features.globalheat.GlobalHeatCard
import com.beatiq.music.features.library.RepositoryProvider
import com.beatiq.music.presentation.home.HomeViewModel
import com.beatiq.music.presentation.home.legacyHomeContent
import com.beatiq.music.ui.components.BeatIQBackButton
import com.beatiq.music.ui.components.OnlineTrackDetailSheet
import com.beatiq.music.ui.components.PremiumScreenBackground

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onBack: () -> Unit,
    onOpenInternalBrowser: (String) -> Unit = {},
    onOpenAiDj: () -> Unit = {},
    onOpenBeatFeed: () -> Unit = {},
    onOpenGlobalHeat: (GlobalHeatCard) -> Unit = {},
) {
    if (BeatIQFeatureFlags.premiumHomeEnabled) {
        PremiumHomeScreen(
            onBack = onBack,
            onOpenInternalBrowser = onOpenInternalBrowser,
            onOpenAiDj = onOpenAiDj,
            onOpenBeatFeed = onOpenBeatFeed,
            onOpenGlobalHeat = onOpenGlobalHeat,
        )
        return
    }

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
        vm.onRecommendationOpened(rec)
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
            legacyHomeContent(
                state = state,
                onSelectGenre = vm::selectGenre,
                onRefresh = vm::refreshAll,
                onRecommendationClick = onRecommendationClick,
                isPlayingRec = isPlayingRec,
            )
        }
    }
}
