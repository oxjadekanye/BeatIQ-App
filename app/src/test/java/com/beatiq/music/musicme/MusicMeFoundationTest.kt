package com.beatiq.music.musicme

import com.beatiq.music.musicme.data.MusicMeCatalog
import com.beatiq.music.musicme.domain.LocalMusicMeRecommendationService
import com.beatiq.music.musicme.domain.MusicMeConfigurationValidator
import com.beatiq.music.musicme.model.*
import org.junit.Assert.*
import org.junit.Test

class MusicMeFoundationTest {
    @Test fun `genre catalogue exceeds required seventy options`() {
        assertTrue(MusicMeCatalog.genres.size >= 70)
    }

    @Test fun `every category is populated and ids are unique in category`() {
        assertEquals(OptionCategory.entries.toSet(), MusicMeCatalog.all.keys)
        MusicMeCatalog.all.forEach { (category, options) ->
            assertTrue("$category is empty", options.isNotEmpty())
            assertEquals("Duplicate ID in $category", options.size, options.map { it.id }.distinct().size)
            assertTrue(options.all { it.label.isNotBlank() && it.description.isNotBlank() && it.generationParameter.isNotBlank() })
        }
    }

    @Test fun `search finds genre by label and ignores case`() {
        val results = MusicMeCatalog.search(OptionCategory.GENRE, "AFRO")
        assertTrue(results.size >= 7)
        assertTrue(results.all { it.label.contains("afro", true) || it.description.contains("afro", true) })
    }

    @Test fun `defaults reference real catalogue options`() {
        val config = MusicMeConfiguration()
        assertTrue(MusicMeCatalog.genres.any { it.id == config.genres.primaryGenreId })
        config.selections.forEach { (category, ids) ->
            val valid = MusicMeCatalog.all.getValue(category).map { it.id }.toSet()
            assertTrue("Invalid default in $category: ${ids - valid}", ids.all { it in valid })
        }
        assertEquals(150, config.controls.durationSeconds)
        assertTrue(MusicMeConfigurationValidator.isValid(config))
    }

    @Test fun `birthday quick create is deterministic and meaningful`() {
        val service = LocalMusicMeRecommendationService()
        val first = service.recommend(MusicMeInput(occasionId = "birthday"))
        val second = service.recommend(MusicMeInput(occasionId = "birthday"))
        assertEquals(first.configuration, second.configuration)
        assertEquals("afrobeats", first.configuration.genres.primaryGenreId)
        assertTrue("joyful" in first.configuration.selections.getValue(OptionCategory.MOOD))
        assertEquals(112, first.configuration.bpm)
    }

    @Test fun `recommendation rules cover romantic memorial and worship contexts`() {
        val service = LocalMusicMeRecommendationService()
        assertEquals("contemporary_r_and_b", service.recommend(MusicMeInput(story = "A romantic anniversary dedication")).configuration.genres.primaryGenreId)
        assertEquals("acoustic_pop", service.recommend(MusicMeInput(story = "A memorial tribute")).configuration.genres.primaryGenreId)
        assertEquals("gospel", service.recommend(MusicMeInput(story = "A worship prayer")).configuration.genres.primaryGenreId)
    }

    @Test fun `surprise mode is seeded valid and repeatable`() {
        val service = LocalMusicMeRecommendationService()
        val first = service.surprise(42)
        assertEquals(first, service.surprise(42))
        assertEquals(CreationMode.SURPRISE, first.mode)
        assertTrue(MusicMeConfigurationValidator.isValid(first))
        assertTrue(first.sections.isNotEmpty())
    }

    @Test fun `instrumental selection removes vocal-only controls`() {
        val config = MusicMeConfiguration(selections = defaultSelections() +
            (OptionCategory.VOCAL_ARRANGEMENT to setOf("instrumental_only")))
        val normalized = MusicMeConfigurationValidator.normalize(config)
        assertTrue(normalized.selections.getValue(OptionCategory.VOCAL_CHARACTER).isEmpty())
        assertTrue(normalized.selections.getValue(OptionCategory.VOCAL_DELIVERY).isEmpty())
        assertTrue(MusicMeConfigurationValidator.isValid(normalized))
    }

    @Test fun `all required arrangements are available and ordered`() {
        val required = setOf("Classic Pop", "R&B Ballad", "Afrobeats Hit", "Hip-Hop Storytelling", "Gospel Worship", "Romantic Duet", "Dance Anthem", "Emotional Tribute", "Birthday Celebration", "Wedding Song", "Inspirational Anthem", "Cinematic Ballad", "Acoustic Storytelling")
        assertEquals(required, MusicMeCatalog.arrangements.keys)
        assertTrue(MusicMeCatalog.arrangements.values.all { it.size >= 5 })
    }
}
