package com.beatiq.music

import com.beatiq.music.features.ai_dj.AIDJCommandParser
import com.beatiq.music.features.ai_dj.AIDJIntent
import org.junit.Assert.assertEquals
import org.junit.Test

class AIDJCommandParserTest {
    @Test
    fun parsesTrendingAfrobeats() {
        val cmd = AIDJCommandParser.parse("Play trending Afrobeats")
        assertEquals(AIDJIntent.TRENDING_AFROBEATS, cmd.intent)
    }

    @Test
    fun parsesLateNight() {
        val cmd = AIDJCommandParser.parse("Find late night songs")
        assertEquals(AIDJIntent.LATE_NIGHT, cmd.intent)
    }

    @Test
    fun parsesSurprise() {
        val cmd = AIDJCommandParser.parse("Surprise me")
        assertEquals(AIDJIntent.SURPRISE, cmd.intent)
    }
}
