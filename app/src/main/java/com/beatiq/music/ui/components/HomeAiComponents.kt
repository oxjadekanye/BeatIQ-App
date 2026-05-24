package com.beatiq.music.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.beatiq.music.R
import com.beatiq.music.data.model.OnlineRecommendation
import com.beatiq.music.presentation.home.HomeGenreTab
import com.beatiq.music.presentation.home.HomeRecSection
import com.beatiq.music.presentation.home.HomeRecommendationBadges

@Composable
fun HomeGenreTabRow(
    selected: HomeGenreTab,
    onSelect: (HomeGenreTab) -> Unit,
    onRefresh: () -> Unit,
    isLoading: Boolean,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LazyRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 4.dp),
        ) {
            items(HomeGenreTab.all.size, key = { HomeGenreTab.all[it].name }) { index ->
                val tab = HomeGenreTab.all[index]
                FilterChip(
                    selected = tab == selected,
                    onClick = { onSelect(tab) },
                    label = {
                        Text(
                            text = tab.label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (tab == selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    colors =
                        FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                            selectedLabelColor = Color.White,
                        ),
                )
            }
        }
        IconButton(onClick = onRefresh, enabled = !isLoading) {
            Icon(
                Icons.Outlined.Refresh,
                contentDescription = stringResource(R.string.home_refresh_ai),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AiSongGridPager(
    tracks: List<OnlineRecommendation>,
    onTrackClick: (OnlineRecommendation) -> Unit,
    modifier: Modifier = Modifier,
    tasteArtist: String? = null,
    isPlaying: (OnlineRecommendation) -> Boolean = { false },
    contentVisible: Boolean = true,
) {
    val pages = tracks.chunked(4)
    val pageCount = pages.size.coerceAtLeast(1)
    val pagerState = rememberPagerState(pageCount = { pageCount })

    AnimatedVisibility(
        visible = contentVisible,
        enter = fadeIn(tween(360)) + slideInVertically(tween(360)) { it / 4 },
        modifier = modifier,
    ) {
        Column {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp),
                pageSpacing = 12.dp,
            ) { page ->
                val rowItems = pages.getOrElse(page) { emptyList() }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    rowItems.forEachIndexed { index, rec ->
                        OnlineRecommendationCard(
                            recommendation = rec,
                            onClick = { onTrackClick(rec) },
                            modifier = Modifier.weight(1f),
                            compact = true,
                            badges =
                                HomeRecommendationBadges.forItem(
                                    rec,
                                    HomeRecSection.GRID,
                                    tasteArtist,
                                    page * 4 + index,
                                ),
                            isPlayingMatch = isPlaying(rec),
                        )
                    }
                    repeat((4 - rowItems.size).coerceAtLeast(0)) {
                        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    }
                }
            }
            if (pageCount > 1) {
                Text(
                    text =
                        stringResource(
                            R.string.home_swipe_more,
                            pagerState.currentPage + 1,
                            pageCount,
                        ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
fun AiRecommendationRow(
    title: String,
    items: List<OnlineRecommendation>,
    onTrackClick: (OnlineRecommendation) -> Unit,
    section: HomeRecSection,
    tasteArtist: String? = null,
    isPlaying: (OnlineRecommendation) -> Boolean = { false },
    contentVisible: Boolean = true,
) {
    if (items.isEmpty()) return
    AnimatedVisibility(
        visible = contentVisible,
        enter = fadeIn(tween(400)) + slideInVertically(tween(400)) { it / 5 },
    ) {
        Column {
            if (title.isNotBlank()) {
                SectionHeader(title = title)
            }
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
            ) {
                itemsIndexed(items, key = { _, rec -> rec.id }) { index, rec ->
                    OnlineRecommendationCard(
                        recommendation = rec,
                        onClick = { onTrackClick(rec) },
                        badges =
                            HomeRecommendationBadges.forItem(
                                rec,
                                section,
                                tasteArtist,
                                index,
                            ),
                        isPlayingMatch = isPlaying(rec),
                    )
                }
            }
        }
    }
}
