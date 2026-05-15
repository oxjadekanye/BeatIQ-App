package com.beatiq.music.data.repository

import com.beatiq.music.data.model.Song
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory mutable store shared by mock repositories for Phase 1 demos.
 *
 * Retained for tests / future preview mode — production uses Room.
 */
class MockMusicLibraryStore(
    initialSongs: List<Song>,
) {
    private val _songs = MutableStateFlow(initialSongs.toList())

    fun observe(): StateFlow<List<Song>> = _songs.asStateFlow()

    fun snapshot(): List<Song> = _songs.value

    fun replaceAll(items: Collection<Song>) {
        _songs.value = items.toList()
    }
}
