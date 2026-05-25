package com.beatiq.music

import com.beatiq.music.features.home.HomeSectionOrderer
import com.beatiq.music.features.home.PremiumHomeSection
import com.beatiq.music.features.mood.MoodCatalog
import com.beatiq.music.features.mood.MoodId
import com.beatiq.music.features.recommendations.intelligence.RecommendationSignals
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeSectionOrdererTest {
    @Test
    fun undergroundSectionAppearsOnlyOnce() {
        val signals =
            RecommendationSignals(
                searches = listOf("underground afrobeats"),
                saves = 5,
                plays = 6,
            )
        val mood = MoodCatalog.byId(MoodId.AFROBEATS_ENERGY)
        val order = HomeSectionOrderer.order(signals, mood, isOffline = false)
        assertEquals(1, order.count { it == PremiumHomeSection.UNDERGROUND })
    }

    @Test
    fun offlineOrderHasNoDuplicates() {
        val mood = MoodCatalog.byId(MoodId.CHILL_DISCOVERY)
        val order = HomeSectionOrderer.order(RecommendationSignals(), mood, isOffline = true)
        assertEquals(order.size, order.distinct().size)
    }
}
