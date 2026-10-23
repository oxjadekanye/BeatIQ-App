package com.beatiq.music.musicme.songwriting

import com.beatiq.music.musicme.data.MusicMeCatalog
import com.beatiq.music.musicme.model.MusicMeDraft
import com.beatiq.music.musicme.model.MusicMeSongSection
import com.beatiq.music.musicme.model.OptionCategory
import com.beatiq.music.musicme.model.SectionOrigin
import com.beatiq.music.musicme.model.defaultSongSections

enum class Severity { BLOCKING, WARNING }

enum class SpecIssueCode {
    MISSING_CONTENT, VOICE_NOTE_NOT_TRANSCRIBED, INSTRUMENTAL_ONLY, NOTHING_TO_WRITE, INVALID_SECTION,
    TOO_MANY_SECTIONS, SHORT_DURATION_FOR_STRUCTURE, MOOD_OCCASION_CONFLICT, EXPLICIT_STYLE_DISABLED,
    NO_LANGUAGE_SELECTED, UNSUPPORTED_LANGUAGE, COPYRIGHTED_LYRICS, ARTIST_VOICE_IMITATION,
}

data class SpecIssue(val code: SpecIssueCode, val severity: Severity, val field: String?, val message: String)

data class SpecBuildResult(
    val specification: SongSpecification?,
    val issues: List<SpecIssue>,
    /**
     * Lyric sections the draft should hold before the request is sent. Differs from the draft's own
     * sections only when they are all still empty and the chosen arrangement defines the structure.
     */
    val sections: List<MusicMeSongSection>,
) {
    val blocking: List<SpecIssue> get() = issues.filter { it.severity == Severity.BLOCKING }
    val canGenerate: Boolean get() = specification != null && blocking.isEmpty()
}

/** Turns a MusicMe draft into a [SongSpecification] built only from what the user actually provided. */
object SongSpecificationBuilder {
    const val DEFAULT_MAX_SECTIONS = 16
    private const val MIN_SECONDS_PER_SUNG_SECTION = 12

    private val SOMBRE_OCCASIONS = setOf("memorial", "remembrance")
    private val UPBEAT_OCCASIONS = setOf("birthday", "celebration", "wedding", "congratulations", "graduation", "new_baby")
    private val UPBEAT_MOODS = setOf("joyful", "celebratory", "playful", "energetic", "party")
    private val SOMBRE_MOODS = setOf("heartbroken", "melancholic")

