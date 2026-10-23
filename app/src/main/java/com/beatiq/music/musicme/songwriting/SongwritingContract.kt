package com.beatiq.music.musicme.songwriting

import org.json.JSONArray
import org.json.JSONObject

/**
 * Versioned wire contract between the app and the songwriting service. Field names are snake_case
 * on the wire; the matching JSON Schema lives in services/musicme-songwriter/contract/.
 */
const val SONGWRITING_CONTRACT_VERSION = "musicme.songwriting.v1"

/** One lyric slot the plan must fill (or leave alone when [locked]). Ids are the draft's section ids. */
data class SongSlot(
    val id: String,
    val type: SectionType,
    val label: String,
    /** User-written or user-edited lyrics; sent as context and never rewritten. */
    val lockedLyrics: String? = null,
) {
    val locked: Boolean get() = lockedLyrics != null
}

enum class SectionType(val wire: String, val sung: Boolean = true, val lyricsOptional: Boolean = false) {
    INTRO("intro", lyricsOptional = true), VERSE("verse"), PRE_CHORUS("pre_chorus"), CHORUS("chorus"), POST_CHORUS("post_chorus"),
    HOOK("hook"), BRIDGE("bridge"), REFRAIN("refrain"), RAP_VERSE("rap_verse"), SPOKEN("spoken"),
    CALL_AND_RESPONSE("call_and_response"), BREAKDOWN("breakdown"), OUTRO("outro", lyricsOptional = true),
    INSTRUMENTAL("instrumental", sung = false);

    companion object {
        fun fromWire(value: String): SectionType? = entries.firstOrNull { it.wire == value }

        /** Maps arrangement and Lyrics Studio labels ("Verse 2", "Final Chorus", "Drop") to a section type. */
        fun fromLabel(label: String): SectionType {
            val l = label.lowercase()
            return when {
                "pre" in l && "chorus" in l -> PRE_CHORUS
                "post" in l && "chorus" in l -> POST_CHORUS
                "instrumental" in l || "solo" == l.trim() || "drop" in l || "build" in l -> INSTRUMENTAL
                "rap" in l -> RAP_VERSE
                "spoken" in l || "interlude" in l -> SPOKEN
                "call" in l && "response" in l -> CALL_AND_RESPONSE
                "breakdown" in l -> BREAKDOWN
                "chorus" in l -> CHORUS
                "hook" in l -> HOOK
                "refrain" in l -> REFRAIN
                "bridge" in l -> BRIDGE
                "intro" in l -> INTRO
                "outro" in l || "fade" in l -> OUTRO
                else -> VERSE
            }
        }
    }
}

data class SongSpecification(
    val contractVersion: String = SONGWRITING_CONTRACT_VERSION,
    val draftId: String,
    val language: String,
    val additionalLanguages: List<String>,
    val story: String,
    val desiredMessage: String,
    val recipient: String,
    val occasion: String,
    val emotionalContext: String,
    val emotionalProgression: String,
    val pronunciationNotes: String,
    val userTitle: String,
    val genres: List<String>,
    val era: List<String>,
    val moods: List<String>,
    val tempo: String,
    val bpm: Int,
    val rhythm: List<String>,
    val vocalArrangement: List<String>,
    val vocalCharacter: List<String>,
    val vocalDelivery: List<String>,
    val instruments: List<String>,
    val beats: List<String>,
    val lyricalStyle: List<String>,
    val productionStyle: List<String>,
    val arrangementName: String,
    val targetDurationSeconds: Int,
    val allowExplicit: Boolean,
    val slots: List<SongSlot>,
    /** When set, only this slot is (re)written; every other slot is context. */
    val regenerateSlotId: String? = null,
) {
    /** Slots the service is asked to write. */
    val writableSlots: List<SongSlot>
        get() = slots.filter { slot ->
            slot.type.sung && if (regenerateSlotId != null) slot.id == regenerateSlotId else !slot.locked
        }
}

data class PlanSection(
    val slotId: String,
    val type: SectionType,
    val label: String,
    val lyrics: String,
    val performanceNotes: String,
)

data class StyleGuidance(
    val genres: List<String>,
    val era: String,
    val arrangement: String,
    val instrumentation: String,
    val production: String,
)

data class GenerationMetadata(
    val modelId: String,
    val modelVersion: String,
    /** "self_hosted" for a real model; "mock" for the development engine. Shown to the user as-is. */
    val engine: String,
    val requestId: String,
    val generatedAt: Long,
    val promptVersion: String,
)

