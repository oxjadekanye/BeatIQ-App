package com.beatiq.music.musicme.songwriting

import com.beatiq.music.musicme.model.MusicMeConfiguration
import com.beatiq.music.musicme.model.MusicMeDraft
import com.beatiq.music.musicme.model.MusicMeInput
import com.beatiq.music.musicme.model.OptionCategory
import com.beatiq.music.musicme.model.defaultSelections

/** A draft with a real story, dedication and studio choices, as a user would build it. */
fun storyDraft(
    story: String = "We met at the Lagos harbour during the rain festival. Ada laughed at my umbrella and kept the ticket for ten years.",
    message: String = "Thank you for ten brave years",
    recipient: String = "Ada",
    languages: Set<String> = setOf("english"),
    arrangement: String = "Wedding Song",
): MusicMeDraft {
    val sections = com.beatiq.music.musicme.data.MusicMeCatalog.arrangements.getValue(arrangement)
    return MusicMeDraft(
        name = "Anniversary",
        input = MusicMeInput(story = story, desiredMessage = message, recipient = recipient, occasionId = "anniversary", pronunciationNotes = "Ada: AH-dah"),
        configuration = MusicMeConfiguration(
            selections = defaultSelections() + (OptionCategory.LANGUAGE to languages),
            arrangementName = arrangement,
            sections = sections,
            bpm = 84,
        ),
    )
}

val testCapabilities = SongwritingCapabilities(
    contractVersion = SONGWRITING_CONTRACT_VERSION, modelId = "test-model", engine = "self_hosted",
    languages = setOf("english", "french", "spanish"), maxSections = 16, supportsSectionRegeneration = true,
)

/** A plan that genuinely uses the request: dedication name and story words, distinct verses. */
fun groundedPlan(spec: SongSpecification, tag: String = "v1", requestId: String = "req-1"): SongwritingPlan {
    val name = spec.recipient.ifBlank { "you" }
    var verse = 0
    val sections = spec.writableSlots.map { slot ->
        val lyrics = when (slot.type) {
            SectionType.CHORUS, SectionType.HOOK, SectionType.REFRAIN -> "${spec.desiredMessage}\nThis one is for $name\n${spec.desiredMessage}\nSinging for $name"
            SectionType.INTRO, SectionType.OUTRO -> "For $name ($tag)\nFrom the harbour to here"
            else -> { verse++; "Verse $verse $tag: the rain at the harbour\n$name laughed at my umbrella\nTen years and the ticket kept\nLine $verse for $name" }
        }
        PlanSection(slot.id, slot.type, slot.label, lyrics, "Sing softly ($tag)")
    }
    return SongwritingPlan(
        contractVersion = SONGWRITING_CONTRACT_VERSION, title = "Harbour Rain for $name", summary = "An anniversary song about the harbour and the umbrella.",
        language = spec.language, sections = sections, sectionOrder = spec.slots.map { it.id },
        style = StyleGuidance(spec.genres, "Contemporary", spec.arrangementName, "Rhodes, strings", "Warm vintage"),
        tempoBpm = spec.bpm, estimatedDurationSeconds = spec.targetDurationSeconds,
        vocalInstructions = "Warm and close", pronunciationNotes = spec.pronunciationNotes,
        metadata = GenerationMetadata("test-model", "1", "self_hosted", requestId, 1L, "p1"),
    )
}

/** Scriptable in-memory service. Each status() call pops the next scripted state for the job. */
class FakeSongwritingService(
    override val isConfigured: Boolean = true,
    var capabilitiesResult: SongwritingCapabilities = testCapabilities,
) : SongwritingService {
    val submits = mutableListOf<Pair<SongSpecification, String>>()
    val cancels = mutableListOf<String>()
    val submitFailures = ArrayDeque<SongwritingException>()
    val statusScript = ArrayDeque<(SongSpecification) -> SongwritingJob>()
    var planFor: (SongSpecification) -> SongwritingPlan = { groundedPlan(it) }
    var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    private val jobsByKey = mutableMapOf<String, String>()
    private val specs = mutableMapOf<String, SongSpecification>()

    override suspend fun capabilities() = capabilitiesResult

    override suspend fun submit(spec: SongSpecification, idempotencyKey: String): SongwritingJob {
        submitFailures.removeFirstOrNull()?.let { throw it }
        submits += spec to idempotencyKey
        val id = jobsByKey.getOrPut(idempotencyKey) { "job-${jobsByKey.size + 1}" }
        specs[id] = spec
        return SongwritingJob(id, JobState.QUEUED)
    }

    override suspend fun status(jobId: String): SongwritingJob {
        gate?.await()
        val spec = specs[jobId] ?: throw SongwritingException(SongwritingErrorCode.JOB_NOT_FOUND, "gone")
        val scripted = statusScript.removeFirstOrNull()
        return scripted?.invoke(spec)?.copy(id = jobId) ?: SongwritingJob(jobId, JobState.SUCCEEDED, planFor(spec))
    }

    /** Lets a resumed job be found without a submit in this process (simulating the server's memory). */
    fun knowJob(jobId: String, spec: SongSpecification) { specs[jobId] = spec }

    override suspend fun cancel(jobId: String) { cancels += jobId }
}