    fun build(
        draft: MusicMeDraft,
        capabilities: SongwritingCapabilities?,
        regenerateSectionId: String? = null,
    ): SpecBuildResult {
        val input = draft.input
        val config = draft.configuration
        val selections = config.selections
        fun ids(category: OptionCategory) = selections[category].orEmpty().toList()
        fun labels(category: OptionCategory) = ids(category).map { optionLabel(category, it) }
        val issues = mutableListOf<SpecIssue>()

        if (input.story.isBlank() && input.desiredMessage.isBlank()) {
            issues += if (draft.recording != null) {
                SpecIssue(
                    SpecIssueCode.VOICE_NOTE_NOT_TRANSCRIBED, Severity.BLOCKING, "story",
                    "Voice notes aren't transcribed yet. Add your story or desired message so the lyrics use your own words.",
                )
            } else {
                SpecIssue(SpecIssueCode.MISSING_CONTENT, Severity.BLOCKING, "story", "Add your story or desired message so the lyrics have something real to say.")
            }
        }
        if ("instrumental_only" in ids(OptionCategory.VOCAL_ARRANGEMENT)) {
            issues += SpecIssue(
                SpecIssueCode.INSTRUMENTAL_ONLY, Severity.BLOCKING, "vocal_arrangement",
                "The vocal arrangement is set to Instrumental Only. Choose a vocal arrangement to write lyrics.",
            )
        }

        val languageIds = ids(OptionCategory.LANGUAGE).ifEmpty {
            issues += SpecIssue(SpecIssueCode.NO_LANGUAGE_SELECTED, Severity.WARNING, "language", "No lyric language was chosen, so English will be used.")
            listOf("english")
        }
        capabilities?.let { caps ->
            val unsupported = languageIds.filterNot { it in caps.languages }
            if (unsupported.isNotEmpty()) {
                issues += SpecIssue(
                    SpecIssueCode.UNSUPPORTED_LANGUAGE, Severity.BLOCKING, "language",
                    "The songwriting model can't yet write reliably in ${unsupported.joinToString { optionLabel(OptionCategory.LANGUAGE, it) }}. " +
                        "Choose another language or remove it from the selection.",
                )
            }
        }

        val occasion = input.occasionId
        val moods = ids(OptionCategory.MOOD).toSet()
        if ((occasion in SOMBRE_OCCASIONS && moods.any { it in UPBEAT_MOODS }) || (occasion in UPBEAT_OCCASIONS && moods.any { it in SOMBRE_MOODS })) {
            issues += SpecIssue(
                SpecIssueCode.MOOD_OCCASION_CONFLICT, Severity.WARNING, "mood",
                "The ${optionLabel(OptionCategory.OCCASION, occasion)} occasion and the ${labels(OptionCategory.MOOD).joinToString()} mood pull in different directions. " +
                    "That can be intentional; adjust the mood if not.",
            )
        }
        if (!config.controls.allowExplicit && ids(OptionCategory.LYRICAL_STYLE).any { "explicit" in it }) {
            issues += SpecIssue(
                SpecIssueCode.EXPLICIT_STYLE_DISABLED, Severity.WARNING, "lyrical_style",
                "An explicit lyrical style is selected but explicit lyrics are turned off, so lyrics will stay clean.",
            )
        }

        issues += SongwritingSafety.screen(
            mapOf(
                "story" to input.story, "desired_message" to input.desiredMessage, "emotional_context" to input.emotionalContext,
                "pronunciation_notes" to input.pronunciationNotes, "title" to input.title, "recipient" to input.recipient,
                "emotional_progression" to config.emotionalProgression,
            ),
        )

        val sections = structureFor(draft)
        val slots = sections.map { section ->
            val type = SectionType.fromWire(section.type) ?: SectionType.fromLabel(section.label)
            val userOwned = section.lyrics.isNotBlank() && section.origin != SectionOrigin.AI
            SongSlot(section.id, type, section.label.ifBlank { type.wire }, lockedLyrics = section.lyrics.takeIf { userOwned })
        }
        val maxSections = capabilities?.maxSections ?: DEFAULT_MAX_SECTIONS
        if (slots.size > maxSections) {
            issues += SpecIssue(SpecIssueCode.TOO_MANY_SECTIONS, Severity.BLOCKING, "structure", "The song has ${slots.size} sections; the songwriting service supports up to $maxSections.")
        }
        val sung = slots.count { it.type.sung }
        val duration = config.controls.durationSeconds
        if (sung > 0 && duration / sung < MIN_SECONDS_PER_SUNG_SECTION) {
            issues += SpecIssue(
                SpecIssueCode.SHORT_DURATION_FOR_STRUCTURE, Severity.WARNING, "duration",
                "$sung sung sections in $duration seconds leaves very little time for each. Consider a longer duration or fewer sections.",
            )
        }

        if (regenerateSectionId != null) {
            val target = slots.firstOrNull { it.id == regenerateSectionId }
            if (target == null || !target.type.sung) {
                issues += SpecIssue(SpecIssueCode.INVALID_SECTION, Severity.BLOCKING, "structure", "That section can't be rewritten because it has no sung lyrics.")
            } else if (capabilities != null && !capabilities.supportsSectionRegeneration) {
                issues += SpecIssue(SpecIssueCode.INVALID_SECTION, Severity.BLOCKING, "structure", "The connected songwriting service can't rewrite single sections yet.")
            }
        } else if (slots.none { it.type.sung && !it.locked }) {
            issues += SpecIssue(
                SpecIssueCode.NOTHING_TO_WRITE, Severity.BLOCKING, "structure",
                "Every section already has your own lyrics. Add an empty section, or rewrite a single section instead.",
            )
        }

        val spec = SongSpecification(
            draftId = draft.id,
            language = languageIds.first(),
            additionalLanguages = languageIds.drop(1),
            story = input.story.trim(),
            desiredMessage = input.desiredMessage.trim(),
            recipient = input.recipient.trim(),
            occasion = optionLabel(OptionCategory.OCCASION, occasion),
            emotionalContext = input.emotionalContext.trim(),
            emotionalProgression = config.emotionalProgression.trim(),
            pronunciationNotes = input.pronunciationNotes.trim(),
            userTitle = input.title.trim(),
            genres = (listOf(config.genres.primaryGenreId) + config.genres.secondaryGenreIds).distinct().map { optionLabel(OptionCategory.GENRE, it) },
            era = labels(OptionCategory.ERA),
            moods = labels(OptionCategory.MOOD),
            tempo = labels(OptionCategory.TEMPO).firstOrNull().orEmpty(),
            bpm = config.bpm,
            rhythm = labels(OptionCategory.RHYTHM),
            vocalArrangement = labels(OptionCategory.VOCAL_ARRANGEMENT),
            vocalCharacter = labels(OptionCategory.VOCAL_CHARACTER),
            vocalDelivery = labels(OptionCategory.VOCAL_DELIVERY),
            instruments = labels(OptionCategory.INSTRUMENT),
            beats = labels(OptionCategory.BEATS),
            lyricalStyle = labels(OptionCategory.LYRICAL_STYLE),
            productionStyle = labels(OptionCategory.PRODUCTION_STYLE),
            arrangementName = config.arrangementName,
            targetDurationSeconds = duration,
            allowExplicit = config.controls.allowExplicit,
            slots = slots,
            regenerateSlotId = regenerateSectionId,
        )
        return SpecBuildResult(spec, issues, sections)
    }

    /**
     * The draft's own lyric sections whenever the user has shaped them (any text, or a structure
     * different from the untouched defaults). Otherwise the chosen arrangement defines the structure;
     * once applied, the same sections (and ids) are returned on every later call.
     */
    fun structureFor(draft: MusicMeDraft): List<MusicMeSongSection> {
        val current = draft.lyrics.sections
        val arrangement = draft.configuration.sections
        val untouchedDefaults = current.map { it.label } == defaultSongSections().map { it.label } && current.all { it.lyrics.isBlank() }
        if (arrangement.isEmpty() || (!untouchedDefaults && current.isNotEmpty())) return current
        if (current.map { it.label } == arrangement) return current
        return arrangement.mapIndexed { index, label ->
            MusicMeSongSection(type = SectionType.fromLabel(label).wire, label = label, order = index)
        }
    }

    fun optionLabel(category: OptionCategory, id: String): String =
        MusicMeCatalog.all[category]?.firstOrNull { it.id == id }?.label ?: id.replace('_', ' ')
}