data class SongwritingPlan(
    val contractVersion: String,
    val title: String,
    val summary: String,
    val language: String,
    val sections: List<PlanSection>,
    val sectionOrder: List<String>,
    val style: StyleGuidance,
    val tempoBpm: Int,
    val estimatedDurationSeconds: Int,
    val vocalInstructions: String,
    val pronunciationNotes: String,
    val metadata: GenerationMetadata,
)

object SongwritingJson {
    fun encodeSpecification(spec: SongSpecification): JSONObject = JSONObject()
        .put("contract_version", spec.contractVersion)
        .put("draft_id", spec.draftId)
        .put("language", spec.language)
        .put("additional_languages", JSONArray(spec.additionalLanguages))
        .put("story", spec.story)
        .put("desired_message", spec.desiredMessage)
        .put("recipient", spec.recipient)
        .put("occasion", spec.occasion)
        .put("emotional_context", spec.emotionalContext)
        .put("emotional_progression", spec.emotionalProgression)
        .put("pronunciation_notes", spec.pronunciationNotes)
        .put("user_title", spec.userTitle)
        .put(
            "music",
            JSONObject()
                .put("genres", JSONArray(spec.genres)).put("era", JSONArray(spec.era)).put("moods", JSONArray(spec.moods))
                .put("tempo", spec.tempo).put("bpm", spec.bpm).put("rhythm", JSONArray(spec.rhythm))
                .put("vocal_arrangement", JSONArray(spec.vocalArrangement)).put("vocal_character", JSONArray(spec.vocalCharacter))
                .put("vocal_delivery", JSONArray(spec.vocalDelivery)).put("instruments", JSONArray(spec.instruments))
                .put("beats", JSONArray(spec.beats)).put("lyrical_style", JSONArray(spec.lyricalStyle))
                .put("production_style", JSONArray(spec.productionStyle)).put("arrangement_name", spec.arrangementName)
                .put("target_duration_seconds", spec.targetDurationSeconds).put("allow_explicit", spec.allowExplicit),
        )
        .put(
            "structure",
            JSONArray().apply {
                spec.slots.forEach { slot ->
                    put(
                        JSONObject().put("id", slot.id).put("type", slot.type.wire).put("label", slot.label)
                            .put("locked_lyrics", slot.lockedLyrics ?: JSONObject.NULL),
                    )
                }
            },
        )
        .put("regenerate_section_id", spec.regenerateSlotId ?: JSONObject.NULL)

    /** Throws [SongwritingException] with [SongwritingErrorCode.MODEL_OUTPUT_INVALID] for anything malformed. */
    fun decodePlan(json: JSONObject): SongwritingPlan = try {
        val metadata = json.getJSONObject("metadata")
        val style = json.getJSONObject("style")
        SongwritingPlan(
            contractVersion = json.getString("contract_version"),
            title = json.getString("title"),
            summary = json.getString("summary"),
            language = json.getString("language"),
            sections = json.getJSONArray("sections").let { array ->
                List(array.length()) { index ->
                    val section = array.getJSONObject(index)
                    val rawType = section.getString("type")
                    PlanSection(
                        slotId = section.getString("id"),
                        type = SectionType.fromWire(rawType) ?: throw IllegalArgumentException("Unknown section type"),
                        label = section.optString("label"),
                        lyrics = section.getString("lyrics"),
                        performanceNotes = section.optString("performance_notes"),
                    )
                }
            },
            sectionOrder = json.getJSONArray("section_order").let { array -> List(array.length()) { array.getString(it) } },
            style = StyleGuidance(
                genres = style.optJSONArray("genres")?.let { array -> List(array.length()) { array.getString(it) } }.orEmpty(),
                era = style.optString("era"),
                arrangement = style.optString("arrangement"),
                instrumentation = style.optString("instrumentation"),
                production = style.optString("production"),
            ),
            tempoBpm = json.getInt("tempo_bpm"),
            estimatedDurationSeconds = json.getInt("estimated_duration_seconds"),
            vocalInstructions = json.optString("vocal_instructions"),
            pronunciationNotes = json.optString("pronunciation_notes"),
            metadata = GenerationMetadata(
                modelId = metadata.getString("model_id"),
                modelVersion = metadata.optString("model_version"),
                engine = metadata.getString("engine"),
                requestId = metadata.getString("request_id"),
                generatedAt = metadata.optLong("generated_at"),
                promptVersion = metadata.optString("prompt_version"),
            ),
        )
    } catch (error: SongwritingException) {
        throw error
    } catch (error: Exception) {
        throw SongwritingException(SongwritingErrorCode.MODEL_OUTPUT_INVALID, "The songwriting service returned an incomplete song plan.")
    }
}
