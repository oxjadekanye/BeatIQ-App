package com.beatiq.music.presentation.beat_feed

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.beatiq.music.core.features.BeatIQFeatureFlags
import com.beatiq.music.core.network.NetworkStatusMonitor
import com.beatiq.music.core.recommendations.HomeAiFeed
import com.beatiq.music.core.recommendations.OnlineRecommendationEngine
import com.beatiq.music.data.model.OnlineRecommendation
import com.beatiq.music.data.repository.MusicRepository
import com.beatiq.music.features.home.HomeFeedEnricher
import com.beatiq.music.features.library.RepositoryProvider
import com.beatiq.music.features.mood.MoodCatalog
import com.beatiq.music.features.mood.MoodEngine
import com.beatiq.music.features.mood.MoodStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class BeatFeedUiState(
    val allItems: List<OnlineRecommendation> = emptyList(),
    val visibleItems: List<OnlineRecommendation> = emptyList(),
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val emptyMessage: String? = null,
)

class BeatFeedViewModel(
    private val musicRepository: MusicRepository,
    application: Application,
) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val _uiState = MutableStateFlow(BeatFeedUiState())
    val uiState: StateFlow<BeatFeedUiState> = _uiState.asStateFlow()

    private var librarySnapshot: List<com.beatiq.music.data.model.Song> = emptyList()
    private var pageSize = PAGE_SIZE

    init {
        viewModelScope.launch {
            musicRepository.observeAllSongs().collect { songs ->
                librarySnapshot = songs
                if (!_uiState.value.isLoading) {
                    loadFeed(reset = true)
                }
            }
        }
        loadFeed(reset = true)
    }

    fun loadFeed(reset: Boolean = false) {
        viewModelScope.launch {
            if (reset) {
                _uiState.value = BeatFeedUiState(isLoading = true)
            } else {
                _uiState.value = _uiState.value.copy(isLoadingMore = true)
            }
            if (!NetworkStatusMonitor.isOnline(appContext)) {
                _uiState.value =
                    BeatFeedUiState(
                        isLoading = false,
                        emptyMessage = "You're offline. Connect to load Beat Feed picks.",
                    )
                return@launch
            }
            val mood = MoodCatalog.byId(MoodStore(appContext).getSelectedMoodId())
            val genre = MoodEngine.genreTabForMood(mood)
            val feed =
                runCatching {
                    OnlineRecommendationEngine.loadHomeFeed(genre, librarySnapshot)
                }.getOrElse {
                    HomeAiFeed(
                        genreTab = genre,
                        featured = emptyList(),
                        gridSixteen = emptyList(),
                        trending = emptyList(),
                        trendingAlbums = emptyList(),
                        djMix = emptyList(),
                        popularPlaylists = emptyList(),
                    )
                }
            val enriched =
                if (BeatIQFeatureFlags.premiumHomeEnabled) {
                    HomeFeedEnricher.enrich(appContext, feed)
                } else {
                    null
                }
            val pool =
                listOf(
                    enriched?.featured.orEmpty(),
                    enriched?.trending.orEmpty(),
                    enriched?.undergroundGems.orEmpty(),
                    enriched?.newFreeMusic.orEmpty(),
                    feed.gridSixteen,
                ).flatten().distinctBy { it.id }

            val fallback =
                if (pool.isEmpty()) {
                    (feed.featured + feed.trending).distinctBy { it.id }
                } else {
                    pool
                }

            val current = _uiState.value
            val allItems = if (reset) fallback else (current.allItems + fallback).distinctBy { it.id }
            val visibleCount = if (reset) pageSize.coerceAtMost(allItems.size) else current.visibleItems.size + pageSize
            val visible = allItems.take(visibleCount.coerceAtMost(allItems.size))
            _uiState.value =
                BeatFeedUiState(
                    allItems = allItems,
                    visibleItems = visible,
                    isLoading = false,
                    isLoadingMore = false,
                    hasMore = visible.size < allItems.size,
                    emptyMessage =
                        if (allItems.isEmpty()) {
                            "No Beat Feed picks yet. Pull to refresh from Home first."
                        } else {
                            null
                        },
                )
        }
    }

    fun loadMore() {
        val state = _uiState.value
        if (state.isLoadingMore || !state.hasMore) return
        val nextCount = (state.visibleItems.size + pageSize).coerceAtMost(state.allItems.size)
        _uiState.value =
            state.copy(
                visibleItems = state.allItems.take(nextCount),
                hasMore = nextCount < state.allItems.size,
            )
    }

    companion object {
        const val PAGE_SIZE = 8

        @Suppress("UNCHECKED_CAST")
        val Factory: ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(BeatFeedViewModel::class.java))
                    return BeatFeedViewModel(
                        RepositoryProvider.musicRepository,
                        RepositoryProvider.requireApplication(),
                    ) as T
                }
            }
    }
}
