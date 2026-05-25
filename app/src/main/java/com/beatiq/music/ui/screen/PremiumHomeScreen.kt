package com.beatiq.music.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.beatiq.music.core.features.BeatIQFeatureFlags
import com.beatiq.music.core.ui.premium.AIDJEntryCard
import com.beatiq.music.core.ui.premium.AnimatedGradientBackground
import com.beatiq.music.core.ui.premium.HeroMoodCard
import com.beatiq.music.core.ui.premium.HomeSectionSkeleton
import com.beatiq.music.core.ui.premium.HorizontalMusicRail
import com.beatiq.music.core.ui.premium.MoodChip
import com.beatiq.music.core.ui.premium.OfflineHomeBanner
import com.beatiq.music.core.ui.premium.PremiumSectionHeader
import com.beatiq.music.data.model.OnlineRecommendation
import com.beatiq.music.features.globalheat.GlobalHeatCard
import com.beatiq.music.features.home.PremiumHomeSection
import com.beatiq.music.features.mood.MoodDefinition
import com.beatiq.music.features.library.RepositoryProvider
import com.beatiq.music.presentation.home.HomeViewModel
import com.beatiq.music.ui.components.BeatIQBackButton
import com.beatiq.music.ui.components.HomeGenreTabRow
import com.beatiq.music.ui.components.OnlineTrackDetailSheet
import com.beatiq.music.ui.components.homeGreetingForNow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PremiumHomeScreen(
    onBack: () -> Unit,
    onOpenInternalBrowser: (String) -> Unit,
    onOpenAiDj: () -> Unit,
    onOpenBeatFeed: () -> Unit,
    onOpenGlobalHeat: (GlobalHeatCard) -> Unit,
) {
    val vm: HomeViewModel = viewModel(factory = HomeViewModel.Factory)
    val state by vm.uiState.collectAsStateWithLifecycle()
    val playback by RepositoryProvider.playerRepository
        .playbackUiState
        .collectAsStateWithLifecycle()
    val isPlayingRec: (OnlineRecommendation) -> Boolean = remember(playback) {
        { rec ->
            if (rec.opensInInternalBrowser) false
            else {
                val current = playback.currentSong
                playback.isPlaying &&
                    current != null &&
                    current.title.equals(rec.title, ignoreCase = true) &&
                    current.artist.equals(rec.artist, ignoreCase = true)
            }
        }
    }
    var selectedTrack by remember { mutableStateOf<OnlineRecommendation?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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

    val mood = state.selectedMood
    AnimatedGradientBackground(
        start = mood?.gradientStart ?: Color(0xFF1A1A2E),
        end = mood?.gradientEnd ?: Color(0xFF4A4E8A),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 28.dp),
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
            if (state.isOffline) {
                item {
                    OfflineHomeBanner(onRetry = vm::refreshAll)
                }
            }
            item {
                Text(
                    text = homeGreetingForNow(),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
            if (BeatIQFeatureFlags.moodEngineEnabled) {
                item {
                    HomeGenreTabRow(
                        selected = state.selectedGenre,
                        onSelect = vm::selectGenre,
                        onRefresh = vm::refreshAll,
                        isLoading = state.isLoading,
                    )
                }
            }
            if (state.isLoading) {
                items(3) { HomeSectionSkeleton() }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(Modifier.padding(24.dp))
                    }
                }
            } else {
                state.sectionOrder.forEach { section ->
                    premiumSectionItem(
                        section = section,
                        state = state,
                        mood = mood,
                        onMoodSelect = vm::selectMood,
                        onOpenAiDj = onOpenAiDj,
                        onOpenBeatFeed = onOpenBeatFeed,
                        onOpenGlobalHeat = onOpenGlobalHeat,
                        onRecommendationClick = onRecommendationClick,
                        isPlaying = isPlayingRec,
                    )
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.premiumSectionItem(
    section: PremiumHomeSection,
    state: com.beatiq.music.presentation.home.HomeAiUiState,
    mood: MoodDefinition?,
    onMoodSelect: (com.beatiq.music.features.mood.MoodId) -> Unit,
    onOpenAiDj: () -> Unit,
    onOpenBeatFeed: () -> Unit,
    onOpenGlobalHeat: (GlobalHeatCard) -> Unit,
    onRecommendationClick: (OnlineRecommendation) -> Unit,
    isPlaying: (OnlineRecommendation) -> Boolean,
) {
    when (section) {
        PremiumHomeSection.HERO_MOOD ->
            item {
                mood?.let {
                    HeroMoodCard(
                        title = it.displayName,
                        subtitle = it.shortDescription,
                        moodLabel = it.displayName,
                        gradientStart = it.gradientStart,
                        gradientEnd = it.gradientEnd,
                    )
                }
            }
        PremiumHomeSection.AI_DJ_ENTRY ->
            if (BeatIQFeatureFlags.aiDjEnabled) {
                item { AIDJEntryCard(onClick = onOpenAiDj) }
            }
        PremiumHomeSection.AI_RECOMMENDED ->
            item {
                HorizontalMusicRail(
                    title = "Recommended for you",
                    items = state.featured,
                    onItemClick = onRecommendationClick,
                    isPlaying = isPlaying,
                )
            }
        PremiumHomeSection.TRENDING_NEAR_YOU ->
            item {
                HorizontalMusicRail(
                    title = "Trending near you",
                    items = state.trending,
                    onItemClick = onRecommendationClick,
                    isPlaying = isPlaying,
                )
            }
        PremiumHomeSection.NEW_FREE ->
            item {
                HorizontalMusicRail(
                    title = "New free music",
                    items = state.newFreeMusic,
                    onItemClick = onRecommendationClick,
                    isPlaying = isPlaying,
                )
            }
        PremiumHomeSection.UNDERGROUND ->
            item {
                HorizontalMusicRail(
                    title = "Underground gems",
                    items = state.undergroundGems,
                    onItemClick = onRecommendationClick,
                    isPlaying = isPlaying,
                )
            }
        PremiumHomeSection.MOOD_STATIONS ->
            if (BeatIQFeatureFlags.moodEngineEnabled && mood != null) {
                item {
                    PremiumSectionHeader("Mood stations")
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(com.beatiq.music.features.mood.MoodCatalog.all, key = { it.id }) { m ->
                            MoodChip(
                                label = m.displayName,
                                selected = m.id == mood.id,
                                onClick = { onMoodSelect(m.id) },
                            )
                        }
                    }
                }
            }
        PremiumHomeSection.BEAT_FEED_PREVIEW ->
            if (BeatIQFeatureFlags.beatFeedEnabled) {
                item {
                    Card(
                        onClick = onOpenBeatFeed,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E28)),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            PremiumSectionHeader("Beat Feed")
                            Text(
                                "Vertical discovery — swipe through fresh picks",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            HorizontalMusicRail(
                                title = "",
                                items = state.beatFeedPreview.take(6),
                                onItemClick = onRecommendationClick,
                                isPlaying = isPlaying,
                            )
                        }
                    }
                }
            }
        PremiumHomeSection.RECENTLY_PLAYED ->
            item {
                HorizontalMusicRail(
                    title = "Recently played",
                    items = state.recentlyPlayedRecs,
                    onItemClick = onRecommendationClick,
                    isPlaying = isPlaying,
                )
            }
        PremiumHomeSection.SAVED_CLIPS ->
            item {
                if (state.savedClipLabels.isNotEmpty()) {
                    PremiumSectionHeader("Saved preview clips")
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.savedClipLabels) { label ->
                            Box(
                                Modifier
                                    .width(140.dp)
                                    .height(72.dp)
                                    .background(Color(0xFF2A2A35), RoundedCornerShape(12.dp))
                                    .padding(12.dp),
                            ) {
                                Text(label, maxLines = 2, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        PremiumHomeSection.GLOBAL_HEAT ->
            if (BeatIQFeatureFlags.globalHeatEnabled) {
                item {
                    PremiumSectionHeader("Global heat")
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(state.globalHeatCards, key = { it.id }) { card ->
                            Card(
                                modifier =
                                    Modifier
                                        .width(160.dp)
                                        .height(100.dp)
                                        .clickable { onOpenGlobalHeat(card) },
                                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .background(
                                            Brush.linearGradient(
                                                listOf(
                                                    Color(card.gradientStartHex),
                                                    Color(card.gradientEndHex),
                                                ),
                                            ),
                                        )
                                        .padding(12.dp),
                                ) {
                                    Column {
                                        Text(card.locationName, fontWeight = FontWeight.Bold, color = Color.White)
                                        Text(
                                            card.focusLabel,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.White.copy(alpha = 0.9f),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        PremiumHomeSection.DJ_MIXES ->
            item {
                HorizontalMusicRail(
                    title = "DJ mixes",
                    items = state.djMix,
                    onItemClick = onRecommendationClick,
                    isPlaying = isPlaying,
                )
            }
    }
}
