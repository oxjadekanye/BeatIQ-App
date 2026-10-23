package com.beatiq.music.musicme.songwriting

import android.app.Application
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.beatiq.music.core.auth.AuthPreferences
import com.beatiq.music.musicme.MusicMeDestination
import com.beatiq.music.musicme.MusicMeViewModel
import com.beatiq.music.musicme.data.MusicMeRepository
import com.beatiq.music.musicme.model.MusicMeDraft
import com.beatiq.music.musicme.model.MusicMePendingSongwritingJob
import com.beatiq.music.musicme.model.MusicMeSongwritingState
import com.beatiq.music.musicme.model.SectionOrigin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.UUID

/** Real ViewModel + repository with a scripted songwriting service and synthetic accounts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MusicMeSongwritingViewModelTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var account: String
    private var previousUser: String? = null
    private lateinit var fake: FakeSongwritingService

    @Before
    fun setUp() {
        previousUser = AuthPreferences(app).userId
        account = "sw-vm-${UUID.randomUUID()}"
        AuthPreferences(app).userId = account
        fake = FakeSongwritingService()
        SongwritingDependencies.serviceFactory = { fake }
        SongwritingDependencies.sleep = { }
        SongwritingDependencies.log = SongwritingLog { }
    }

    @After
    fun tearDown() {
        // DataStore runs edit transforms in the caller's context (the paused main looper here). Robolectric
        // drops queued main-looper work between tests, so finish in-flight saves before the test ends.
        fake.gate?.complete(Unit)
        settle(2_000)
        SongwritingDependencies.reset()
        AuthPreferences(app).userId = previousUser
    }

    private fun openStoryDraft(consent: Boolean = true, draft: MusicMeDraft = storyDraft()): Pair<MusicMeViewModel, String> {
        runBlocking {
            MusicMeRepository(app, account).upsertDraft(draft)
            if (consent) MusicMeRepository(app, account).setSongwritingConsent(true)
        }
        val vm = MusicMeViewModel(app, SavedStateHandle())
        settle()
        vm.openDraft(draft.id, MusicMeDestination.LYRICS)
        settle()
        return vm to draft.id
    }

    private fun stored(id: String) = runBlocking { MusicMeRepository(app, account).drafts.first() }.single { it.id == id }

    @Test
    fun `without a configured service nothing is sent and the reason is shown`() {
        SongwritingDependencies.serviceFactory = { UnconfiguredSongwritingService }
        val (vm, id) = openStoryDraft()
        assertFalse(vm.state.value.songwriting.configured)
        vm.generateLyrics()
        awaitSongwriting(vm)
        assertEquals(SongwritingErrorCode.NOT_CONFIGURED, vm.state.value.songwriting.error!!.code)
        assertTrue(stored(id).lyrics.sections.all { it.lyrics.isBlank() })
    }

    @Test
    fun `first generation asks for consent once per account, then writes grounded lyrics`() {
        val (vm, id) = openStoryDraft(consent = false)
        val before = stored(id)
        vm.generateLyrics()
        awaitSongwriting(vm)
        assertNotNull(vm.state.value.songwriting.consentRequest)
        assertTrue(fake.submits.isEmpty())

        vm.grantSongwritingConsent()
        awaitSongwriting(vm)
        val after = stored(id)
        assertEquals(before.input, after.input)
        assertEquals(before.configuration, after.configuration)
        val sung = after.lyrics.sections.filter { SectionType.fromWire(it.type)!!.sung }
        assertTrue(sung.all { it.origin == SectionOrigin.AI && it.lyrics.isNotBlank() })
        assertTrue(after.lyrics.sections.any { "harbour" in it.lyrics })
        assertEquals("test-model", after.songwriting!!.lastPlan!!.modelId)
        assertNull(after.songwriting!!.pendingJob)
        assertFalse(vm.state.value.songwriting.running)

        vm.generateLyrics()
        awaitSongwriting(vm)
        assertNull(vm.state.value.songwriting.consentRequest)
        assertEquals(2, fake.submits.size)
        assertFalse(runBlocking { MusicMeRepository(app, "someone-else-${UUID.randomUUID()}").hasSongwritingConsent() })
    }

    @Test
    fun `regenerating keeps edited sections and rewriting one section changes only that section`() {
        val (vm, id) = openStoryDraft()
        vm.generateLyrics()
        awaitSongwriting(vm)
        val first = stored(id).lyrics.sections
        val verse1 = first.single { it.label == "Verse 1" }
        vm.editSectionLyrics(verse1.id, "${verse1.lyrics}\nmy own extra line")
        settle(1_500)
        assertEquals(SectionOrigin.AI_EDITED, stored(id).lyrics.sections.single { it.id == verse1.id }.origin)

        fake.planFor = { groundedPlan(it, tag = "v2", requestId = "req-2") }
        vm.generateLyrics()
        awaitSongwriting(vm)
        val second = stored(id).lyrics.sections
        assertTrue(second.single { it.id == verse1.id }.lyrics.endsWith("my own extra line"))
        assertTrue(second.single { it.label == "Verse 2" }.lyrics.contains("v2"))
        assertEquals(verse1.id, fake.submits.last().first.slots.single { it.lockedLyrics != null }.id)

        val chorus = second.first { it.label == "Chorus" }
        fake.planFor = { groundedPlan(it, tag = "v3", requestId = "req-3") }
        vm.regenerateSection(second.single { it.label == "Bridge" }.id)
        awaitSongwriting(vm)
        val third = stored(id).lyrics.sections
        assertTrue(third.single { it.label == "Bridge" }.lyrics.contains("v3"))
        assertEquals(chorus, third.first { it.label == "Chorus" })
        assertTrue(third.single { it.id == verse1.id }.lyrics.endsWith("my own extra line"))

        vm.undoSongwriting()
        settle(1_500)
        assertEquals(second, stored(id).lyrics.sections)
    }

    @Test
    fun `typing while lyrics are being written is never overwritten`() {
        val (vm, id) = openStoryDraft()
        fake.gate = CompletableDeferred()
        vm.generateLyrics()
        awaitSongwriting(vm)
        assertTrue(vm.state.value.songwriting.running)
        val chorusId = vm.state.value.draft!!.lyrics.sections.first { it.label == "Chorus" }.id
        vm.editSectionLyrics(chorusId, "I wrote this chorus myself")
        fake.gate!!.complete(Unit)
        settle(1_500)
        val sections = stored(id).lyrics.sections
        assertEquals("I wrote this chorus myself", sections.single { it.id == chorusId }.lyrics)
        assertEquals(SectionOrigin.USER, sections.single { it.id == chorusId }.origin)
        assertTrue(sections.single { it.label == "Verse 1" }.lyrics.isNotBlank())
    }

    @Test
    fun `cancelling and backend failures leave lyrics untouched with a clear message`() {
        val (vm, id) = openStoryDraft()
        fake.gate = CompletableDeferred()
        vm.generateLyrics()
        awaitSongwriting(vm)
        vm.cancelSongwriting()
        awaitSongwriting(vm)
        assertFalse(vm.state.value.songwriting.running)
        assertEquals(listOf("job-1"), fake.cancels)
        assertTrue(stored(id).lyrics.sections.all { it.lyrics.isBlank() })
        assertNull(stored(id).songwriting?.pendingJob)

        fake.gate = null
        repeat(4) { fake.submitFailures += SongwritingException(SongwritingErrorCode.OFFLINE, RemoteSongwritingService.defaultMessage(SongwritingErrorCode.OFFLINE)) }
        vm.generateLyrics()
        awaitSongwriting(vm)
        val error = vm.state.value.songwriting.error!!
        assertEquals(SongwritingErrorCode.OFFLINE, error.code)
        assertTrue(error.retryable && error.message.contains("offline"))
        assertTrue(stored(id).lyrics.sections.all { it.lyrics.isBlank() })

        fake.planFor = { groundedPlan(it).copy(title = "Love", summary = "A love song", sections = groundedPlan(it).sections.map { s -> s.copy(lyrics = "Baby tonight ${s.slotId.take(3)}\nHold me close") }) }
        vm.generateLyrics()
        awaitSongwriting(vm)
        assertEquals(SongwritingErrorCode.MODEL_OUTPUT_INVALID, vm.state.value.songwriting.error!!.code)
        assertTrue("generic output must never be applied", stored(id).lyrics.sections.all { it.lyrics.isBlank() })
    }

    @Test
    fun `blocked inputs are explained before anything is sent`() {
        val draft = storyDraft().let { it.copy(input = it.input.copy(desiredMessage = "Make it sound like Burna Boy")) }
        val (vm, _) = openStoryDraft(draft = draft)
        assertTrue(vm.songwritingIssues(vm.state.value.draft!!).any { it.code == SpecIssueCode.ARTIST_VOICE_IMITATION })
        vm.generateLyrics()
        awaitSongwriting(vm)
        assertTrue(fake.submits.isEmpty())
        assertTrue(vm.state.value.songwriting.error!!.message.contains("imitate"))
    }

    @Test
    fun `a request interrupted by process death resumes and is applied once`() {
        val base = storyDraft()
        val structured = base.copy(lyrics = base.lyrics.copy(sections = SongSpecificationBuilder.build(base, testCapabilities).sections))
        val spec = SongSpecificationBuilder.build(structured, testCapabilities).specification!!
        fake.knowJob("job-41", spec)
        val interrupted = structured.copy(songwriting = MusicMeSongwritingState(pendingJob = MusicMePendingSongwritingJob("job-41", "key-41", null, 1L)))
        val (vm, id) = openStoryDraft(draft = interrupted)
        awaitSongwriting(vm)
        val after = stored(id)
        assertTrue(fake.submits.isEmpty())
        assertNull(after.songwriting!!.pendingJob)
        assertTrue(after.lyrics.sections.any { it.origin == SectionOrigin.AI })
        assertFalse(vm.state.value.songwriting.running)
    }

    /** Lets a request run to completion (or reach its gate): each DataStore write needs the main looper to turn. */
    private fun awaitSongwriting(vm: MusicMeViewModel, timeoutMs: Long = 10_000) {
        settle(200)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (vm.state.value.songwriting.running && fake.gate?.isCompleted != false && System.currentTimeMillis() < deadline) settle(100)
        settle(400)
    }

    private fun settle(millis: Long = 600) {
        val looper = shadowOf(Looper.getMainLooper())
        repeat((millis / 50).toInt().coerceAtLeast(1)) {
            looper.idleFor(Duration.ofMillis(50))
            Thread.sleep(15)
        }
        looper.idle()
    }
}
