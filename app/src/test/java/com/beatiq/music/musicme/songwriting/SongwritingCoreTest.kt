package com.beatiq.music.musicme.songwriting

import com.beatiq.music.musicme.model.MusicMeDraft
import com.beatiq.music.musicme.model.MusicMePendingSongwritingJob
import com.beatiq.music.musicme.model.MusicMeSongSection
import com.beatiq.music.musicme.model.OptionCategory
import com.beatiq.music.musicme.model.SectionOrigin
import com.beatiq.music.musicme.model.defaultSongSections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SongwritingCoreTest {
    // ---- Story to specification ------------------------------------------------------------

    @Test
    fun `specification carries the user's own story, dedication, occasion and music choices`() {
        val draft = storyDraft(languages = linkedSetOf("french", "english"))
        val result = SongSpecificationBuilder.build(draft, testCapabilities)
        val spec = result.specification!!
        assertTrue(result.canGenerate)
        assertEquals(draft.input.story, spec.story)
        assertEquals("Thank you for ten brave years", spec.desiredMessage)
        assertEquals("Ada", spec.recipient)
        assertEquals("Anniversary", spec.occasion)
        assertEquals("Ada: AH-dah", spec.pronunciationNotes)
        assertEquals("french", spec.language)
        assertEquals(listOf("english"), spec.additionalLanguages)
        assertEquals(listOf("Contemporary R&B", "Soul"), spec.genres)
        assertEquals(84, spec.bpm)
        assertEquals(150, spec.targetDurationSeconds)
        assertTrue(spec.vocalCharacter.containsAll(listOf("Warm", "Soulful")))
        // Untouched default lyric sections give way to the chosen arrangement.
        assertEquals(draft.configuration.sections, spec.slots.map { it.label })
        assertEquals(
            listOf(SectionType.INTRO, SectionType.VERSE, SectionType.CHORUS, SectionType.VERSE, SectionType.BRIDGE, SectionType.CHORUS, SectionType.OUTRO),
            spec.slots.map { it.type },
        )
    }

    @Test
    fun `structure ids are stable once applied and user-shaped structures are respected`() {
        val draft = storyDraft()
        val first = SongSpecificationBuilder.build(draft, testCapabilities)
        val applied = draft.copy(lyrics = draft.lyrics.copy(sections = first.sections))
        val second = SongSpecificationBuilder.build(applied, testCapabilities)
        assertEquals(first.specification!!.slots.map { it.id }, second.specification!!.slots.map { it.id })

        val custom = draft.copy(lyrics = draft.lyrics.copy(sections = listOf(MusicMeSongSection(type = "verse", label = "Only verse", order = 0))))
        assertEquals(listOf("Only verse"), SongSpecificationBuilder.build(custom, testCapabilities).specification!!.slots.map { it.label })
    }

    @Test
    fun `user lyrics and edited sections are locked context, generated ones are rewritable`() {
        val sections = defaultSongSections().mapIndexed { i, s ->
            when (i) {
                0 -> s.copy(lyrics = "My own first verse", origin = SectionOrigin.USER)
                1 -> s.copy(lyrics = "Generated chorus", origin = SectionOrigin.AI)
                2 -> s.copy(lyrics = "Generated then edited", origin = SectionOrigin.AI_EDITED)
                else -> s
            }
        }
        val draft = storyDraft().let { it.copy(lyrics = it.lyrics.copy(sections = sections)) }
        val spec = SongSpecificationBuilder.build(draft, testCapabilities).specification!!
        assertEquals("My own first verse", spec.slots[0].lockedLyrics)
        assertNull(spec.slots[1].lockedLyrics)
        assertEquals("Generated then edited", spec.slots[2].lockedLyrics)
        assertEquals(listOf(sections[1].id, sections[3].id, sections[4].id), spec.writableSlots.map { it.id })
        assertEquals(listOf(sections[2].id), SongSpecificationBuilder.build(draft, testCapabilities, sections[2].id).specification!!.writableSlots.map { it.id })
    }

    @Test
    fun `missing, contradictory and unsupported inputs produce clear issues`() {
        fun codes(draft: MusicMeDraft, caps: SongwritingCapabilities? = testCapabilities, section: String? = null) =
            SongSpecificationBuilder.build(draft, caps, section).issues.map { it.code to it.severity }

        val base = storyDraft()
        assertTrue(SpecIssueCode.MISSING_CONTENT to Severity.BLOCKING in codes(base.copy(input = base.input.copy(story = " ", desiredMessage = ""))))
        val voiceOnly = base.copy(input = base.input.copy(story = "", desiredMessage = ""), recording = com.beatiq.music.musicme.model.MusicMeVoiceRecording(localFileName = "a", durationMs = 1))
        assertTrue(SpecIssueCode.VOICE_NOTE_NOT_TRANSCRIBED to Severity.BLOCKING in codes(voiceOnly))
        val instrumental = base.copy(configuration = base.configuration.copy(selections = base.configuration.selections + (OptionCategory.VOCAL_ARRANGEMENT to setOf("instrumental_only"))))
        assertTrue(SpecIssueCode.INSTRUMENTAL_ONLY to Severity.BLOCKING in codes(instrumental))
        assertTrue(SpecIssueCode.UNSUPPORTED_LANGUAGE to Severity.BLOCKING in codes(storyDraft(languages = setOf("yoruba"))))
        assertFalse(codes(storyDraft(languages = setOf("yoruba")), caps = null).any { it.first == SpecIssueCode.UNSUPPORTED_LANGUAGE })
        val memorialParty = base.copy(
            input = base.input.copy(occasionId = "memorial"),
            configuration = base.configuration.copy(selections = base.configuration.selections + (OptionCategory.MOOD to setOf("joyful"))),
        )
        assertTrue(SpecIssueCode.MOOD_OCCASION_CONFLICT to Severity.WARNING in codes(memorialParty))
        assertTrue(SpecIssueCode.TOO_MANY_SECTIONS to Severity.BLOCKING in codes(base, testCapabilities.copy(maxSections = 3)))
        val short = base.copy(configuration = base.configuration.copy(controls = base.configuration.controls.copy(durationSeconds = 30)))
        assertTrue(SpecIssueCode.SHORT_DURATION_FOR_STRUCTURE to Severity.WARNING in codes(short))
        val allMine = base.copy(lyrics = base.lyrics.copy(sections = defaultSongSections().map { it.copy(lyrics = "mine ${it.label}") }))
        assertTrue(SpecIssueCode.NOTHING_TO_WRITE to Severity.BLOCKING in codes(allMine))
        assertTrue(SpecIssueCode.INVALID_SECTION to Severity.BLOCKING in codes(base, section = "not-a-section"))
    }

    @Test
    fun `requests to copy lyrics or imitate an artist's voice are blocked with an explanation`() {
        val base = storyDraft()
        listOf(
            base.input.copy(story = "Use the exact lyrics from my favourite hit but add Ada"),
            base.input.copy(desiredMessage = "Make it sound like Burna Boy"),
            base.input.copy(story = "Write it in the voice of Whitney Houston for Ada"),
        ).forEach { input ->
            val issues = SongSpecificationBuilder.build(base.copy(input = input), testCapabilities).blocking
            assertTrue(issues.any { it.code == SpecIssueCode.COPYRIGHTED_LYRICS || it.code == SpecIssueCode.ARTIST_VOICE_IMITATION })
            assertTrue(issues.all { it.message.isNotBlank() })
        }
        val fine = base.input.copy(story = "${base.input.story} She sings like an angel and loves 90s R&B.")
        assertTrue(SongSpecificationBuilder.build(base.copy(input = fine), testCapabilities).canGenerate)
    }

    // ---- Structured output validation ------------------------------------------------------

    private fun spec(draft: MusicMeDraft = storyDraft(), section: String? = null) =
        SongSpecificationBuilder.build(draft, testCapabilities, section).specification!!

    @Test
    fun `a grounded plan in the right language and structure is accepted`() {
        val spec = spec()
        val result = SongwritingPlanValidator.validate(groundedPlan(spec), spec)
        assertTrue(result.errors.toString(), result.valid)
    }

    @Test
    fun `malformed, generic, repetitive or mismatched plans are rejected`() {
        val spec = spec()
        val plan = groundedPlan(spec)
        fun codes(p: SongwritingPlan) = SongwritingPlanValidator.validate(p, spec).errors.map { it.code }

        assertTrue(PlanIssueCode.CONTRACT_MISMATCH in codes(plan.copy(contractVersion = "v0")))
        assertTrue(PlanIssueCode.LANGUAGE_MISMATCH in codes(plan.copy(language = "spanish")))
        assertTrue(PlanIssueCode.MISSING_SECTION in codes(plan.copy(sections = plan.sections.drop(1))))
        assertTrue(PlanIssueCode.STRUCTURE_MISMATCH in codes(plan.copy(sectionOrder = plan.sectionOrder.reversed())))
        assertTrue(PlanIssueCode.UNEXPECTED_SECTION in codes(plan.copy(sections = plan.sections + plan.sections.first().copy(slotId = "made-up"))))
        assertTrue(PlanIssueCode.STRUCTURE_MISMATCH in codes(plan.copy(sections = plan.sections.map { if (it.type == SectionType.INTRO) it.copy(type = SectionType.BRIDGE) else it })))
        assertTrue(PlanIssueCode.EMPTY_LYRICS in codes(plan.copy(sections = plan.sections.map { it.copy(lyrics = " ") })))
        assertTrue(PlanIssueCode.REPETITIVE in codes(plan.copy(sections = plan.sections.map { if (it.type == SectionType.VERSE) it.copy(lyrics = "yeah yeah yeah yeah yeah yeah\nAda harbour") else it })))
        val loop = "Same line\nSame line\nSame line\nAnother line\nSame line"
        assertTrue(PlanIssueCode.REPETITIVE in codes(plan.copy(sections = plan.sections.map { if (it.type == SectionType.BRIDGE) it.copy(lyrics = loop) else it })))
        assertTrue(PlanIssueCode.REPETITIVE in codes(plan.copy(sections = plan.sections.map { if (it.type == SectionType.VERSE) it.copy(lyrics = "Ada at the harbour\nUmbrella in the rain") else it })))
        val generic = plan.copy(title = "Love", summary = "A love song", sections = plan.sections.map { it.copy(lyrics = "Baby you're my everything\nHold me tonight ${it.slotId.take(4)}") })
        assertTrue(PlanIssueCode.GENERIC_CONTENT in codes(generic))
        val unnamed = plan.copy(sections = plan.sections.map { it.copy(lyrics = it.lyrics.replace("Ada", "you")) })
        assertTrue(PlanIssueCode.DEDICATION_NOT_NAMED in codes(unnamed))
        assertTrue(PlanIssueCode.TEMPO_OUT_OF_RANGE in codes(plan.copy(tempoBpm = 400)))
        assertTrue(PlanIssueCode.METADATA_MISSING in codes(plan.copy(metadata = plan.metadata.copy(engine = "openai_api"))))
        assertTrue(PlanIssueCode.TOO_LONG in codes(plan.copy(sections = plan.sections.map { it.copy(lyrics = "word ".repeat(600)) })))
    }

    @Test
    fun `intros and outros may be instrumental but other sections need words`() {
        val spec = spec()
        val plan = groundedPlan(spec)
        val instrumentalEnds = plan.copy(sections = plan.sections.map {
            if (it.type == SectionType.INTRO || it.type == SectionType.OUTRO) it.copy(lyrics = "", performanceNotes = "Instrumental piano") else it
        })
        assertTrue(SongwritingPlanValidator.validate(instrumentalEnds, spec).errors.toString(), SongwritingPlanValidator.validate(instrumentalEnds, spec).valid)
        val emptyVerse = plan.copy(sections = plan.sections.map { if (it.type == SectionType.VERSE) it.copy(lyrics = "") else it })
        assertTrue(PlanIssueCode.EMPTY_LYRICS in SongwritingPlanValidator.validate(emptyVerse, spec).errors.map { it.code })
    }

    @Test
    fun `dedicatee naming applies only when a dedicatee is supplied, and optional sections match the service`() {
        listOf("", "   ", "A").forEach { recipient ->
            val draft = storyDraft(recipient = recipient)
            val spec = SongSpecificationBuilder.build(draft, testCapabilities).specification!!
            val plan = groundedPlan(spec)
            val codes = SongwritingPlanValidator.validate(plan, spec).errors.map { it.code }
            assertFalse("recipient='$recipient' must not require a name", PlanIssueCode.DEDICATION_NOT_NAMED in codes)
        }
        // Mirrors OPTIONAL_LYRICS in services/musicme-songwriter/songwriter/contract.py.
        assertEquals(setOf(SectionType.INTRO, SectionType.OUTRO), SectionType.entries.filter { it.lyricsOptional }.toSet())
        assertEquals(SectionType.OUTRO, SectionType.fromLabel("Fade-Out"))
    }

    @Test
    fun `genre and language choices survive into the plan and locked lyrics are protected`() {
        val draft = storyDraft(languages = setOf("french"))
        val built = SongSpecificationBuilder.build(draft, testCapabilities)
        val prepared = draft.copy(lyrics = draft.lyrics.copy(sections = built.sections.mapIndexed { i, s -> if (i == 1) s.copy(lyrics = "Mon couplet à moi") else s }))
        val spec = SongSpecificationBuilder.build(prepared, testCapabilities).specification!!
        val plan = groundedPlan(spec)
        assertEquals("french", plan.language)
        assertEquals(spec.genres, plan.style.genres)
        val tampered = plan.copy(sections = plan.sections + PlanSection(spec.slots[1].id, spec.slots[1].type, "Verse 1", "Overwritten!", ""))
        val validation = SongwritingPlanValidator.validate(tampered, spec)
        assertTrue(validation.valid)
        assertTrue(validation.warnings.any { it.code == PlanIssueCode.LOCKED_SECTION_CHANGED })
        val merged = SongwritingMerger.apply(prepared, tampered, spec, prepared.lyrics.sections)
        assertEquals("Mon couplet à moi", merged.lyrics.sections[1].lyrics)
        assertEquals(SectionOrigin.USER, merged.lyrics.sections[1].origin)
    }

    // ---- Merging --------------------------------------------------------------------------

    @Test
    fun `merging fills only requested sections, keeps edits made during generation and the user's title`() {
        val draft = storyDraft()
        val sections = SongSpecificationBuilder.build(draft, testCapabilities).sections
        val prepared = draft.copy(input = draft.input.copy(title = "Our Harbour"), lyrics = draft.lyrics.copy(sections = sections))
        val spec = SongSpecificationBuilder.build(prepared, testCapabilities).specification!!
        val plan = groundedPlan(spec)
        // The user typed into the chorus while the request was running.
        val editedDuring = prepared.copy(lyrics = prepared.lyrics.copy(sections = prepared.lyrics.sections.map { if (it.label == "Chorus") it.copy(lyrics = "typed meanwhile") else it }))
        val merged = SongwritingMerger.apply(editedDuring, plan, spec, prepared.lyrics.sections)
        val chorus = merged.lyrics.sections.single { it.label == "Chorus" }
        assertEquals("typed meanwhile", chorus.lyrics)
        val verse = merged.lyrics.sections.single { it.label == "Verse 1" }
        assertEquals(SectionOrigin.AI, verse.origin)
        assertTrue(verse.lyrics.contains("harbour"))
        assertEquals("Sing softly (v1)", verse.performanceNotes)
        assertEquals("Our Harbour", merged.input.title)
        assertEquals("Our Harbour", merged.lyrics.title)
        assertEquals("test-model", merged.songwriting!!.lastPlan!!.modelId)
        assertNull(merged.songwriting!!.pendingJob)
    }

    // ---- Coordinator: retries, cancellation, offline, resume ------------------------------

    private class Harness(val service: FakeSongwritingService = FakeSongwritingService()) {
        val pending = mutableListOf<MusicMePendingSongwritingJob?>()
        val phases = mutableListOf<SongwritingPhase>()
        val logs = mutableListOf<String>()
        var clock = 0L
        var keys = 0
        val coordinator = SongwritingCoordinator(
            service, SongwritingLog { logs += it }, sleep = { clock += it }, clock = { clock },
            newKey = { "key-${++keys}" }, pollIntervalMs = 1_000, maxWaitMs = 10_000,
        )

        suspend fun run(spec: SongSpecification, resume: MusicMePendingSongwritingJob? = null) =
            coordinator.execute(spec, resume, { pending += it }, { phases += it })
    }

    @Test
    fun `successful run records the pending job first, then clears it`() = runBlocking {
        val h = Harness()
        h.service.statusScript += { SongwritingJob("", JobState.QUEUED) }
        h.service.statusScript += { SongwritingJob("", JobState.RUNNING) }
        val result = h.run(spec())
        assertEquals("Harbour Rain for Ada", result.plan.title)
        assertNull(h.pending.first()!!.jobId)
        assertEquals("job-1", h.pending[1]!!.jobId)
        assertNull(h.pending.last())
        assertEquals(listOf(SongwritingPhase.SUBMITTING, SongwritingPhase.QUEUED, SongwritingPhase.WRITING, SongwritingPhase.CHECKING), h.phases)
    }

    @Test
    fun `transient failures retry with the same idempotency key, then succeed`() = runBlocking {
        val h = Harness()
        h.service.submitFailures += SongwritingException(SongwritingErrorCode.OFFLINE, "offline")
        h.service.submitFailures += SongwritingException(SongwritingErrorCode.RATE_LIMITED, "slow down", retryAfterSeconds = 7)
        h.run(spec())
        assertEquals(1, h.service.submits.size)
        assertEquals("key-1", h.service.submits.single().second)
        assertTrue(SongwritingPhase.RETRYING in h.phases)
        assertEquals(1_000L + 7_000L, h.clock)
    }

    @Test
    fun `offline and backend-unavailable failures end with a clear retryable error and no pending job`() = runBlocking {
        listOf(SongwritingErrorCode.OFFLINE, SongwritingErrorCode.UNAVAILABLE).forEach { code ->
            val h = Harness()
            repeat(4) { h.service.submitFailures += SongwritingException(code, RemoteSongwritingService.defaultMessage(code)) }
            val error = assertThrows(SongwritingException::class.java) { runBlocking { h.run(spec()) } }
            assertEquals(code, error.code)
            assertTrue(error.retryable)
            assertTrue(error.userMessage.contains("draft is saved"))
            assertNull(h.pending.last())
        }
    }

    @Test
    fun `non-retryable, failed, timed-out and invalid jobs surface specific errors`() = runBlocking {
        Harness().also { h ->
            h.service.submitFailures += SongwritingException(SongwritingErrorCode.POLICY_REJECTED, "no")
            assertEquals(SongwritingErrorCode.POLICY_REJECTED, assertThrows(SongwritingException::class.java) { runBlocking { h.run(spec()) } }.code)
            assertEquals(0, h.service.submits.size)
        }
        Harness().also { h ->
            h.service.statusScript += { SongwritingJob("", JobState.FAILED, error = SongwritingException(SongwritingErrorCode.UNAVAILABLE, "model down")) }
            assertEquals(SongwritingErrorCode.UNAVAILABLE, assertThrows(SongwritingException::class.java) { runBlocking { h.run(spec()) } }.code)
        }
        Harness().also { h ->
            repeat(30) { h.service.statusScript += { SongwritingJob("", JobState.RUNNING) } }
            assertEquals(SongwritingErrorCode.TIMEOUT, assertThrows(SongwritingException::class.java) { runBlocking { h.run(spec()) } }.code)
            assertEquals(listOf("job-1"), h.service.cancels)
        }
        Harness().also { h ->
            h.service.planFor = { groundedPlan(it).copy(language = "german") }
            val error = assertThrows(SongwritingException::class.java) { runBlocking { h.run(spec()) } }
            assertEquals(SongwritingErrorCode.MODEL_OUTPUT_INVALID, error.code)
            assertNull(h.pending.last())
        }
        Unit
    }

    @Test
    fun `cancellation cancels the server job and clears the pending record`() = runBlocking {
        val h = Harness()
        h.service.gate = CompletableDeferred()
        val running = async { h.run(spec()) }
        while (h.service.submits.isEmpty()) yield()
        running.cancel()
        assertThrows(CancellationException::class.java) { runBlocking { running.await() } }
        assertEquals(listOf("job-1"), h.service.cancels)
        assertNull(h.pending.last())
    }

    @Test
    fun `interrupted requests resume by polling a known job or resubmitting the same key`() = runBlocking {
        val spec = spec()
        Harness().also { h ->
            h.service.knowJob("job-77", spec)
            h.run(spec, MusicMePendingSongwritingJob("job-77", "key-old", null, 1))
            assertTrue(h.service.submits.isEmpty())
            assertEquals(SongwritingPhase.RESUMING, h.phases.first())
        }
        Harness().also { h ->
            h.run(spec, MusicMePendingSongwritingJob(null, "key-old", null, 1))
            assertEquals("key-old", h.service.submits.single().second)
        }
        Unit
    }

    @Test
    fun `logs carry codes and hashed ids only, never story, names or lyrics`() = runBlocking {
        val h = Harness()
        h.service.submitFailures += SongwritingException(SongwritingErrorCode.OFFLINE, "offline")
        h.run(spec())
        val text = h.logs.joinToString("\n")
        listOf("Ada", "harbour", "umbrella", "Thank you", "job-1", "key-1").forEach { assertFalse("log leaked $it: $text", text.contains(it)) }
        assertTrue(text.contains("musicme.songwriting completed"))
        SongwritingLog { assertFalse(it.contains("secret story")) }.event("x", "code" to "secret story", "story" to "secret story")
    }
}
