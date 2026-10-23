package com.beatiq.music.musicme.audiogeneration

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.beatiq.music.core.auth.AccountStorageKey
import com.beatiq.music.core.auth.AuthPreferences
import com.beatiq.music.musicme.model.MusicMeConfiguration
import com.beatiq.music.musicme.model.MusicMeDraft
import com.beatiq.music.musicme.model.MusicMeInput
import com.beatiq.music.musicme.model.MusicMeLyricsDraft
import com.beatiq.music.musicme.model.MusicMeSongSection
import com.beatiq.music.musicme.model.OptionCategory
import com.beatiq.music.musicme.model.defaultSelections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID

/** DEV song creation: coordinator, on-device store and the song ViewModel, against a fake audio service. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SongCreationDevFlowTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private var previousUserId: String? = null
    private val wav = ByteArray(4096) { (it % 251).toByte() }.also { "RIFF".toByteArray().copyInto(it) }
    private val sha = MessageDigest.getInstance("SHA-256").digest(wav).joinToString("") { "%02x".format(it) }
    private val trackId = "0b6f5c1e-2d3a-4b5c-8d9e-0f1a2b3c4d5e"

    @Before
    fun setUp() {
        previousUserId = AuthPreferences(app).userId
        AudioGenerationDependencies.sleep = { }
        AudioGenerationDependencies.filesFactory = { context, account -> AudioTrackFiles(context, account, wavClient()) }
    }

    @After
    fun tearDown() {
        AuthPreferences(app).userId = previousUserId
        AudioGenerationDependencies.reset()
    }

    private fun devAccount() = "dev-" + UUID.randomUUID().toString().replace("-", "").take(16)

    private fun wavClient() = OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(wav.toResponseBody("audio/wav".toMediaType())).build()
    }.build()

    private fun capabilities(real: Boolean = true) = AudioGenerationCapabilities(
        "musicme.audio.v1", if (real) "remote_gpu" else "mock", "ace-step-1.5", real, setOf("english"), setOf("male", "female", "none"),
        16, 30, 240, 1, 10, 0, 1_000_000, "internal_test", true,
    )

    private fun track() = AudioTrack(trackId, 1, "wav", "audio/wav", wav.size.toLong(), 30_000, sha, 7, true, null)

    private fun job(state: AudioJobState, stage: String = "rendering", mock: Boolean = false, real: Boolean = true, error: AudioGenerationException? = null) =
        AudioJob("job-1", state, stage, 50, tracks = if (state == AudioJobState.SUCCEEDED) listOf(track()) else emptyList(),
            generation = if (state == AudioJobState.SUCCEEDED) AudioGenerationInfo("remote_gpu", "ace-step-1.5", "v0.1.8", mock, real, 7) else null, error = error)

    private inner class FakeService(
        var caps: AudioGenerationCapabilities = capabilities(),
        val statuses: ArrayDeque<Any> = ArrayDeque(listOf(job(AudioJobState.RUNNING), job(AudioJobState.SUCCEEDED))),
    ) : AudioGenerationService {
        override val isConfigured = true
        val submitted = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        var capabilityCalls = 0
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun capabilities(): AudioGenerationCapabilities { capabilityCalls++; return caps }
        override suspend fun submit(request: AudioGenerationRequest, idempotencyKey: String): AudioJob { submitted += idempotencyKey; return job(AudioJobState.QUEUED, "queued") }
        override suspend fun status(jobId: String): AudioJob {
            gate?.await()
            return when (val next = statuses.removeFirst()) { is AudioGenerationException -> throw next; else -> next as AudioJob }
        }
        override suspend fun cancel(jobId: String) { cancelled += jobId }
        override suspend fun downloadLink(trackId: String) = AudioDownloadLink("http://127.0.0.1:8088/v1/audio/files/x", 0, "audio/wav", wav.size.toLong())
        override suspend fun deleteTrack(trackId: String) { deleted += trackId }
        override suspend fun history(): List<AudioLibraryTrack> = emptyList()
        override suspend fun updateTrack(trackId: String, title: String?, favorite: Boolean?, saved: Boolean?) =
            AudioLibraryTrack(trackId, title ?: "Song", favorite ?: false, saved ?: false, "wav", "audio/wav",
                wav.size.toLong(), 30_000, sha, 1)
    }

    private fun draft() = MusicMeDraft(
        id = "draft-song",
        input = MusicMeInput(title = "Ten Brave Years", story = "Private story", recipient = "Ada"),
        configuration = MusicMeConfiguration(selections = defaultSelections() + (OptionCategory.VOCAL_ARRANGEMENT to setOf("female_solo")) + (OptionCategory.LANGUAGE to setOf("english"))),
        lyrics = MusicMeLyricsDraft(sections = listOf(MusicMeSongSection(id = "v1", type = "verse", label = "Verse", lyrics = "Ten brave years", order = 0))),
    )

    private fun request() = AudioGenerationRequestBuilder.build(draft(), capabilities()).request!!

    // ---- coordinator -------------------------------------------------------------------------------------------

    @Test
    fun coordinatorStoresTheKeyBeforeSendingThenTheJobIdAndClearsWhenDone() = runBlocking<Unit> {
        val service = FakeService()
        val persisted = mutableListOf<PendingAudioJob?>()
        val progress = mutableListOf<AudioJobState>()
        val done = AudioGenerationCoordinator(service, sleep = {}).execute(request(), null, { persisted += it }, { progress += it.state })
        assertEquals(AudioJobState.SUCCEEDED, done.state)
        assertEquals(3, persisted.size)
        assertNull(persisted[0]!!.jobId)
        assertEquals(persisted[0]!!.idempotencyKey, service.submitted.single())
        assertEquals("job-1", persisted[1]!!.jobId)
        assertNull(persisted[2])
        assertEquals(listOf(AudioJobState.QUEUED, AudioJobState.RUNNING, AudioJobState.SUCCEEDED), progress)
    }

    @Test
    fun mockOrUnlabelledAudioIsNeverAcceptedAsASong() = runBlocking<Unit> {
        for (finished in listOf(job(AudioJobState.SUCCEEDED, mock = true), job(AudioJobState.SUCCEEDED, real = false), job(AudioJobState.SUCCEEDED).copy(generation = null))) {
            val service = FakeService(statuses = ArrayDeque(listOf(finished)))
            val error = assertThrows(AudioGenerationException::class.java) {
                runBlocking { AudioGenerationCoordinator(service, sleep = {}).execute(request(), null, {}, {}) }
            }
            assertEquals(AudioGenerationErrorCode.NOT_CONFIGURED, error.code)
        }
    }

    @Test
    fun resumingWithAJobIdPollsWithoutResubmittingAndTransientErrorsAreRetried() = runBlocking<Unit> {
        val offline = AudioGenerationException(AudioGenerationErrorCode.OFFLINE, "offline")
        val service = FakeService(statuses = ArrayDeque(listOf(offline, job(AudioJobState.SUCCEEDED))))
        val done = AudioGenerationCoordinator(service, sleep = {}).execute(request(), PendingAudioJob("draft-song", "song-k", "job-1"), {}, {})
        assertEquals(AudioJobState.SUCCEEDED, done.state)
        assertTrue(service.submitted.isEmpty())
    }

    @Test
    fun failedJobsSurfaceTheirErrorAndClearThePendingRecord() = runBlocking<Unit> {
        val policy = AudioGenerationException(AudioGenerationErrorCode.POLICY_REJECTED, "These lyrics can't be used.")
        val service = FakeService(statuses = ArrayDeque(listOf(job(AudioJobState.FAILED, "failed", error = policy))))
        var last: PendingAudioJob? = PendingAudioJob("x", "y")
        val error = assertThrows(AudioGenerationException::class.java) {
            runBlocking { AudioGenerationCoordinator(service, sleep = {}).execute(request(), null, { last = it }, {}) }
        }
        assertEquals(AudioGenerationErrorCode.POLICY_REJECTED, error.code)
        assertNull(last)
    }

    @Test
    fun cancellingTellsTheServiceToStopAndForgetsTheJob() = runBlocking<Unit> {
        val service = FakeService().apply { gate = CompletableDeferred() }
        var last: PendingAudioJob? = null
        val running = async { AudioGenerationCoordinator(service, sleep = {}).execute(request(), null, { last = it }, {}) }
        repeat(20) { yield() }
        assertEquals("job-1", last?.jobId)
        running.cancel()
        assertThrows(CancellationException::class.java) { runBlocking { running.await() } }
        assertEquals(listOf("job-1"), service.cancelled)
        assertNull(last)
    }

    // ---- store ---------------------------------------------------------------------------------------------------

    @Test
    fun creationsArePerAccountInTheBackupExcludedFolderAndACorruptIndexIsNeverOverwritten() = runBlocking<Unit> {
        val a = devAccount()
        val b = devAccount()
        val storeA = MusicMeCreationStore(app, a)
        val creation = MusicMeCreation(track(), AudioGenerationInfo("remote_gpu", "ace-step-1.5", "v0.1.8", false, true, 7), "d", "Song", 1, saved = false)
        storeA.add(creation)
        storeA.setConsent(true)
        assertEquals(listOf(trackId), storeA.creations().map { it.id })
        assertTrue(MusicMeCreationStore(app, b).creations().isEmpty())
        assertFalse(MusicMeCreationStore(app, b).hasConsent())
        val index = File(app.filesDir, "musicme_audio/${AccountStorageKey.fromUserId(a)}/creations.json")
        assertTrue(index.isFile)
        index.writeText("{broken")
        assertThrows(java.io.IOException::class.java) { runBlocking { storeA.add(creation) } }
        assertEquals("{broken", index.readText())
    }

    // ---- ViewModel gating ------------------------------------------------------------------------------------------

    @Test
    fun songCreationIsOffForUnconfiguredUnauthorisedAccountsAndMockEngines() {
        AuthPreferences(app).userId = devAccount()
        AudioGenerationDependencies.serviceFactory = { UnconfiguredAudioGenerationService }
        assertEquals(SongCreationAvailability.NOT_IN_BUILD, availability())

        val service = FakeService()
        AudioGenerationDependencies.serviceFactory = { service }
        service.caps = capabilities().copy(generationAllowed = false)
        assertEquals(SongCreationAvailability.NOT_AUTHORISED, availability())
        assertEquals(1, service.capabilityCalls)

        service.caps = capabilities(real = false)
        assertEquals(SongCreationAvailability.NOT_REAL_ENGINE, availability())

        service.caps = capabilities()
        assertEquals(SongCreationAvailability.AVAILABLE, availability())
        assertTrue(AudioGenerationDependencies.serviceFor("http://127.0.0.1:8088/", app).isConfigured)
    }

    private fun availability(): SongCreationAvailability = MusicMeSongViewModel(app).also { settle() }.state.value.availability

    // ---- full DEV flow -----------------------------------------------------------------------------------------------

    @Test
    fun consentCreateListenKeepAndDeleteWithoutTouchingDraftsOrTheLibrary() {
        val account = devAccount()
        AuthPreferences(app).userId = account
        val service = FakeService()
        AudioGenerationDependencies.serviceFactory = { service }
        val databasesBefore = app.databaseList().toSet()

        val vm = MusicMeSongViewModel(app)
        settle()
        assertEquals(SongCreationAvailability.AVAILABLE, vm.state.value.availability)
        vm.createSong(draft())
        settle()
        assertEquals("draft-song", vm.state.value.consentDraftId)
        assertTrue(service.submitted.isEmpty())

        vm.grantConsent(draft())
        settle(1_000)
        val unsaved = vm.state.value.unsavedFor("draft-song")
        assertNotNull(unsaved)
        assertTrue(unsaved!!.generation.realAudio && !unsaved.generation.mock)
        val file = vm.fileFor(unsaved)!!
        assertTrue(file.readBytes().contentEquals(wav))
        assertTrue(file.canonicalPath.contains("/musicme_audio/${AccountStorageKey.fromUserId(account)}/"))
        assertTrue(vm.state.value.saved.isEmpty())

        vm.keep(unsaved)
        settle()
        assertEquals(listOf(trackId), vm.state.value.saved.map { it.id })

        // Kept songs survive a new ViewModel (they are read back from the store).
        val again = MusicMeSongViewModel(app)
        settle()
        assertEquals(listOf(trackId), again.state.value.saved.map { it.id })

        vm.delete(unsaved)
        settle()
        assertFalse(file.exists())
        assertEquals(listOf(trackId), service.deleted)
        assertTrue(vm.state.value.creations.isEmpty())
        assertEquals(databasesBefore, app.databaseList().toSet())
    }

    @Test
    fun anInterruptedSongResumesWithoutBeingSentAgain() = runBlocking<Unit> {
        val account = devAccount()
        AuthPreferences(app).userId = account
        MusicMeCreationStore(app, account).apply { setConsent(true); setPending(PendingAudioJob("draft-song", "song-key", "job-1")) }
        val service = FakeService()
        AudioGenerationDependencies.serviceFactory = { service }
        val vm = MusicMeSongViewModel(app)
        settle(1_000)
        assertNotNull(vm.state.value.unsavedFor("draft-song"))
        assertTrue(service.submitted.isEmpty())
        assertNull(MusicMeCreationStore(app, account).pending())
    }

    private fun settle(millis: Long = 500) {
        val looper = shadowOf(Looper.getMainLooper())
        repeat((millis / 50).toInt().coerceAtLeast(1)) {
            looper.idleFor(Duration.ofMillis(50))
            Thread.sleep(15)
        }
        looper.idle()
    }
}
