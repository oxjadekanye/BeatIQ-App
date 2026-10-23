package com.beatiq.music.musicme.songwriting

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.beatiq.music.musicme.data.MusicMeAccountBackup
import com.beatiq.music.musicme.data.MusicMeRepository
import com.beatiq.music.musicme.model.MusicMePendingSongwritingJob
import com.beatiq.music.musicme.model.MusicMeSongwritingState
import com.beatiq.music.musicme.model.SectionOrigin
import kotlinx.coroutines.flow.first
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
import java.net.UnknownHostException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SongwritingRemoteAndStorageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun service(token: String? = "access-token", handler: (Request) -> Response): Pair<RemoteSongwritingService, MutableList<Request>> {
        val seen = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain -> seen += chain.request(); handler(chain.request()) }).build()
        return RemoteSongwritingService("https://songwriter.example.test/".toHttpUrl(), { token }, client) to seen
    }

    private fun json(request: Request, status: Int, body: JSONObject, headers: Map<String, String> = emptyMap()) =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("x")
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .body(body.toString().toResponseBody("application/json".toMediaType())).build()

    @Test
    fun `requests are account-scoped, idempotent and versioned, and plans decode`() = runBlocking {
        val spec = SongSpecificationBuilder.build(storyDraft(), testCapabilities).specification!!
        val (remote, seen) = service { request ->
            when {
                request.url.encodedPath.endsWith("/jobs") -> json(request, 202, JSONObject().put("id", "j1").put("state", "queued"))
                else -> json(request, 200, JSONObject().put("id", "j1").put("state", "succeeded").put("plan", planJson(groundedPlan(spec))))
            }
        }
        val submitted = remote.submit(spec, "key-abc-123")
        assertEquals(JobState.QUEUED, submitted.state)
        val post = seen.first()
        assertEquals("/v1/songwriting/jobs", post.url.encodedPath)
        assertEquals("Bearer access-token", post.header("Authorization"))
        assertEquals("key-abc-123", post.header("Idempotency-Key"))
        val sent = JSONObject(Buffer().also { post.body!!.writeTo(it) }.readUtf8())
        assertEquals(SONGWRITING_CONTRACT_VERSION, sent.getString("contract_version"))
        assertEquals("Ada", sent.getJSONObject("specification").getString("recipient"))
        assertEquals(spec.slots.size, sent.getJSONObject("specification").getJSONArray("structure").length())

        val done = remote.status("j1")
        assertEquals(JobState.SUCCEEDED, done.state)
        assertTrue(SongwritingPlanValidator.validate(done.plan!!, spec).valid)
        assertEquals("/v1/songwriting/jobs/j1", seen.last().url.encodedPath)
    }

    @Test
    fun `HTTP and network failures map to specific, user-safe errors`() {
        val spec = SongSpecificationBuilder.build(storyDraft(), testCapabilities).specification!!
        fun failure(handler: (Request) -> Response) = assertThrows(SongwritingException::class.java) {
            runBlocking { service(handler = handler).first.submit(spec, "key-1") }
        }
        failure { json(it, 429, JSONObject(), mapOf("Retry-After" to "42")) }.also {
            assertEquals(SongwritingErrorCode.RATE_LIMITED, it.code); assertEquals(42L, it.retryAfterSeconds)
        }
        assertEquals(SongwritingErrorCode.UNAUTHORIZED, failure { json(it, 401, JSONObject()) }.code)
        assertEquals(SongwritingErrorCode.UNAVAILABLE, failure { json(it, 503, JSONObject()) }.code)
        failure { json(it, 422, JSONObject().put("error", JSONObject().put("code", "POLICY_REJECTED").put("message", "MusicMe can't imitate a specific artist's voice."))) }.also {
            assertEquals(SongwritingErrorCode.POLICY_REJECTED, it.code); assertTrue(it.userMessage.contains("imitate"))
        }
        assertEquals(SongwritingErrorCode.OFFLINE, failure { throw UnknownHostException("no network") }.code)
        assertEquals(
            SongwritingErrorCode.MODEL_OUTPUT_INVALID,
            failure { json(it, 200, JSONObject().put("id", "j").put("state", "succeeded").put("plan", JSONObject().put("title", "x"))) }.code,
        )
        // Without a session nothing is sent at all.
        val (noToken, seen) = service(token = null) { error("must not be called") }
        assertEquals(SongwritingErrorCode.UNAUTHORIZED, assertThrows(SongwritingException::class.java) { runBlocking { noToken.capabilities() } }.code)
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `only HTTPS or loopback development URLs enable the service`() {
        assertNull(RemoteSongwritingService.parseBaseUrl(""))
        assertNull(RemoteSongwritingService.parseBaseUrl("http://songwriter.example.com"))
        assertNull(RemoteSongwritingService.parseBaseUrl("not a url"))
        assertEquals("songwriter.example.com", RemoteSongwritingService.parseBaseUrl("https://songwriter.example.com")!!.host)
        assertEquals("10.0.2.2", RemoteSongwritingService.parseBaseUrl("http://10.0.2.2:8088")!!.host)
        assertFalse(SongwritingDependencies.defaultService(context).isConfigured)
    }

    @Test
    fun `songwriting fields persist, legacy drafts still load, backups keep everything, accounts stay separate`() = runBlocking {
        val account = "sw-${UUID.randomUUID()}"
        val repository = MusicMeRepository(context, account)
        val base = storyDraft()
        val spec = SongSpecificationBuilder.build(base, testCapabilities).specification!!
        val prepared = base.copy(lyrics = base.lyrics.copy(sections = SongSpecificationBuilder.build(base, testCapabilities).sections))
        val merged = SongwritingMerger.apply(prepared, groundedPlan(spec), spec, prepared.lyrics.sections)
            .let { d -> d.copy(lyrics = d.lyrics.copy(sections = d.lyrics.sections.mapIndexed { i, s -> if (i == 1) s.copy(lyrics = "edited", origin = SectionOrigin.AI_EDITED) else s })) }
            .let { d -> d.copy(songwriting = d.songwriting!!.copy(pendingJob = MusicMePendingSongwritingJob("job-5", "key-5", null, 9L))) }
        repository.upsertDraft(merged)
        val loaded = repository.drafts.first().single()
        assertEquals(merged.lyrics, loaded.lyrics)
        assertEquals(merged.songwriting, loaded.songwriting)

        // A pre-Phase-2.1 draft (no origin/performanceNotes/songwriting) still decodes.
        val legacy = JSONArray().put(
            JSONObject().put("schema", 1).put("id", "legacy-1").put("name", "Old").put("createdAt", 1L).put("modifiedAt", 2L)
                .put("status", "DRAFT").put("input", JSONObject().put("story", "old story"))
                .put("configuration", JSONObject().put("genres", JSONObject().put("primary", "soul")))
                .put("lyrics", JSONObject().put("title", "").put("sections", JSONArray().put(JSONObject().put("id", "s1").put("type", "verse").put("label", "Verse").put("lyrics", "old words").put("order", 0))))
                .put("recording", JSONObject.NULL),
        ).toString()
        repository.mergeAccountBackup(MusicMeAccountBackup(legacy, "[]", emptyList()))
        val old = repository.drafts.first().single { it.id == "legacy-1" }
        assertEquals(SectionOrigin.USER, old.lyrics.sections.single().origin)
        assertNull(old.songwriting)

        // Export → delete → import restores the songwriting state exactly (merge-only).
        val backup = repository.exportAccountBackup()
        repository.deleteDraft(merged.id)
        repository.mergeAccountBackup(backup)
        assertEquals(merged.songwriting, repository.drafts.first().single { it.id == merged.id }.songwriting)

        val other = MusicMeRepository(context, "sw-other-${UUID.randomUUID()}")
        assertTrue(other.drafts.first().isEmpty())
        repository.setSongwritingConsent(true)
        assertTrue(repository.hasSongwritingConsent())
        assertFalse(other.hasSongwritingConsent())
        assertEquals(MusicMeSongwritingState(lastPlan = merged.songwriting!!.lastPlan, pendingJob = merged.songwriting!!.pendingJob), loaded.songwriting)
    }

    private fun planJson(plan: SongwritingPlan): JSONObject = JSONObject()
        .put("contract_version", plan.contractVersion).put("title", plan.title).put("summary", plan.summary).put("language", plan.language)
        .put("sections", JSONArray().apply {
            plan.sections.forEach { put(JSONObject().put("id", it.slotId).put("type", it.type.wire).put("label", it.label).put("lyrics", it.lyrics).put("performance_notes", it.performanceNotes)) }
        })
        .put("section_order", JSONArray(plan.sectionOrder))
        .put("style", JSONObject().put("genres", JSONArray(plan.style.genres)).put("era", plan.style.era).put("arrangement", plan.style.arrangement)
            .put("instrumentation", plan.style.instrumentation).put("production", plan.style.production))
        .put("tempo_bpm", plan.tempoBpm).put("estimated_duration_seconds", plan.estimatedDurationSeconds)
        .put("vocal_instructions", plan.vocalInstructions).put("pronunciation_notes", plan.pronunciationNotes)
        .put("metadata", JSONObject().put("model_id", plan.metadata.modelId).put("model_version", plan.metadata.modelVersion)
            .put("engine", plan.metadata.engine).put("request_id", plan.metadata.requestId).put("generated_at", plan.metadata.generatedAt)
            .put("prompt_version", plan.metadata.promptVersion))
}
