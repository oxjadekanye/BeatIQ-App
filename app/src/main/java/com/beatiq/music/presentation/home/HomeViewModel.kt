package com.beatiq.music.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.beatiq.music.data.model.Song
import com.beatiq.music.data.repository.MusicRepository
import com.beatiq.music.data.repository.PlayerRepository
import com.beatiq.music.features.library.RepositoryProvider
import com.beatiq.music.presentation.library.AlbumSection
import com.beatiq.music.presentation.library.ArtistSection
import com.beatiq.music.presentation.library.buildAlbumSections
import com.beatiq.music.presentation.library.buildArtistSections
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
)

class HomeViewModel(
    private val musicRepository: MusicRepository,
    private val playerRepository: PlayerRepository,
) : ViewModel() {

    val uiState =
        combine(
            musicRepository.observeRecentlyAdded(40),
            musicRepository.observeRecentlyPlayed(40),
            musicRepository.observeMostPlayed(40),
            musicRepository.observeFavoriteSongs(),
            musicRepository.observeAllSongs(),
        ) { recentAdded, recentPlayed, mostPlayed, favs, all ->
            HomeUiState(
                recentlyAdded = recentAdded,
                recentlyPlayed = recentPlayed,
                mostPlayed = mostPlayed,
                favorites = favs,
                albums = buildAlbumSections(all, maxSections = 32),
                artists = buildArtistSections(all, maxSections = 32),
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            HomeUiState(),
        )

    fun playSongWithQueue(song: Song, queue: List<Song>) {
        viewModelScope.launch {
            playerRepository.playSongWithQueue(song, queue)
        }
    }

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
