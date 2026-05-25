package com.beatiq.music

import com.beatiq.music.features.mood.MoodCatalog
import com.beatiq.music.features.mood.MoodId
import org.junit.Assert.assertEquals
import org.junit.Test

class MoodCatalogTest {
    @Test
    fun catalogHasThirteenMoods() {
        assertEquals(13, MoodCatalog.all.size)
    }

    @Test
    fun byIdReturnsAfrobeatsEnergy() {
        val mood = MoodCatalog.byId(MoodId.AFROBEATS_ENERGY)
        assertEquals("Afrobeats Energy", mood.displayName)
    }
}
