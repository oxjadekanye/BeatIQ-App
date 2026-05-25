package com.beatiq.music.presentation.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.beatiq.music.data.model.OnlineRecommendation
import com.beatiq.music.ui.components.AiRecommendationRow
import com.beatiq.music.ui.components.AiSongGridPager
import com.beatiq.music.ui.components.HomeGenreTabRow
import com.beatiq.music.ui.components.HomeWelcomeHeader
import com.beatiq.music.ui.components.SectionHeader
import com.beatiq.music.ui.components.homeGreetingForNow

fun LazyListScope.legacyHomeContent(
    state: HomeAiUiState,
    onSelectGenre: (HomeGenreTab) -> Unit,
    onRefresh: () -> Unit,
    onRecommendationClick: (OnlineRecommendation) -> Unit,
    isPlayingRec: (OnlineRecommendation) -> Boolean,
) {
    val genre = state.selectedGenre
    val contentReady = !state.isLoading
    item {
        HomeWelcomeHeader(
            greeting = homeGreetingForNow(),
            subline = HomeSectionLabels.welcomeSubline(),
        )
    }
    item {
        HomeGenreTabRow(
            selected = genre,
            onSelect = onSelectGenre,
            onRefresh = onRefresh,
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
            AnimatedVisibility(visible = contentReady, enter = fadeIn(tween(320))) {
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
            AnimatedVisibility(visible = contentReady, enter = fadeIn(tween(360))) {
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
