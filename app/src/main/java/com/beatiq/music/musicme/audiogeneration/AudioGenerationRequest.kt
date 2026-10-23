package com.beatiq.music.musicme.audiogeneration

import com.beatiq.music.musicme.model.MusicMeDraft
import com.beatiq.music.musicme.model.OptionCategory
import com.beatiq.music.musicme.songwriting.SongSpecificationBuilder.optionLabel
import org.json.JSONArray
import org.json.JSONObject

const val AUDIO_CONTRACT_VERSION = "musicme.audio.v1"

/**
 * What the app sends to the audio service. Built from the draft's lyrics and musical choices only: the
 * story, recipient, desired message and any voice recording stay on the device (audio needs none of them,
 * and reference recordings are never accepted).
 */
data class AudioGenerationRequest(
    val draftId: String,
    val title: String,
    val prompt: String,
    val language: String,
    val sections: List<Section>,
    val genres: List<String>,
    val moods: List<String>,
    val bpm: Int?,
    val instrumentation: List<String>,
    val vocalType: String,
    val vocalDescriptors: List<String>,
    val targetDurationSeconds: Int,
    val takes: Int = 1,
    val allowExplicit: Boolean = false,
) {
    data class Section(val id: String, val type: String, val label: String, val lyrics: String)

    fun toJson(): JSONObject = JSONObject()
        .put("contract_version", AUDIO_CONTRACT_VERSION)
        .put(
            "request",
            JSONObject()
                .put("draft_id", draftId)
                .put("title", title)
                .put("prompt", prompt)
                .put("language", language)
                .put("sections", JSONArray(sections.map { JSONObject().put("id", it.id).put("type", it.type).put("label", it.label).put("lyrics", it.lyrics) }))
                .put(
                    "style",
                    JSONObject().put("genres", JSONArray(genres)).put("moods", JSONArray(moods))
                        .put("bpm", bpm ?: JSONObject.NULL).put("instrumentation", JSONArray(instrumentation)),
                )
                .put("vocals", JSONObject().put("type", vocalType).put("descriptors", JSONArray(vocalDescriptors)))
                .put("target_duration_seconds", targetDurationSeconds)
                .put("takes", takes)
                .put("allow_explicit", allowExplicit),
        )
}

enum class AudioRequestIssue { NO_LYRICS, LYRICS_IN_INSTRUMENTAL_SECTION, UNSUPPORTED_SECTION, TOO_MANY_SECTIONS, VOCAL_TYPE_NEEDED, UNSUPPORTED_LANGUAGE, NO_GENRE }

data class AudioRequestBuildResult(val request: AudioGenerationRequest?, val issues: List<AudioRequestIssue>)

/** Turns a MusicMe draft into an [AudioGenerationRequest], mirroring the service's validation so problems show before sending. */
object AudioGenerationRequestBuilder {
    private const val MAX_SECTIONS = 16
    private val WORDLESS = setOf("instrumental", "interlude", "breakdown")
    private val SECTION_TYPES = mapOf(
        "intro" to "intro", "verse" to "verse", "rap_verse" to "verse", "pre_chorus" to "pre_chorus", "chorus" to "chorus",
        "final_chorus" to "chorus", "post_chorus" to "hook", "hook" to "hook", "repeated_hook" to "hook", "refrain" to "refrain",
        "bridge" to "bridge", "interlude" to "interlude", "spoken_interlude" to "interlude", "instrumental" to "instrumental",
        "instrumental_break" to "instrumental", "breakdown" to "breakdown", "outro" to "outro",
    )
    private val DESCRIPTOR = Regex("^[A-Za-z][A-Za-z -]{0,31}$")

