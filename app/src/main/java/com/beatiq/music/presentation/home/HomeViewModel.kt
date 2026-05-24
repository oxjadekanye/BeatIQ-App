package com.beatiq.music.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.beatiq.music.core.recommendations.OnlineRecommendationEngine
import com.beatiq.music.data.model.OnlineRecommendation
import com.beatiq.music.data.model.Song
import com.beatiq.music.data.repository.MusicRepository
import com.beatiq.music.data.repository.PlayerRepository
import com.beatiq.music.features.library.RepositoryProvider
import com.beatiq.music.presentation.library.AlbumSection
import com.beatiq.music.presentation.library.ArtistSection
import com.beatiq.music.presentation.library.buildAlbumSections
import com.beatiq.music.presentation.library.buildArtistSections
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(
    val recentlyAdded: List<Song> = emptyList(),
    val recentlyPlayed: List<Song> = emptyList(),
    val mostPlayed: List<Song> = emptyList(),
    val favorites: List<Song> = emptyList(),
    val albums: List<AlbumSection> = emptyList(),
    val artists: List<ArtistSection> = emptyList(),
    val onlineRecommendations: List<OnlineRecommendation> = emptyList(),
    val recommendationsLoading: Boolean = false,
)

class HomeViewModel(
    private val musicRepository: MusicRepository,
    private val playerRepository: PlayerRepository,
) : ViewModel() {

    private val onlineRecommendations = MutableStateFlow<List<OnlineRecommendation>>(emptyList())
    private val recommendationsLoading = MutableStateFlow(false)

    val uiState =
        combine(
            combine(
                musicRepository.observeRecentlyAdded(40),
                musicRepository.observeRecentlyPlayed(40),
                musicRepository.observeMostPlayed(40),
                musicRepository.observeFavoriteSongs(),
                musicRepository.observeAllSongs(),
            ) { recentAdded, recentPlayed, mostPlayed, favs, all ->
                LibrarySnapshot(
                    recentAdded = recentAdded,
                    recentPlayed = recentPlayed,
                    mostPlayed = mostPlayed,
                    favorites = favs,
                    all = all,
                )
            },
            onlineRecommendations,
            recommendationsLoading,
        ) { library, online, loading ->
            HomeUiState(
                recentlyAdded = library.recentAdded,
                recentlyPlayed = library.recentPlayed,
                mostPlayed = library.mostPlayed,
                favorites = library.favorites,
                albums = buildAlbumSections(library.all, maxSections = 32),
                artists = buildArtistSections(library.all, maxSections = 32),
                onlineRecommendations = online,
                recommendationsLoading = loading,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            HomeUiState(),
        )

    init {
        viewModelScope.launch {
            musicRepository.observeAllSongs().collect { library ->
                recommendationsLoading.value = true
                onlineRecommendations.value =
                    runCatching { OnlineRecommendationEngine.recommendForLibrary(library) }
                        .getOrElse { emptyList() }
                recommendationsLoading.value = false
            }
        }
    }

    fun playSongWithQueue(song: Song, queue: List<Song>) {
        viewModelScope.launch {
            playerRepository.playSongWithQueue(song, queue)
        }
    }

    private data class LibrarySnapshot(
        val recentAdded: List<Song>,
        val recentPlayed: List<Song>,
        val mostPlayed: List<Song>,
        val favorites: List<Song>,
        val all: List<Song>,
    )

    companion object {
        @Suppress("UNCHECKED_CAST")
        val Factory: ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(HomeViewModel::class.java))
                    return HomeViewModel(
                        RepositoryProvider.musicRepository,
                        RepositoryProvider.playerRepository,
                    ) as T
                }
            }
    }
}
