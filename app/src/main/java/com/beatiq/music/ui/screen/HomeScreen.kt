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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.beatiq.music.R
import com.beatiq.music.data.model.OnlineRecommendation
import com.beatiq.music.presentation.home.HomeViewModel
import com.beatiq.music.ui.components.AiRecommendationRow
import com.beatiq.music.ui.components.AiSongGridPager
import com.beatiq.music.ui.components.BeatIQBackButton
import com.beatiq.music.ui.components.HomeGenreTabRow
import com.beatiq.music.ui.components.OnlineTrackDetailSheet
import com.beatiq.music.ui.components.PremiumScreenBackground
import com.beatiq.music.ui.components.SectionHeader
import java.time.LocalTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onBack: () -> Unit) {
    val vm: HomeViewModel = viewModel(factory = HomeViewModel.Factory)
    val state by vm.uiState.collectAsStateWithLifecycle()
    var selectedTrack by remember { mutableStateOf<OnlineRecommendation?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    selectedTrack?.let { track ->
        ModalBottomSheet(
            onDismissRequest = { selectedTrack = null },
            sheetState = sheetState,
        ) {
            OnlineTrackDetailSheet(
                track = track,
                onDismiss = { selectedTrack = null },
            )
        }
    }

    PremiumScreenBackground {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
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
                Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                    Text(
                        text = greetingLabel(),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.home_headline),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = stringResource(R.string.home_sub_ai),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                HomeGenreTabRow(
                    selected = state.selectedGenre,
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
                    SectionHeader(title = stringResource(R.string.home_section_ai_featured))
                }
                item {
                    AiRecommendationRow(
                        title = "",
                        items = state.featured,
                        onTrackClick = { selectedTrack = it },
                    )
                }
                item {
                    SectionHeader(title = stringResource(R.string.home_section_ai_grid))
                }
                item {
                    AiSongGridPager(
                        tracks = state.gridSixteen,
                        onTrackClick = { selectedTrack = it },
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                item {
                    AiRecommendationRow(
                        title = stringResource(R.string.home_section_trending),
                        items = state.trending,
                        onTrackClick = { selectedTrack = it },
                    )
                }
                item {
                    AiRecommendationRow(
                        title = stringResource(R.string.home_section_trending_albums),
                        items = state.trendingAlbums,
                        onTrackClick = { selectedTrack = it },
                    )
                }
                item {
                    AiRecommendationRow(
                        title = stringResource(R.string.home_section_dj_mix),
                        items = state.djMix,
                        onTrackClick = { selectedTrack = it },
                    )
                }
                item {
                    AiRecommendationRow(
                        title = stringResource(R.string.home_section_popular_playlists),
                        items = state.popularPlaylists,
                        onTrackClick = { selectedTrack = it },
                    )
                }
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
