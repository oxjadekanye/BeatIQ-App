package com.beatiq.music.musicme

import android.app.Application
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.beatiq.music.core.auth.AuthPreferences
import com.beatiq.music.musicme.data.MusicMeRepository
import com.beatiq.music.musicme.domain.MusicMeDraftPolicy
import com.beatiq.music.musicme.model.CreationMode
import com.beatiq.music.musicme.model.MusicMeConfiguration
import com.beatiq.music.musicme.model.MusicMeDraft
import com.beatiq.music.musicme.model.MusicMeInput
import com.beatiq.music.musicme.model.MusicMeLyricsDraft
import com.beatiq.music.musicme.model.MusicMePreset
import com.beatiq.music.musicme.model.MusicMeSongSection
import com.beatiq.music.musicme.model.MusicMeVoiceRecording
import com.beatiq.music.musicme.model.OptionCategory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.UUID

/** Drives the real ViewModel and repository with synthetic accounts to verify when drafts are written. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MusicMeViewModelDraftTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var account: String
    private var previousUserId: String? = null

    @Before
    fun signIn() {
        previousUserId = AuthPreferences(app).userId
        account = "musicme-ui-${UUID.randomUUID()}"
        AuthPreferences(app).userId = account
    }

    @After
    fun restore() {
        AuthPreferences(app).userId = previousUserId
    }

    @Test
    fun startingAndAbandoningANewCreationNeverAddsABlankDraft() {
        val vm = MusicMeViewModel(app, SavedStateHandle())
        settle()
        vm.newDraft()
        val id = vm.state.value.draft!!.id
        assertEquals("Not saved yet", vm.state.value.saveStatus())

        // Automatic studio defaults are not user content.
        vm.applyRecommendations()
        vm.setMode(CreationMode.GUIDED)
        vm.surprise()
        settle(2_000)
        assertFalse(vm.state.value.draftPersisted)
        assertFalse(storedIds().contains(id))
        assertTrue(vm.state.value.drafts.isEmpty())

        vm.back()
        vm.newDraft(occasionId = "birthday", mode = CreationMode.SURPRISE, destination = MusicMeDestination.ADVANCED)
        settle(2_000)
        assertTrue(storedIds().isEmpty())
    }

    @Test
    fun meaningfulInputExplicitSaveAndDeliberateCustomisationPersist() {
        val vm = MusicMeViewModel(app, SavedStateHandle())
        settle()

        vm.newDraft()
        val storyDraft = vm.state.value.draft!!.id
        vm.updateInput { it.copy(story = "We met under the Lagos rain") }
        settle(2_000)
        assertTrue(storedIds().contains(storyDraft))
        assertEquals("Saved", vm.state.value.saveStatus())

        vm.newDraft(occasionId = "birthday")
        val savedBlank = vm.state.value.draft!!.id
        vm.saveNow()
        settle(2_000)
        assertTrue(storedIds().contains(savedBlank))

        vm.newDraft()
        val customised = vm.state.value.draft!!.id
        vm.toggleSelection(OptionCategory.MOOD, "hopeful", multi = true)
        settle(2_000)
        assertTrue(storedIds().contains(customised))

        val names = runBlocking { MusicMeRepository(app, account).drafts.first() }.map(MusicMeDraftPolicy::displayName)
        assertEquals(3, names.toSet().size)
        assertTrue(names.any { it.startsWith("Birthday song") })
        assertFalse(names.any { it == MusicMeDraftPolicy.LEGACY_UNTITLED_NAME })
    }

    @Test
    fun existingDraftsAreShownUnchangedAndStayIsolatedPerAccount() {
        val repository = MusicMeRepository(app, account)
        val legacy = MusicMeDraft(name = MusicMeDraftPolicy.LEGACY_UNTITLED_NAME)
        runBlocking { repository.upsertDraft(legacy) }

        val vm = MusicMeViewModel(app, SavedStateHandle())
        settle()
        assertEquals(listOf(legacy.id), vm.state.value.drafts.map { it.id })
        vm.openDraft(legacy.id)
        settle(1_000)
        assertTrue(vm.state.value.draftPersisted)
        assertEquals(MusicMeDraftPolicy.LEGACY_UNTITLED_NAME, runBlocking { repository.drafts.first() }.single().name)
        assertNotEquals(MusicMeDraftPolicy.LEGACY_UNTITLED_NAME, MusicMeDraftPolicy.displayName(legacy))

        AuthPreferences(app).userId = "musicme-other-${UUID.randomUUID()}"
        val other = MusicMeViewModel(app, SavedStateHandle())
        settle()
        assertTrue(other.state.value.drafts.isEmpty())
    }

    @Test
    fun clearAndUndoNeverCrossDraftsOrOverwriteNewText() {
        val repository = MusicMeRepository(app, account)
        val a = MusicMeDraft(name = "A", input = MusicMeInput(story = "Story A"))
        val b = MusicMeDraft(name = "B", input = MusicMeInput(story = "Story B"))
        runBlocking { repository.upsertDraft(a); repository.upsertDraft(b) }
        val vm = MusicMeViewModel(app, SavedStateHandle())
        settle()

        vm.openDraft(a.id)
        vm.clearStory()
        assertTrue(vm.state.value.canUndoClear)
        vm.undoClearStory()
        settle(1_500)
        assertEquals("Story A", stored(a.id).input.story)

        vm.clearStory()
        vm.updateInput { it.copy(story = "New words") }
        assertFalse("Undo must not replace text typed after Clear", vm.state.value.canUndoClear)
        vm.undoClearStory()
        assertEquals("New words", vm.state.value.draft!!.input.story)

        vm.updateInput { it.copy(story = "") }
        vm.openDraft(b.id)
        assertFalse(vm.state.value.canUndoClear)
        vm.undoClearStory()
        settle(1_500)
        assertEquals("Story B", stored(b.id).input.story)
    }

    @Test
    fun continuingFromInputKeepsSavedStudioSettings() {
        val repository = MusicMeRepository(app, account)
        val custom = MusicMeConfiguration(mode = CreationMode.ADVANCED, bpm = 151, arrangementName = "Custom")
        val saved = MusicMeDraft(name = "Tuned", input = MusicMeInput(story = "Wedding toast"), configuration = custom)
        runBlocking { repository.upsertDraft(saved) }
        val vm = MusicMeViewModel(app, SavedStateHandle())
        settle()

        vm.openDraft(saved.id)
        vm.continueToCustomisation()
        settle(1_500)
        assertEquals(MusicMeDestination.GUIDED, vm.state.value.destination)
        assertEquals(custom, vm.state.value.draft!!.configuration)
        assertEquals(custom, stored(saved.id).configuration)

        // A brand-new creation still gets story-based suggestions.
        vm.newDraft()
        vm.updateInput { it.copy(story = "A worship song for my church choir") }
        vm.continueToCustomisation()
        assertEquals(CreationMode.GUIDED, vm.state.value.draft!!.configuration.mode)
    }

    @Test
    fun homeSurpriseAndPresetsStartNewCreationsInsteadOfOverwritingTheOpenDraft() {
        val repository = MusicMeRepository(app, account)
        val custom = MusicMeConfiguration(bpm = 133, arrangementName = "Mine")
        val saved = MusicMeDraft(name = "Keep me", input = MusicMeInput(story = "Keep"), configuration = custom)
        val preset = MusicMePreset(name = "Gospel", configuration = MusicMeConfiguration(bpm = 72))
        runBlocking { repository.upsertDraft(saved); repository.savePreset(preset) }
        val vm = MusicMeViewModel(app, SavedStateHandle())
        settle()

        vm.openDraft(saved.id)
        vm.back()
        vm.startSurprise()
        assertNotEquals(saved.id, vm.state.value.draft!!.id)
        vm.back()
        vm.startFromPreset(preset.id)
        assertNotEquals(saved.id, vm.state.value.draft!!.id)
        assertEquals(72, vm.state.value.draft!!.configuration.bpm)
        settle(1_500)
        assertEquals(custom, stored(saved.id).configuration)
        assertEquals("Keep", stored(saved.id).input.story)
    }

    @Test
    fun studioReplacementsAndModeSwitchesKeepContentAndCanBeUndone() {
        val repository = MusicMeRepository(app, account)
        val custom = MusicMeConfiguration(mode = CreationMode.ADVANCED, bpm = 140, arrangementName = "Mine")
        val lyrics = MusicMeLyricsDraft(title = "T", sections = listOf(MusicMeSongSection(type = "verse", lyrics = "Line", order = 0)))
        val recording = MusicMeVoiceRecording(localFileName = "voice.m4a", durationMs = 1_000)
        val saved = MusicMeDraft(name = "Full", input = MusicMeInput(story = "Story", recipient = "Ada"), configuration = custom, lyrics = lyrics, recording = recording)
        runBlocking { repository.upsertDraft(saved) }
        val vm = MusicMeViewModel(app, SavedStateHandle())
        settle()
        vm.openDraft(saved.id, MusicMeDestination.ADVANCED)

        for (replace in listOf(vm::quickDefaults, vm::surprise, vm::resetConfiguration)) {
            replace()
            val changed = vm.state.value.draft!!
            assertEquals(saved.input, changed.input)
            assertEquals(saved.lyrics, changed.lyrics)
            assertEquals(saved.recording, changed.recording)
            assertTrue(vm.state.value.canUndoConfiguration)
            vm.undoConfigurationChange()
            assertEquals(custom, vm.state.value.draft!!.configuration)
        }

        vm.setMode(CreationMode.GUIDED)
        vm.setMode(CreationMode.QUICK)
        val afterModes = vm.state.value.draft!!
        assertEquals(custom.copy(mode = CreationMode.QUICK), afterModes.configuration)
        assertEquals(saved.input, afterModes.input)
        assertEquals(saved.lyrics, afterModes.lyrics)
        settle(1_500)
        assertEquals(custom.copy(mode = CreationMode.QUICK), stored(saved.id).configuration)
        assertEquals(saved.recording, stored(saved.id).recording)
    }

    @Test
    fun switchingDraftsDuringAutosaveKeepsEachDraftsOwnEdits() {
        val repository = MusicMeRepository(app, account)
        val a = MusicMeDraft(name = "A", input = MusicMeInput(story = "Story A"))
        val b = MusicMeDraft(name = "B", input = MusicMeInput(story = "Story B"))
        runBlocking { repository.upsertDraft(a); repository.upsertDraft(b) }
        val vm = MusicMeViewModel(app, SavedStateHandle())
        settle()

        vm.openDraft(a.id)
        vm.updateInput { it.copy(story = "Story A edited") }
        vm.openDraft(b.id) // before the 700ms autosave fires
        settle(1_500)
        assertEquals("Story A edited", stored(a.id).input.story)
        assertEquals("Story B", stored(b.id).input.story)
    }

    @Test
    fun customiseAcceptsEachValidPathKeepsAllInputAndBlocksEmptyOnes() {
        val vm = MusicMeViewModel(app, SavedStateHandle())
        settle()

        // Invalid: nothing, whitespace, context-only fields, voice note with no audio file.
        vm.newDraft()
        vm.continueToCustomisation()
        assertEquals(MusicMeDestination.INPUT, vm.state.value.destination)
        vm.updateInput { it.copy(story = "   ", desiredMessage = "\n ", title = "Title", recipient = "Ada") }
        vm.continueToCustomisation()
        assertEquals(MusicMeDestination.INPUT, vm.state.value.destination)
        vm.attachRecording(MusicMeVoiceRecording(localFileName = "missing.m4a", durationMs = 3_000))
        vm.continueToCustomisation()
        assertEquals(MusicMeDestination.INPUT, vm.state.value.destination)

        // Valid: desired message only; every field survives the move to customisation.
        vm.newDraft()
        val entered = MusicMeInput(
            title = "Thirty", recipient = "Ada", occasionId = "birthday", desiredMessage = "Happy 30th, Ada",
            emotionalContext = "Joyful", pronunciationNotes = "AH-dah",
        )
        vm.updateInput { entered }
        vm.continueToCustomisation()
        assertEquals(MusicMeDestination.GUIDED, vm.state.value.destination)
        assertEquals(entered, vm.state.value.draft!!.input)
        settle(1_500)
        assertEquals(entered, stored(vm.state.value.draft!!.id).input)

        // Valid: story only.
        vm.newDraft()
        vm.updateInput { it.copy(story = "We met in Lagos") }
        vm.continueToCustomisation()
        assertEquals(MusicMeDestination.GUIDED, vm.state.value.destination)
        assertEquals("We met in Lagos", vm.state.value.draft!!.input.story)

        // Valid: a voice note whose audio exists in this account's storage.
        vm.newDraft()
        val recording = MusicMeVoiceRecording(localFileName = vm.repository.recordingFile("voice-ok").name, durationMs = 2_500)
        vm.repository.recordingFile("voice-ok").writeBytes(ByteArray(64) { 1 })
        vm.attachRecording(recording)
        vm.continueToCustomisation()
        assertEquals(MusicMeDestination.GUIDED, vm.state.value.destination)
        assertEquals(recording, vm.state.value.draft!!.recording)
    }

    private fun stored(id: String): MusicMeDraft = runBlocking { MusicMeRepository(app, account).drafts.first() }.single { it.id == id }

    private fun storedIds(): Set<String> = runBlocking { MusicMeRepository(app, account).drafts.first() }.map { it.id }.toSet()

    /** Advances the main looper (autosave delay) while DataStore completes its IO-thread writes. */
    private fun settle(millis: Long = 500) {
        val looper = shadowOf(Looper.getMainLooper())
        repeat((millis / 50).toInt().coerceAtLeast(1)) {
            looper.idleFor(Duration.ofMillis(50))
            Thread.sleep(15)
        }
        looper.idle()
    }
}
