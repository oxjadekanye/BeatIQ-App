package com.beatiq.music

import com.beatiq.music.features.search.SmartSearchMapper
import com.beatiq.music.presentation.home.HomeGenreTab
import org.junit.Assert.assertEquals
import org.junit.Test

class SmartSearchMapperTest {
    @Test
    fun mapsSadAfrobeatsChip() {
        val mapped = SmartSearchMapper.map("sad afrobeats")
        assertEquals(HomeGenreTab.AFROBEATS, mapped.genreTab)
        assertEquals("Heartbreak", mapped.moodHint)
    }

    @Test
    fun passesThroughUnknownQuery() {
        val mapped = SmartSearchMapper.map("custom artist")
        assertEquals("custom artist", mapped.providerQuery)
    }
}
