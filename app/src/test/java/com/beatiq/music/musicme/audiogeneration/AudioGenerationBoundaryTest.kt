package com.beatiq.music.musicme.audiogeneration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.beatiq.music.BuildConfig
import com.beatiq.music.musicme.model.MusicMeConfiguration
import com.beatiq.music.musicme.model.MusicMeDraft
import com.beatiq.music.musicme.model.MusicMeGenerationStatus
import com.beatiq.music.musicme.model.MusicMeInput
import com.beatiq.music.musicme.model.MusicMeLyricsDraft
import com.beatiq.music.musicme.model.MusicMeSongSection
import com.beatiq.music.musicme.model.MusicMeVoiceRecording
import com.beatiq.music.musicme.model.OptionCategory
import com.beatiq.music.musicme.model.defaultSelections
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AudioGenerationBoundaryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val trackId = "0b6f5c1e-2d3a-4b5c-8d9e-0f1a2b3c4d5e"

    private fun draft(arrangement: String = "male_solo", language: String = "english") = MusicMeDraft(
        id = "draft-1",
        input = MusicMeInput(title = "Ten Brave Years", story = "Secret story about Ada", recipient = "Ada", desiredMessage = "Private message"),
        recording = MusicMeVoiceRecording(localFileName = "voice.m4a", durationMs = 3000),
        configuration = MusicMeConfiguration(
            selections = defaultSelections() + (OptionCategory.VOCAL_ARRANGEMENT to setOf(arrangement)) + (OptionCategory.LANGUAGE to setOf(language)),
        ),
        lyrics = MusicMeLyricsDraft(
            sections = listOf(
                MusicMeSongSection(id = "s1", type = "intro", label = "Intro", order = 0),
                MusicMeSongSection(id = "s2", type = "verse", label = "Verse 1", lyrics = "Ada laughed at my umbrella", order = 1),
                MusicMeSongSection(id = "s3", type = "Final Chorus", label = "Final Chorus", lyrics = "Ten brave years", order = 2),
                MusicMeSongSection(id = "s4", type = "instrumental_break", label = "Break", order = 3),
            ),
        ),
    )

    private val capabilities = AudioGenerationCapabilities(
        AUDIO_CONTRACT_VERSION, "mock", "mock-tone-generator", realAudio = false, languages = setOf("english"),
        vocalTypes = setOf("female", "male", "duet", "choir", "none"), maxSections = 16, minDurationSeconds = 30,
        maxDurationSeconds = 240, maxTakes = 2, songsPerDay = 3, usedStorageBytes = 0, storageQuotaBytes = 1,
        generationMode = "internal_test", generationAllowed = true,
    )

    // ---- fail-closed wiring ---------------------------------------------------------------------

    @Test
    fun `audio generation is off by default and configured only with a safe service origin`() = runBlocking<Unit> {
        assertEquals("", BuildConfig.MUSICME_AUDIO_URL)
        assertFalse(AudioGenerationDependencies.defaultService(context).isConfigured)
        for (url in listOf("", "   ", "not a url", "http://audio.example.test/", "ftp://audio.example.test/")) {
            assertFalse(url, AudioGenerationDependencies.serviceFor(url, context).isConfigured)
        }
        assertTrue(AudioGenerationDependencies.serviceFor("https://audio.example.test/", context).isConfigured)
        assertTrue(AudioGenerationDependencies.serviceFor("http://10.0.2.2:8090/", context).isConfigured)
        val error = assertThrows(AudioGenerationException::class.java) {
            runBlocking { UnconfiguredAudioGenerationService.submit(AudioGenerationRequestBuilder.build(draft(), null).request!!, "key-12345678") }
        }
        assertEquals(AudioGenerationErrorCode.NOT_CONFIGURED, error.code)
    }

    // ---- request building -----------------------------------------------------------------------

    @Test
    fun `requests carry lyrics and musical choices only, never the story or recordings`() {
        val result = AudioGenerationRequestBuilder.build(draft(), capabilities)
        assertTrue(result.issues.toString(), result.issues.isEmpty())
        val json = result.request!!.toJson()
        assertEquals(AUDIO_CONTRACT_VERSION, json.getString("contract_version"))
        val request = json.getJSONObject("request")
        assertEquals(
            setOf("draft_id", "title", "prompt", "language", "sections", "style", "vocals", "target_duration_seconds", "takes", "allow_explicit"),
            request.keys().asSequence().toSet(),
        )
        val text = json.toString()
        for (privateValue in listOf("voice.m4a", "recording")) {
            assertFalse(privateValue, text.contains(privateValue))
        }
        assertTrue(request.getString("prompt").contains("Secret story"))
        val sections = request.getJSONArray("sections")
        assertEquals(listOf("intro", "verse", "chorus", "instrumental"), List(sections.length()) { sections.getJSONObject(it).getString("type") })
        assertEquals("Ada laughed at my umbrella", sections.getJSONObject(1).getString("lyrics"))  // unchanged
        assertEquals("male", request.getJSONObject("vocals").getString("type"))
        assertEquals(150, request.getInt("target_duration_seconds"))
    }

    @Test
    fun `builder blocks what the service would refuse`() {
        assertEquals(listOf(AudioRequestIssue.VOCAL_TYPE_NEEDED), AudioGenerationRequestBuilder.build(draft("lead_and_backing_vocals"), capabilities).issues)
        assertEquals(listOf(AudioRequestIssue.UNSUPPORTED_LANGUAGE), AudioGenerationRequestBuilder.build(draft(language = "yoruba"), capabilities).issues)
        val wordy = draft().let { d ->
            d.copy(lyrics = d.lyrics.copy(sections = d.lyrics.sections.map { if (it.id == "s4") it.copy(lyrics = "words") else it }))
        }
        assertTrue(AudioRequestIssue.LYRICS_IN_INSTRUMENTAL_SECTION in AudioGenerationRequestBuilder.build(wordy, capabilities).issues)
        val silent = draft().let { d -> d.copy(input = MusicMeInput(), lyrics = d.lyrics.copy(sections = d.lyrics.sections.map { it.copy(lyrics = "") })) }
        assertEquals(listOf(AudioRequestIssue.NO_LYRICS), AudioGenerationRequestBuilder.build(silent, capabilities).issues)
        assertEquals("none", AudioGenerationRequestBuilder.vocalType(setOf("instrumental_only")))
        assertNull(AudioGenerationRequestBuilder.vocalType(setOf("male_solo", "female_solo")))
    }

    // ---- remote client ------------------------------------------------------------------------

    private fun remote(token: String? = "access-token", handler: (Request) -> Response): Pair<RemoteAudioGenerationService, MutableList<Request>> {
        val seen = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain -> seen += chain.request(); handler(chain.request()) }).build()
        return RemoteAudioGenerationService("https://audio.example.test/".toHttpUrl(), { token }, client) to seen
    }

    private fun json(request: Request, status: Int, body: JSONObject) =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("x")
            .body(body.toString().toResponseBody("application/json".toMediaType())).build()

    private fun trackJson(sha: String = "a".repeat(64), size: Long = 4) = JSONObject().put("id", trackId).put("take", 1).put("format", "wav")
        .put("content_type", "audio/wav").put("size_bytes", size).put("duration_ms", 10000).put("sha256", sha).put("seed", 7)
        .put("available", true).put("expires_at", 123L)

    @Test
    fun `requests are authenticated, idempotent and decode jobs with honest mock labels`() = runBlocking<Unit> {
        val (service, seen) = remote { request ->
            when {
                request.url.encodedPath.endsWith("/jobs") -> json(request, 202, JSONObject().put("id", "j1").put("state", "queued").put("stage", "queued").put("progress", 0).put("queue_position", 2))
                else -> json(
                    request, 200,
                    JSONObject().put("id", "j1").put("state", "succeeded").put("stage", "done").put("progress", 100).put("queue_position", JSONObject.NULL)
                        .put("result", JSONObject().put("tracks", JSONArray().put(trackJson()))
                            .put("generation", JSONObject().put("engine", "mock").put("model_id", "mock-tone-generator").put("mock", true).put("seed", 7))),
                )
            }
        }
        val queued = service.submit(AudioGenerationRequestBuilder.build(draft(), capabilities).request!!, "key-abc-12345")
        assertEquals(2, queued.queuePosition)
        assertEquals(MusicMeGenerationStatus.QUEUED, queued.displayStatus)
        val done = service.status("j1")
        assertEquals(MusicMeGenerationStatus.COMPLETED, done.displayStatus)
        assertTrue(done.generation!!.mock)
        assertFalse(done.generation!!.realAudio)  // the response omitted real_audio: treated as not real
        assertEquals(trackId, done.tracks.single().id)
        assertEquals("Bearer access-token", seen[0].header("Authorization"))
        assertEquals("key-abc-12345", seen[0].header("Idempotency-Key"))
        assertEquals("/v1/audio/jobs", seen[0].url.encodedPath)
        val sent = Buffer().also { seen[0].body!!.writeTo(it) }.readUtf8()
        assertTrue(sent.contains("Secret story"))
        assertEquals(MusicMeGenerationStatus.GENERATING, AudioJob("j", AudioJobState.RUNNING, "rendering", 10).displayStatus)
        assertEquals(MusicMeGenerationStatus.PROCESSING, AudioJob("j", AudioJobState.RUNNING, "validating", 90).displayStatus)
    }

    @Test
    fun `mock flags fail safe and capabilities default to not real`() = runBlocking<Unit> {
        val (service, _) = remote { request ->
            when {
                request.url.encodedPath.endsWith("/capabilities") -> json(
                    request, 200,
                    JSONObject().put("contract_version", AUDIO_CONTRACT_VERSION).put("engine", "mock").put("model_id", "m").put("languages", JSONArray().put("english")),
                )
                else -> json(
                    request, 200,
                    JSONObject().put("id", "j1").put("state", "succeeded").put("stage", "done").put("progress", 100)
                        .put("result", JSONObject().put("tracks", JSONArray()).put("generation", JSONObject().put("engine", "x").put("model_id", "m").put("real_audio", true))),
                )
            }
        }
        assertFalse(service.capabilities().realAudio)
        val info = service.status("j1").generation!!
        assertTrue(info.mock)  // "mock" missing → mock, even though the response claimed real_audio
        assertFalse(info.realAudio)
    }

    @Test
    fun `missing token fails before any network call`() {
        val (service, seen) = remote(token = null) { error("must not be called") }
        val error = assertThrows(AudioGenerationException::class.java) { runBlocking { service.status("j1") } }
        assertEquals(AudioGenerationErrorCode.UNAUTHORIZED, error.code)
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `service errors map to safe app errors`() {
        fun map(status: Int, code: String?, message: String = "Service says") =
            RemoteAudioGenerationService.errorFrom(status, code?.let { JSONObject().put("code", it).put("message", message) }, null)
        assertEquals(AudioGenerationErrorCode.AUDIO_QUALITY, map(200, "AUDIO_SILENT").code)
        assertEquals(AudioGenerationErrorCode.UNAVAILABLE, map(200, "WORKER_LOST").code)
        assertEquals(AudioGenerationErrorCode.UNAVAILABLE, map(200, "PROVIDER_UNAVAILABLE").code)
        assertEquals(AudioGenerationErrorCode.UNAVAILABLE, map(200, "BUDGET_EXCEEDED").code)
        assertEquals(AudioGenerationErrorCode.LINK_EXPIRED, map(410, "LINK_EXPIRED").code)
        assertEquals(AudioGenerationErrorCode.STORAGE_QUOTA_EXCEEDED, map(429, "STORAGE_QUOTA_EXCEEDED").code)
        assertEquals(AudioGenerationErrorCode.GENERATION_DISABLED, map(403, "GENERATION_DISABLED").code)
        assertEquals("Service says", map(422, "POLICY_REJECTED").userMessage)
        assertEquals(RemoteAudioGenerationService.defaultMessage(AudioGenerationErrorCode.UNKNOWN), map(500, "UNKNOWN", "stack trace with lyrics").userMessage)
        assertEquals(AudioGenerationErrorCode.UNAUTHORIZED, map(401, null).code)
    }

    @Test
    fun `history and metadata updates use authenticated owner-scoped endpoints`() = runBlocking<Unit> {
        val libraryTrack = JSONObject()
            .put("id", trackId).put("title", "Lagos Sunset").put("favorite", false).put("saved", true)
            .put("format", "wav").put("content_type", "audio/wav").put("size_bytes", 4)
            .put("duration_ms", 10000).put("sha256", "a".repeat(64)).put("created_at", 123L)
        val (service, seen) = remote { request ->
            if (request.method == "GET") {
                json(request, 200, JSONObject().put("tracks", JSONArray().put(libraryTrack)))
            } else {
                json(request, 200, JSONObject().put("track", JSONObject(libraryTrack.toString()).put("favorite", true)))
            }
        }
        assertEquals("Lagos Sunset", service.history().single().title)
        assertTrue(service.updateTrack(trackId, favorite = true).favorite)
        assertEquals(listOf("GET", "PATCH"), seen.map { it.method })
        assertEquals(listOf("/v1/audio/tracks", "/v1/audio/tracks/$trackId"), seen.map { it.url.encodedPath })
        assertEquals("Bearer access-token", seen[1].header("Authorization"))
        assertEquals(true, JSONObject(Buffer().also { seen[1].body!!.writeTo(it) }.readUtf8()).getBoolean("favorite"))
    }

    @Test
    fun `download links to other origins are refused`() {
        for (link in listOf(
            "https://evil.example.test/v1/audio/files/x",
            "http://audio.example.test/v1/audio/files/x",
            "https://audio.example.test:8443/v1/audio/files/x",
        )) {
            val (service, _) = remote { request ->
                json(request, 200, JSONObject().put("url", link).put("expires_at", 1).put("content_type", "audio/wav").put("size_bytes", 4))
            }
            val error = assertThrows(AudioGenerationException::class.java) { runBlocking { service.downloadLink(trackId) } }
            assertEquals(AudioGenerationErrorCode.UNAVAILABLE, error.code)
        }
        val (service, _) = remote { request ->
            json(request, 200, JSONObject().put("url", "https://audio.example.test/v1/audio/files/x").put("expires_at", 1).put("content_type", "audio/wav").put("size_bytes", 4))
        }
        runBlocking { assertEquals("https://audio.example.test/v1/audio/files/x", service.downloadLink(trackId).url) }
    }

    // ---- on-device files ----------------------------------------------------------------------

    private fun track(sha: String, size: Long) = AudioTrack(trackId, 1, "wav", "audio/wav", size, 10000, sha, 7, true, null)

    private fun files(contentType: String = "audio/wav", bytes: ByteArray): AudioTrackFiles {
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("ok")
                .body(bytes.toResponseBody(contentType.toMediaType())).build()
        }).build()
        return AudioTrackFiles(context, "account-a", client)
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `songs download into private per-account storage only after integrity checks`() = runBlocking<Unit> {
        val bytes = "RIFFdata".toByteArray()
        val link = AudioDownloadLink("https://audio.example.test/v1/audio/files/t", 1, "audio/wav", bytes.size.toLong())
        val good = track(sha(bytes), bytes.size.toLong())
        val saved = files(bytes = bytes).download(link, good)
        assertTrue(saved.path.contains("musicme_audio/") && saved.canonicalPath.startsWith(context.filesDir.canonicalPath))
        assertFalse(saved.path.contains("account-a"))
        assertTrue(files(bytes = bytes).isSaved(good))
        val out = ByteArrayOutputStream()
        files(bytes = bytes).exportTo(good, out)
        assertEquals("RIFFdata", out.toString())

        val tampered = track("0".repeat(64), bytes.size.toLong())
        files(bytes = bytes).delete(good)
        assertThrows(IOException::class.java) { runBlocking { files(bytes = bytes).download(link, tampered) } }
        assertFalse(files(bytes = bytes).isSaved(tampered))
        assertThrows(IOException::class.java) { runBlocking { files(contentType = "text/html", bytes = bytes).download(link, good) } }
        assertThrows(IOException::class.java) { files(bytes = bytes).fileFor(good.copy(id = "../../shared_prefs/beatiq_auth")) }
        assertThrows(IOException::class.java) { files(bytes = bytes).fileFor(good.copy(format = "exe")) }
    }
}
