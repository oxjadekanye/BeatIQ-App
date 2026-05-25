package com.beatiq.music

import com.beatiq.music.data.model.Song
import com.beatiq.music.presentation.home.HomeRecentMapper
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeRecentMapperTest {
    @Test
    fun sortsByRecentlyPlayedAtDescending() {
        val songs =
            listOf(
                song(id = "1", playedAt = 100L),
                song(id = "2", playedAt = 300L),
                song(id = "3", playedAt = null),
                song(id = "4", playedAt = 200L),
            )
        val mapped = HomeRecentMapper.fromSongs(songs)
        assertEquals("local_2", mapped[0].id)
        assertEquals("local_4", mapped[1].id)
        assertEquals("local_1", mapped[2].id)
        assertEquals(3, mapped.size)
    }

    private fun song(id: String, playedAt: Long?) =
        Song(
            id = id,
            title = "T$id",
            artist = "A",
            album = "B",
            genre = "Pop",
            durationMs = 1000,
            filePath = "/m/$id",
            artworkUri = null,
            dateAdded = 0,
            playCount = 0,
            isFavorite = false,
            recentlyPlayedAt = playedAt,
        )
}
