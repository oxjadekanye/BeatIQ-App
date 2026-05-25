package com.beatiq.music.ui.screen

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.beatiq.music.core.ui.premium.ProviderBadge
import com.beatiq.music.core.ui.premium.RecommendationReasonText
import com.beatiq.music.data.model.OnlineRecommendation
import com.beatiq.music.features.share.BeatIQShareText
import com.beatiq.music.presentation.beat_feed.BeatFeedViewModel
import com.beatiq.music.presentation.home.HomeViewModel
import com.beatiq.music.ui.components.BeatIQBackButton
import com.beatiq.music.ui.components.OnlineTrackDetailSheet
import com.beatiq.music.ui.components.PremiumScreenBackground

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BeatFeedScreen(
    onBack: () -> Unit,
    onOpenInternalBrowser: (String) -> Unit,
) {
    val vm: BeatFeedViewModel = viewModel(factory = BeatFeedViewModel.Factory)
    val homeVm: HomeViewModel = viewModel(factory = HomeViewModel.Factory)
    val state by vm.uiState.collectAsStateWithLifecycle()
    var selectedTrack by remember { mutableStateOf<OnlineRecommendation?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val listState = rememberLazyListState()
    val context = LocalContext.current

    val shouldLoadMore by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= state.visibleItems.size - 3 && state.hasMore && !state.isLoadingMore
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) vm.loadMore()
    }

    selectedTrack?.let { track ->
        ModalBottomSheet(onDismissRequest = { selectedTrack = null }, sheetState = sheetState) {
            OnlineTrackDetailSheet(
                track = track,
                onDismiss = { selectedTrack = null },
                onOpenInternalBrowser = onOpenInternalBrowser,
            )
        }
    }

    PremiumScreenBackground {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                BeatIQBackButton(onBack = onBack, modifier = Modifier.padding(8.dp))
                Text(
                    "Beat Feed",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            if (state.isLoading) {
                item {
                    CircularProgressIndicator(Modifier.padding(24.dp))
                }
            } else if (state.emptyMessage != null) {
                item {
                    Text(
                        state.emptyMessage!!,
                        modifier = Modifier.padding(20.dp),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            } else {
                items(state.visibleItems, key = { it.id }) { rec ->
                    BeatFeedCard(
                        item = rec,
                        onPlay = {
                            homeVm.onRecommendationOpened(rec)
                            if (rec.opensInInternalBrowser) {
                                onOpenInternalBrowser(rec.browserUrl)
                            } else {
                                selectedTrack = rec
                            }
                        },
                        onOpen = {
                            homeVm.onRecommendationOpened(rec)
                            onOpenInternalBrowser(rec.browserUrl)
                        },
                        onShare = {
                            val text = BeatIQShareText.beatFeedShare(rec)
                            val intent =
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, text)
                                }
                            context.startActivity(Intent.createChooser(intent, "Share discovery"))
                        },
                    )
                }
                if (state.isLoadingMore || state.hasMore) {
                    item {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BeatFeedCard(
    item: OnlineRecommendation,
    onPlay: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF14141C)),
    ) {
        Column {
            Box(Modifier.fillMaxWidth().height(200.dp)) {
                AsyncImage(
                    model = item.artworkUrl,
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)),
                            ),
                        ),
                )
                ProviderBadge(
                    label = item.sourceLabel ?: item.provider.name,
                    modifier = Modifier.padding(12.dp).align(Alignment.TopStart),
                )
                Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                    Text(
                        item.title,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    Text(
                        item.artist,
                        color = Color(0xFFE8E8F0),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                RecommendationReasonText(
                    item.recommendationReason,
                    Modifier.padding(bottom = 4.dp),
                )
                RowActions(
                    browseOnly = item.isBrowseOnly,
                    hasPreview = item.isPlayablePreview,
                    onPlay = onPlay,
                    onOpen = onOpen,
                    onShare = onShare,
                )
            }
        }
    }
}

@Composable
private fun RowActions(
    browseOnly: Boolean,
    hasPreview: Boolean,
    onPlay: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (hasPreview && !browseOnly) {
            TextButton(onClick = onPlay) { Text("Play preview") }
        }
        TextButton(onClick = onOpen) {
            Text(if (browseOnly) "Open in browser" else "Open provider")
        }
        TextButton(onClick = onShare) { Text("Share") }
    }
}