    /** Catalogue vocal arrangement ids → service vocal types. Ambiguous arrangements need an explicit choice. */
    fun vocalType(arrangementIds: Set<String>): String? {
        val id = arrangementIds.singleOrNull() ?: return null
        return when {
            id == "instrumental_only" -> "none"
            id == "male_solo" || id == "two_male_voices" -> "male"
            id == "female_solo" || id == "two_female_voices" -> "female"
            id == "male_female_duet" -> "duet"
            id.endsWith("choir") || id == "mixed_group" -> "choir"
            else -> null
        }
    }

    fun build(draft: MusicMeDraft, capabilities: AudioGenerationCapabilities?): AudioRequestBuildResult {
        val config = draft.configuration
        val issues = mutableListOf<AudioRequestIssue>()
        fun labels(category: OptionCategory, limit: Int) =
            config.selections[category].orEmpty().map { optionLabel(category, it) }.distinct().take(limit)

        val vocalType = vocalType(config.selections[OptionCategory.VOCAL_ARRANGEMENT].orEmpty())
        if (vocalType == null) issues += AudioRequestIssue.VOCAL_TYPE_NEEDED
        val language = config.selections[OptionCategory.LANGUAGE].orEmpty().firstOrNull() ?: "english"
        if (capabilities != null && language !in capabilities.languages) issues += AudioRequestIssue.UNSUPPORTED_LANGUAGE

        val sections = draft.lyrics.sections.sortedBy { it.order }.mapNotNull { section ->
            val type = SECTION_TYPES[section.type.lowercase().replace('-', '_').replace(' ', '_')]
            if (type == null) {
                issues += AudioRequestIssue.UNSUPPORTED_SECTION
                null
            } else {
                AudioGenerationRequest.Section(section.id, type, section.label.take(40).ifBlank { type }, section.lyrics.trim())
            }
        }
        if (sections.size > (capabilities?.maxSections ?: MAX_SECTIONS)) issues += AudioRequestIssue.TOO_MANY_SECTIONS
        if (sections.any { it.type in WORDLESS && it.lyrics.isNotEmpty() }) issues += AudioRequestIssue.LYRICS_IN_INSTRUMENTAL_SECTION
        val prompt = listOf(draft.input.story, draft.input.desiredMessage, draft.input.emotionalContext,
            draft.input.pronunciationNotes.takeIf { it.isNotBlank() }?.let { "Pronunciation guidance: $it" }.orEmpty())
            .filter { it.isNotBlank() }.joinToString(". ").trim().take(1000)
        if (vocalType != null && vocalType != "none" && sections.none { it.lyrics.isNotEmpty() } && prompt.isBlank()) {
            issues += AudioRequestIssue.NO_LYRICS
        }

        val genreIds = listOf(config.genres.primaryGenreId) + config.genres.secondaryGenreIds
        val genres = genreIds.filter { it.isNotBlank() }.map { optionLabel(OptionCategory.GENRE, it) }.distinct().take(3)
        if (genres.isEmpty()) issues += AudioRequestIssue.NO_GENRE

        val minSeconds = capabilities?.minDurationSeconds ?: 30
        val maxSeconds = capabilities?.maxDurationSeconds ?: 240
        if (issues.isNotEmpty()) return AudioRequestBuildResult(null, issues.distinct())
        return AudioRequestBuildResult(
            AudioGenerationRequest(
                draftId = draft.id,
                title = draft.lyrics.title.ifBlank { draft.input.title }.trim().take(120),
                prompt = prompt,
                language = language,
                sections = sections,
                genres = genres,
                moods = labels(OptionCategory.MOOD, 3),
                bpm = config.bpm.takeIf { it in 50..200 },
                instrumentation = labels(OptionCategory.INSTRUMENT, 8),
                vocalType = vocalType!!,
                vocalDescriptors = labels(OptionCategory.VOCAL_CHARACTER, 4).filter { DESCRIPTOR.matches(it) },
                targetDurationSeconds = config.controls.durationSeconds.coerceIn(minSeconds, maxSeconds),
                allowExplicit = config.controls.allowExplicit,
            ),
            emptyList(),
        )
    }
}
