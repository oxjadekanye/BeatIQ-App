package com.beatiq.music.musicme

import com.beatiq.music.musicme.data.MusicMeCatalog
import com.beatiq.music.musicme.domain.MusicMeDraftPolicy
import com.beatiq.music.musicme.model.MusicMeDraft
import com.beatiq.music.musicme.model.MusicMeInput
import com.beatiq.music.musicme.model.MusicMeVoiceRecording
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale

class MusicMeDraftPolicyTest {
    private val at1432 = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 20, 14, 32) }.timeInMillis
    private val at1505 = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 20, 15, 5) }.timeInMillis

    @Test
    fun `blank defaults are not meaningful and are not persisted automatically`() {
        val blank = MusicMeDraft()
        assertFalse(MusicMeDraftPolicy.hasMeaningfulContent(blank))
        assertFalse(MusicMeDraftPolicy.shouldPersist(alreadyPersisted = false, userCustomisation = false, draft = blank))
        assertFalse(MusicMeDraftPolicy.hasMeaningfulContent(blank.copy(input = blank.input.copy(story = "   \n"))))
    }

    @Test
    fun `text recordings lyrics saves and deliberate customisation each count`() {
        val blank = MusicMeDraft()
        assertTrue(MusicMeDraftPolicy.hasMeaningfulContent(blank.copy(input = MusicMeInput(story = "Our first dance"))))
        assertTrue(MusicMeDraftPolicy.hasMeaningfulContent(blank.copy(input = MusicMeInput(recipient = "Ada"))))
        assertTrue(MusicMeDraftPolicy.hasMeaningfulContent(blank.copy(recording = MusicMeVoiceRecording(localFileName = "a.m4a", durationMs = 1))))
        val lyrics = blank.lyrics.copy(sections = blank.lyrics.sections.mapIndexed { i, s -> if (i == 0) s.copy(lyrics = "La la") else s })
        assertTrue(MusicMeDraftPolicy.hasMeaningfulContent(blank.copy(lyrics = lyrics)))
        assertTrue(MusicMeDraftPolicy.shouldPersist(alreadyPersisted = false, userCustomisation = true, draft = blank))
        assertTrue(MusicMeDraftPolicy.shouldPersist(alreadyPersisted = true, userCustomisation = false, draft = blank))
    }

    @Test
    fun `default names are descriptive and distinguishable`() {
        val uk = Locale.UK
        assertEquals("Birthday song for Ada", MusicMeDraftPolicy.defaultName(MusicMeInput(occasionId = "birthday", recipient = " Ada "), at1432, uk))
        assertEquals("Birthday song · 20 Oct 14:32", MusicMeDraftPolicy.defaultName(MusicMeInput(occasionId = "birthday"), at1432, uk))
        assertEquals("Story song · 20 Oct 14:32", MusicMeDraftPolicy.defaultName(MusicMeInput(), at1432, uk))
        val childrensId = MusicMeCatalog.occasions.single { it.label == "Children's Song" }.id
        assertEquals("Children's Song · 20 Oct 14:32", MusicMeDraftPolicy.defaultName(MusicMeInput(occasionId = childrensId), at1432, uk))
        assertEquals("Our Song", MusicMeDraftPolicy.defaultName(MusicMeInput(title = " Our Song ", occasionId = "birthday"), at1432, uk))
        assertNotEquals(
            MusicMeDraftPolicy.defaultName(MusicMeInput(), at1432, uk),
            MusicMeDraftPolicy.defaultName(MusicMeInput(), at1505, uk),
        )
        assertEquals("New song · 20 Oct 14:32", MusicMeDraftPolicy.defaultName(MusicMeInput(occasionId = "unknown"), at1432, uk))
    }

    @Test
    fun `customise is enabled by story, desired message or an available voice note`() {
        val blank = MusicMeDraft()
        val voice = MusicMeVoiceRecording(localFileName = "v.m4a", durationMs = 2_000)
        fun eligible(draft: MusicMeDraft, file: Boolean = false) = MusicMeDraftPolicy.canContinueToCustomisation(draft, file)

        assertTrue("story", eligible(blank.copy(input = MusicMeInput(story = "Our story"))))
        assertTrue("desired message only", eligible(blank.copy(input = MusicMeInput(desiredMessage = "Happy 30th, Ada"))))
        assertTrue("voice note with audio", eligible(blank.copy(recording = voice), file = true))

        assertFalse("empty", eligible(blank))
        assertFalse("whitespace story", eligible(blank.copy(input = MusicMeInput(story = "  \n\t "))))
        assertFalse("whitespace message", eligible(blank.copy(input = MusicMeInput(desiredMessage = "   "))))
        assertFalse("voice note whose file is missing", eligible(blank.copy(recording = voice), file = false))
        assertFalse("zero-length voice note", eligible(blank.copy(recording = voice.copy(durationMs = 0)), file = true))
        assertFalse(
            "title, dedication, emotion and pronunciation alone are context, not content",
            eligible(blank.copy(input = MusicMeInput(title = "T", recipient = "Ada", emotionalContext = "Joy", pronunciationNotes = "AH-dah"))),
        )
    }

    @Test
    fun `colliding generated names get a numeric suffix`() {
        assertEquals("Story song", MusicMeDraftPolicy.uniqueName("Story song", listOf("Other")))
        assertEquals("Story song (2)", MusicMeDraftPolicy.uniqueName("Story song", listOf("Story song")))
        assertEquals("Story song (3)", MusicMeDraftPolicy.uniqueName("Story song", listOf("Story song", "Story song (2)")))
    }

    @Test
    fun `legacy untitled drafts display descriptively without changing stored names`() {
        val legacy = MusicMeDraft(name = MusicMeDraftPolicy.LEGACY_UNTITLED_NAME, createdAt = at1432, input = MusicMeInput(occasionId = "wedding"))
        assertEquals("Wedding song · 20 Oct 14:32", MusicMeDraftPolicy.displayName(legacy, Locale.UK))
        assertEquals(MusicMeDraftPolicy.LEGACY_UNTITLED_NAME, legacy.name)
        assertEquals("Kept name", MusicMeDraftPolicy.displayName(legacy.copy(name = "Kept name"), Locale.UK))
    }

    @Test
    fun `storage and availability copy makes no unsupported claims`() {
        val copy = listOf(
            MusicMeCopy.STORAGE_BADGE, MusicMeCopy.STORAGE_SUMMARY, MusicMeCopy.STORAGE_DETAIL, MusicMeCopy.DRAFT_SAVED,
            MusicMeCopy.TRANSCRIPTION_TITLE, MusicMeCopy.TRANSCRIPTION_BODY, MusicMeCopy.AI_LYRICS_TITLE,
            MusicMeCopy.AI_LYRICS_BODY, MusicMeCopy.GENERATION_BUTTON, MusicMeCopy.GENERATION_BODY,
            MusicMeCopy.HERO_TITLE, MusicMeCopy.HERO_BODY,
        ).joinToString(" ").lowercase()
        listOf("nothing leaves", "never leaves", "coming soon", "phase 2", "will be enabled", "encrypted", "private • on device")
            .forEach { phrase -> assertFalse("Copy must not claim \"$phrase\"", copy.contains(phrase)) }
        assertTrue(MusicMeCopy.STORAGE_DETAIL.contains("device backup"))
        assertTrue(MusicMeCopy.STORAGE_DETAIL.contains("export"))
    }
}
