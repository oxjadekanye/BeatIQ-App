package com.beatiq.music

import com.beatiq.music.core.features.BeatIQFeatureFlagOverrides
import com.beatiq.music.core.features.BeatIQFeatureFlags
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BeatIQFeatureFlagsTest {
    @After
    fun tearDown() {
        BeatIQFeatureFlagOverrides.premiumHomeEnabled = null
    }

    @Test
    fun explicitOverrideEnablesInReleasePath() {
        BeatIQFeatureFlagOverrides.premiumHomeEnabled = true
        assertTrue(BeatIQFeatureFlags.resolve(true))
    }

    @Test
    fun explicitOverrideDisables() {
        assertFalse(BeatIQFeatureFlags.resolve(false))
    }
}
