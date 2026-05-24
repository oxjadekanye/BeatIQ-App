package com.beatiq.music.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.beatiq.music.core.recommendations.HomeAiFeed
import com.beatiq.music.core.recommendations.OnlineRecommendationEngine
import com.beatiq.music.data.model.OnlineRecommendation
import com.beatiq.music.data.repository.MusicRepository
import com.beatiq.music.features.library.RepositoryProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class HomeAiUiState(
    val selectedGenre: HomeGenreTab = HomeGenreTab.AFROBEATS,
    val isLoading: Boolean = true,
    val featured: List<OnlineRecommendation> = emptyList(),
    val gridSixteen: List<OnlineRecommendation> = emptyList(),
    val trending: List<OnlineRecommendation> = emptyList(),
    val trendingAlbums: List<OnlineRecommendation> = emptyList(),
    val djMix: List<OnlineRecommendation> = emptyList(),
    val popularPlaylists: List<OnlineRecommendation> = emptyList(),
    val lastRefreshedAt: Long = 0L,
)

class HomeViewModel(
    private val musicRepository: MusicRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeAiUiState())
    val uiState: StateFlow<HomeAiUiState> = _uiState.asStateFlow()

    private var librarySnapshot: List<com.beatiq.music.data.model.Song> = emptyList()
    private var refreshJob: Job? = null
    private var autoRefreshJob: Job? = null

    init {
        viewModelScope.launch {
            musicRepository.observeAllSongs().collect { library ->
                librarySnapshot = library
                refreshAll()
            }
        }
        autoRefreshJob =
            viewModelScope.launch {
                while (isActive) {
                    delay(AUTO_REFRESH_MS)
                    refreshAll()
                }
            }
    }

    fun selectGenre(tab: HomeGenreTab) {
        if (_uiState.value.selectedGenre == tab) return
        _uiState.value = _uiState.value.copy(selectedGenre = tab)
        refreshAll()
    }

    fun refreshAll() {
        refreshJob?.cancel()
        refreshJob =
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(isLoading = true)
                val feed =
                    runCatching {
                        OnlineRecommendationEngine.loadHomeFeed(
                            _uiState.value.selectedGenre,
                            librarySnapshot,
                        )
                    }.getOrElse {
                        HomeAiFeed(
                            genreTab = _uiState.value.selectedGenre,
                            featured = emptyList(),
                            gridSixteen = emptyList(),
                            trending = emptyList(),
                            trendingAlbums = emptyList(),
                            djMix = emptyList(),
                            popularPlaylists = emptyList(),
                        )
                    }
                applyFeed(feed)
            }
    }

    private fun applyFeed(feed: HomeAiFeed) {
        _uiState.value =
            HomeAiUiState(
                selectedGenre = feed.genreTab,
                isLoading = false,
                featured = feed.featured,
                gridSixteen = feed.gridSixteen,
                trending = feed.trending,
                trendingAlbums = feed.trendingAlbums,
                djMix = feed.djMix,
                popularPlaylists = feed.popularPlaylists,
                lastRefreshedAt = System.currentTimeMillis(),
            )
    }

    companion object {
        private const val AUTO_REFRESH_MS = 10 * 60 * 1000L

        @Suppress("UNCHECKED_CAST")
        val Factory: ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(HomeViewModel::class.java))
                    return HomeViewModel(RepositoryProvider.musicRepository) as T
                }
            }
    }
}
