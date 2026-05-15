package com.beatiq.music.presentation.discover

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DiscoverUiState(
    val searchQuery: String = "",
    val selectedGenre: String? = null,
    val genres: List<String> = emptyList(),
    val filteredSongs: List<Song> = emptyList(),
    val suggestedSongs: List<Song> = emptyList(),
    val albums: List<AlbumSection> = emptyList(),
    val artists: List<ArtistSection> = emptyList(),
)

class DiscoverViewModel(
    private val musicRepository: MusicRepository,
    private val playerRepository: PlayerRepository,
) : ViewModel() {

    private val searchQuery = MutableStateFlow("")
    private val selectedGenre = MutableStateFlow<String?>(null)

    val uiState =
        combine(
            musicRepository.observeAllSongs(),
            searchQuery,
            selectedGenre,
            musicRepository.observeRecentlyPlayed(24),
        ) { allSongs, query, genre, recentPlayed ->
            val genres =
                allSongs
                    .map { it.genre.trim() }
                    .filter { it.isNotEmpty() }
                    .distinctBy { it.lowercase() }
                    .sortedBy { it.lowercase() }
            val filtered =
                allSongs.filter { song ->
                    val genreOk =
                        genre == null ||
                            song.genre.trim().equals(genre, ignoreCase = true)
                    if (!genreOk) return@filter false
                    if (query.isBlank()) return@filter true
                    val q = query.trim().lowercase()
                    song.title.lowercase().contains(q) ||
                        song.artist.lowercase().contains(q) ||
                        song.album.lowercase().contains(q) ||
                        song.genre.lowercase().contains(q)
                }
            DiscoverUiState(
                searchQuery = query,
                selectedGenre = genre,
                genres = genres,
                filteredSongs = filtered,
                suggestedSongs = recentPlayed.ifEmpty { allSongs.take(12) }.take(12),
                albums = buildAlbumSections(allSongs, maxSections = 24),
                artists = buildArtistSections(allSongs, maxSections = 24),
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            DiscoverUiState(),
        )

    fun setSearchQuery(value: String) {
        searchQuery.update { value }
    }

    fun selectGenre(genre: String?) {
        selectedGenre.value = genre
    }

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
                    require(modelClass.isAssignableFrom(DiscoverViewModel::class.java))
                    return DiscoverViewModel(
                        RepositoryProvider.musicRepository,
                        RepositoryProvider.playerRepository,
                    ) as T
                }
            }
    }
}
