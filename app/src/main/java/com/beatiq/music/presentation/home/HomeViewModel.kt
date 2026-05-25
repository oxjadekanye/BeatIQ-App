package com.beatiq.music.presentation.home

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
import com.beatiq.music.data.model.UserDownloadStatus
import com.beatiq.music.data.repository.MusicRepository
import com.beatiq.music.features.globalheat.GlobalHeatCatalog
import com.beatiq.music.features.globalheat.GlobalHeatCard
import com.beatiq.music.features.home.HomeFeedEnricher
import com.beatiq.music.features.home.HomeSectionOrderer
import com.beatiq.music.features.home.PremiumHomeSection
import com.beatiq.music.features.mood.MoodCatalog
import com.beatiq.music.features.mood.MoodDefinition
import com.beatiq.music.features.mood.MoodEngine
import com.beatiq.music.features.mood.MoodId
import com.beatiq.music.features.mood.MoodStore
import com.beatiq.music.features.recommendations.intelligence.RecommendationSignalCollector
import com.beatiq.music.features.recommendations.intelligence.UserMusicPreferenceStore
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
    val tasteArtist: String? = null,
    val selectedMood: MoodDefinition? = null,
    val isOffline: Boolean = false,
    val undergroundGems: List<OnlineRecommendation> = emptyList(),
    val newFreeMusic: List<OnlineRecommendation> = emptyList(),
    val beatFeedPreview: List<OnlineRecommendation> = emptyList(),
    val recentlyPlayedRecs: List<OnlineRecommendation> = emptyList(),
    val savedClipLabels: List<String> = emptyList(),
    val globalHeatCards: List<GlobalHeatCard> = GlobalHeatCatalog.cards,
    val sectionOrder: List<PremiumHomeSection> = emptyList(),
)

class HomeViewModel(
    private val musicRepository: MusicRepository,
    application: Application,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(HomeAiUiState())
    val uiState: StateFlow<HomeAiUiState> = _uiState.asStateFlow()

    private var librarySnapshot: List<com.beatiq.music.data.model.Song> = emptyList()
    private var refreshJob: Job? = null
    private var autoRefreshJob: Job? = null
    private val appContext = application.applicationContext
    private val moodStore = MoodStore(appContext)
    private val signalsStore = UserMusicPreferenceStore(appContext)

    init {
        viewModelScope.launch {
            musicRepository.observeAllSongs().collect { library ->
                librarySnapshot = library
                refreshAll()
            }
        }
        viewModelScope.launch {
            musicRepository.observeRecentlyPlayed(RECENTLY_PLAYED_LIMIT).collect { recent ->
                _uiState.value =
                    _uiState.value.copy(
                        recentlyPlayedRecs = HomeRecentMapper.fromSongs(recent),
                    )
            }
        }
        viewModelScope.launch {
            RepositoryProvider.downloadsRepository.observeDownloads().collect { downloads ->
                val labels =
                    downloads
                        .filter { it.status == UserDownloadStatus.COMPLETED }
                        .map { it.displayTitle }
                        .take(12)
                _uiState.value = _uiState.value.copy(savedClipLabels = labels)
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
        RecommendationSignalCollector.recordGenreTap(appContext, tab.label)
        refreshAll()
    }

    fun selectMood(moodId: MoodId) {
        moodStore.setSelectedMoodId(moodId)
        RecommendationSignalCollector.recordMoodSelect(appContext)
        val mood = MoodCatalog.byId(moodId)
        val genre = MoodEngine.genreTabForMood(mood)
        _uiState.value = _uiState.value.copy(selectedMood = mood, selectedGenre = genre)
        refreshAll()
    }

    fun onRecommendationOpened(rec: OnlineRecommendation) {
        if (rec.opensInInternalBrowser) {
            RecommendationSignalCollector.recordProviderOpen(appContext)
        } else if (rec.hasPreviewAudio) {
            RecommendationSignalCollector.recordPlay(appContext, rec.artist)
        }
    }

    fun refreshAll() {
        refreshJob?.cancel()
        refreshJob =
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(isLoading = true)
                val offline = !NetworkStatusMonitor.isOnline(appContext)
                val mood = MoodCatalog.byId(moodStore.getSelectedMoodId())
                val genre =
                    if (BeatIQFeatureFlags.moodEngineEnabled) {
                        MoodEngine.genreTabForMood(mood)
                    } else {
                        _uiState.value.selectedGenre
                    }
                val feed =
                    if (offline) {
                        HomeAiFeed(
                            genreTab = genre,
                            featured = emptyList(),
                            gridSixteen = emptyList(),
                            trending = emptyList(),
                            trendingAlbums = emptyList(),
                            djMix = emptyList(),
                            popularPlaylists = emptyList(),
                        )
                    } else {
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
                    }
                applyFeed(feed, mood, offline)
            }
    }

    private fun applyFeed(feed: HomeAiFeed, mood: MoodDefinition, offline: Boolean) {
        val tasteArtist =
            librarySnapshot
                .groupingBy { it.artist.trim() }
                .eachCount()
                .entries
                .maxByOrNull { it.value }
                ?.key
                ?.takeIf { it.isNotBlank() }
        val signals = signalsStore.loadSignals()
        val sectionOrder = HomeSectionOrderer.order(signals, mood, offline)

        val enriched =
            if (BeatIQFeatureFlags.premiumHomeEnabled && !offline) {
                HomeFeedEnricher.enrich(appContext, feed)
            } else {
                null
            }

        val featured = enriched?.featured ?: feed.featured
        val grid = enriched?.gridSixteen ?: feed.gridSixteen
        val trending = enriched?.trending ?: feed.trending
        val albums = enriched?.trendingAlbums ?: feed.trendingAlbums
        val dj = enriched?.djMix ?: feed.djMix
        val playlists = enriched?.popularPlaylists ?: feed.popularPlaylists
        val underground = enriched?.undergroundGems.orEmpty()
        val newFree = enriched?.newFreeMusic.orEmpty()
        val beatPreview = (featured + trending + underground).distinctBy { it.id }.take(12)

        _uiState.value =
            HomeAiUiState(
                selectedGenre = feed.genreTab,
                isLoading = false,
                featured = featured,
                gridSixteen = grid,
                trending = trending,
                trendingAlbums = albums,
                djMix = dj,
                popularPlaylists = playlists,
                lastRefreshedAt = System.currentTimeMillis(),
                tasteArtist = tasteArtist,
                selectedMood = mood,
                isOffline = offline,
                undergroundGems = underground,
                newFreeMusic = newFree,
                beatFeedPreview = beatPreview,
                recentlyPlayedRecs = _uiState.value.recentlyPlayedRecs,
                savedClipLabels = _uiState.value.savedClipLabels,
                globalHeatCards = GlobalHeatCatalog.cards,
                sectionOrder = sectionOrder,
            )
    }

    companion object {
        private const val AUTO_REFRESH_MS = 10 * 60 * 1000L
        private const val RECENTLY_PLAYED_LIMIT = 12

        @Suppress("UNCHECKED_CAST")
        val Factory: ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(HomeViewModel::class.java))
                    return HomeViewModel(
                        RepositoryProvider.musicRepository,
                        RepositoryProvider.requireApplication(),
                    ) as T
                }
            }
    }
}
