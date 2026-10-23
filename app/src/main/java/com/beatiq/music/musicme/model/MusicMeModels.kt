package com.beatiq.music.musicme.model

import java.util.UUID

enum class MusicMeGenerationStatus { DRAFT, CONFIGURED, QUEUED, GENERATING, PROCESSING, COMPLETED, FAILED, CANCELLED }
enum class CreationMode { QUICK, GUIDED, ADVANCED, SURPRISE }
enum class OptionCategory {
    GENRE, ERA, VOCAL_ARRANGEMENT, VOCAL_CHARACTER, VOCAL_DELIVERY, STRUCTURE,
    TEMPO, RHYTHM, MOOD, BEATS, INSTRUMENT, LANGUAGE, LYRICAL_STYLE, PRODUCTION_STYLE, OCCASION
}

data class MusicMeOption(
    val id: String,
    val label: String,
    val description: String,
    val category: OptionCategory,
    val multiSelect: Boolean = true,
    val recommended: Boolean = false,
    val compatibleWith: Set<String> = emptySet(),
    val incompatibleWith: Set<String> = emptySet(),
    val generationParameter: String = id,
    val supportedInPhaseOne: Boolean = true,
)

data class MusicMeInput(
    val title: String = "",
    val story: String = "",
    val recipient: String = "",
    val occasionId: String = "personal_story",
    val desiredMessage: String = "",
    val emotionalContext: String = "",
    val pronunciationNotes: String = "",
)

data class MusicMeVoiceRecording(
    val id: String = UUID.randomUUID().toString(),
    val localFileName: String,
    val durationMs: Long,
    val createdAt: Long = System.currentTimeMillis(),
)

data class MusicMeGenreSelection(
    val primaryGenreId: String = "contemporary_r_and_b",
    val secondaryGenreIds: Set<String> = setOf("soul"),
    val weights: Map<String, Int> = mapOf("contemporary_r_and_b" to 70, "soul" to 30),
)

data class MusicMeSongSection(
    val id: String = UUID.randomUUID().toString(),
    val type: String,
    val label: String = type,
    val lyrics: String = "",
    val order: Int,
    /** Who wrote the current lyrics; generation never overwrites [SectionOrigin.USER] or [SectionOrigin.AI_EDITED] text. */
    val origin: SectionOrigin = SectionOrigin.USER,
    /** Vocal performance guidance supplied with generated lyrics. */
    val performanceNotes: String = "",
)

enum class SectionOrigin { USER, AI, AI_EDITED }

/** Songwriting results and any in-flight request, stored with the draft so both survive restarts and backups. */
data class MusicMeSongwritingState(
    val lastPlan: MusicMeSongwritingSummary? = null,
    val pendingJob: MusicMePendingSongwritingJob? = null,
)

data class MusicMeSongwritingSummary(
    val contractVersion: String,
    val title: String,
    val summary: String,
    val styleGuidance: String,
    val arrangementGuidance: String,
    val tempoBpm: Int,
    val estimatedDurationSeconds: Int,
    val vocalInstructions: String,
    val pronunciationNotes: String,
    val language: String,
    val modelId: String,
    val modelVersion: String,
    val engine: String,
    val requestId: String,
    val generatedAt: Long,
)

/** [jobId] is null until the service acknowledges the request; resubmitting with [idempotencyKey] is then safe. */
data class MusicMePendingSongwritingJob(
    val jobId: String?,
    val idempotencyKey: String,
    val targetSectionId: String?,
    val submittedAt: Long,
)

data class MusicMeLyricsDraft(
    val title: String = "",
    val sections: List<MusicMeSongSection> = defaultSongSections(),
)

fun defaultSongSections() = listOf(
    MusicMeSongSection(type = "verse", label = "Verse 1", order = 0),
    MusicMeSongSection(type = "chorus", label = "Chorus", order = 1),
    MusicMeSongSection(type = "verse", label = "Verse 2", order = 2),
    MusicMeSongSection(type = "bridge", label = "Bridge", order = 3),
    MusicMeSongSection(type = "chorus", label = "Final Chorus", order = 4),
)

data class AdvancedControls(
    val durationSeconds: Int = 150,
    val vocalIntensity: Int = 55,
    val emotionalIntensity: Int = 65,
    val instrumentalComplexity: Int = 50,
    val bassIntensity: Int = 50,
    val chorusRepetition: Int = 50,
    val harmonyRichness: Int = 55,
    val lyricDensity: Int = 50,
    val introLength: Int = 20,
    val outroLength: Int = 20,
    val energyProgression: Int = 60,
    val vocalBalance: Int = 60,
    val electronicBalance: Int = 50,
    val syntheticBalance: Int = 45,
    val fadeIn: Boolean = false,
    val fadeOut: Boolean = true,
    val allowExplicit: Boolean = false,
    val aiAutomatic: Boolean = true,
)

data class MusicMeConfiguration(
    val mode: CreationMode = CreationMode.QUICK,
    val genres: MusicMeGenreSelection = MusicMeGenreSelection(),
    val selections: Map<OptionCategory, Set<String>> = defaultSelections(),
    val bpm: Int = 88,
    val emotionalProgression: String = "Warm opening → uplifting finish",
    val arrangementName: String = "R&B Ballad",
    val sections: List<String> = listOf("Intro", "Verse 1", "Pre-Chorus", "Chorus", "Verse 2", "Chorus", "Bridge", "Final Chorus", "Outro"),
    val controls: AdvancedControls = AdvancedControls(),
)

fun defaultSelections(): Map<OptionCategory, Set<String>> = mapOf(
    OptionCategory.ERA to setOf("contemporary"),
    OptionCategory.VOCAL_ARRANGEMENT to setOf("lead_and_backing_vocals"),
    OptionCategory.VOCAL_CHARACTER to setOf("warm", "soulful"),
    OptionCategory.VOCAL_DELIVERY to setOf("emotional"),
    OptionCategory.STRUCTURE to setOf("ai_automatic"),
    OptionCategory.TEMPO to setOf("medium_slow"),
    OptionCategory.RHYTHM to setOf("straight"),
    OptionCategory.MOOD to setOf("heartwarming", "hopeful"),
    OptionCategory.BEATS to setOf("acoustic_drums"),
    OptionCategory.INSTRUMENT to setOf("grand_piano", "strings"),
    OptionCategory.LANGUAGE to setOf("english"),
    OptionCategory.LYRICAL_STYLE to setOf("storytelling", "personal_dedication"),
    OptionCategory.PRODUCTION_STYLE to setOf("studio_polished", "vocal_forward"),
    OptionCategory.OCCASION to setOf("personal_story"),
)

data class MusicMeDraft(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Untitled creation",
    val createdAt: Long = System.currentTimeMillis(),
    val modifiedAt: Long = System.currentTimeMillis(),
    val status: MusicMeGenerationStatus = MusicMeGenerationStatus.DRAFT,
    val input: MusicMeInput = MusicMeInput(),
    val recording: MusicMeVoiceRecording? = null,
    val configuration: MusicMeConfiguration = MusicMeConfiguration(),
    val lyrics: MusicMeLyricsDraft = MusicMeLyricsDraft(),
    val songwriting: MusicMeSongwritingState? = null,
)

data class MusicMePreset(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val configuration: MusicMeConfiguration,
)

data class MusicMeGenerationRequest(
    val draftId: String,
    val input: MusicMeInput,
    val configuration: MusicMeConfiguration,
    val lyrics: MusicMeLyricsDraft,
    val recordingId: String?,
)
