package com.beatiq.music.musicme.songwriting

enum class PlanIssueCode {
    CONTRACT_MISMATCH, MISSING_FIELD, LANGUAGE_MISMATCH, STRUCTURE_MISMATCH, MISSING_SECTION, UNEXPECTED_SECTION,
    EMPTY_LYRICS, LOCKED_SECTION_CHANGED, TOO_LONG, REPETITIVE, GENERIC_CONTENT, TEMPO_OUT_OF_RANGE,
    DURATION_MISMATCH, METADATA_MISSING, DEDICATION_NOT_NAMED,
}

data class PlanIssue(val code: PlanIssueCode, val message: String)

data class PlanValidation(val errors: List<PlanIssue>, val warnings: List<PlanIssue>) {
    val valid: Boolean get() = errors.isEmpty()
}

/**
 * Checks a model's plan against the request before anything reaches the draft. A plan that fails
 * is reported as an error, never silently replaced by template lyrics.
 */
object SongwritingPlanValidator {
    private const val MAX_SECTION_CHARS = 2_000
    private const val MAX_SECTION_LINES = 40
    private const val MAX_LINE_CHARS = 160
    private const val MIN_DISTINCT_LINE_RATIO = 0.5
    private const val MAX_SAME_WORD_RUN = 5
    private val REPEATING_TYPES = setOf(SectionType.CHORUS, SectionType.HOOK, SectionType.REFRAIN, SectionType.CALL_AND_RESPONSE, SectionType.POST_CHORUS)
    private val ENGINES = setOf("self_hosted", "mock")
    private val STOPWORDS = setOf(
        "this", "that", "with", "from", "have", "were", "they", "them", "their", "there", "what", "when", "where", "which",
        "would", "could", "should", "about", "into", "your", "yours", "just", "like", "will", "been", "being", "than",
        "then", "also", "very", "much", "more", "most", "some", "such", "only", "over", "after", "before", "because",
        "song", "songs", "music", "want", "make", "made", "know", "love", "always", "every", "everything", "feel",
    )

    fun validate(plan: SongwritingPlan, spec: SongSpecification): PlanValidation {
        val errors = mutableListOf<PlanIssue>()
        val warnings = mutableListOf<PlanIssue>()
        fun error(code: PlanIssueCode, message: String) { errors += PlanIssue(code, message) }

        if (plan.contractVersion != spec.contractVersion) error(PlanIssueCode.CONTRACT_MISMATCH, "Unsupported song plan version.")
        if (plan.title.isBlank() || plan.summary.isBlank()) error(PlanIssueCode.MISSING_FIELD, "The song plan is missing its title or summary.")
        if (plan.title.length > 100 || plan.summary.length > 800) error(PlanIssueCode.TOO_LONG, "The song plan's title or summary is too long.")
        if (plan.language != spec.language) error(PlanIssueCode.LANGUAGE_MISMATCH, "The lyrics came back in a different language from the one you chose.")
        if (plan.metadata.modelId.isBlank() || plan.metadata.requestId.isBlank() || plan.metadata.engine !in ENGINES) {
            error(PlanIssueCode.METADATA_MISSING, "The song plan is missing its generation details.")
        }
        if (plan.sectionOrder != spec.slots.map { it.id }) error(PlanIssueCode.STRUCTURE_MISMATCH, "The song plan doesn't follow your song structure.")

        val slots = spec.slots.associateBy { it.id }
        val seen = mutableSetOf<String>()
        plan.sections.forEach { section ->
            val slot = slots[section.slotId]
            when {
                slot == null || !seen.add(section.slotId) -> error(PlanIssueCode.UNEXPECTED_SECTION, "The song plan contains a section that isn't in your structure.")
                section.type != slot.type -> error(PlanIssueCode.STRUCTURE_MISMATCH, "The ${slot.label} section came back as a different kind of section.")
                slot.locked && section.lyrics.trim() != slot.lockedLyrics?.trim() ->
                    warnings += PlanIssue(PlanIssueCode.LOCKED_SECTION_CHANGED, "Suggested changes to your own ${slot.label} lyrics were ignored.")
                !slot.type.sung && section.lyrics.isNotBlank() ->
                    warnings += PlanIssue(PlanIssueCode.UNEXPECTED_SECTION, "Lyrics for the instrumental ${slot.label} were ignored.")
            }
        }

        val written = writtenSections(plan, spec)
        spec.writableSlots.forEach { slot ->
            val section = written[slot.id]
            if (section == null) error(PlanIssueCode.MISSING_SECTION, "The song plan left out the ${slot.label} section.")
            // Intros and outros may be instrumental; every other sung section needs words.
            else if (section.lyrics.isBlank() && !slot.type.lyricsOptional) error(PlanIssueCode.EMPTY_LYRICS, "The ${slot.label} section came back empty.")
        }
        written.values.forEach { section ->
            val lines = lyricLines(section.lyrics)
            if (section.lyrics.length > MAX_SECTION_CHARS || lines.size > MAX_SECTION_LINES || lines.any { it.length > MAX_LINE_CHARS }) {
                error(PlanIssueCode.TOO_LONG, "The ${section.label.ifBlank { "generated" }} section is far longer than a song section should be.")
            }
            if (isRepetitive(section, lines)) error(PlanIssueCode.REPETITIVE, "The ${section.label.ifBlank { "generated" }} section repeats itself too much.")
        }
        val verses = written.values.filter { it.type == SectionType.VERSE || it.type == SectionType.RAP_VERSE }.map { normalise(it.lyrics) }
        if (verses.size != verses.distinct().size) error(PlanIssueCode.REPETITIVE, "Two verses came back with the same lyrics.")

        if (plan.tempoBpm !in 40..220) error(PlanIssueCode.TEMPO_OUT_OF_RANGE, "The suggested tempo is outside 40–220 BPM.")
        else if (kotlin.math.abs(plan.tempoBpm - spec.bpm) > 15) warnings += PlanIssue(PlanIssueCode.TEMPO_OUT_OF_RANGE, "The suggested tempo differs from your ${spec.bpm} BPM; your setting is kept.")
        val target = spec.targetDurationSeconds.coerceAtLeast(1)
        if (kotlin.math.abs(plan.estimatedDurationSeconds - target).toDouble() / target > 0.5) {
            warnings += PlanIssue(PlanIssueCode.DURATION_MISMATCH, "The estimated length differs noticeably from your ${spec.targetDurationSeconds}-second target.")
        }

        if (spec.regenerateSlotId == null && written.isNotEmpty() && !isGrounded(plan, written.values, spec)) {
            error(PlanIssueCode.GENERIC_CONTENT, "The lyrics didn't use the details you gave, so they weren't applied.")
        }
        val name = spec.recipient.split(Regex("\\s+")).map { it.trim(',', '.') }.firstOrNull { it.length >= 2 }
        if (spec.regenerateSlotId == null && name != null && written.isNotEmpty() &&
            !Regex("(?<![\\p{L}\\p{N}])${Regex.escape(name.lowercase())}(?![\\p{L}\\p{N}])").containsMatchIn(written.values.joinToString(" ") { it.lyrics.lowercase() })
        ) {
            error(PlanIssueCode.DEDICATION_NOT_NAMED, "The lyrics never mention $name, so they weren't applied.")
        }
        return PlanValidation(errors, warnings)
    }

    /** Sections the plan is allowed to write, keyed by slot id (locked and instrumental slots excluded). */
    fun writtenSections(plan: SongwritingPlan, spec: SongSpecification): Map<String, PlanSection> {
        val writable = spec.writableSlots.map { it.id }.toSet()
        return plan.sections.filter { it.slotId in writable }.associateBy { it.slotId }
    }

    private fun isRepetitive(section: PlanSection, lines: List<String>): Boolean {
        val words = section.lyrics.lowercase().split(Regex("[^\\p{L}\\p{N}']+")).filter { it.isNotBlank() }
        var run = 1
        for (i in 1 until words.size) {
            run = if (words[i] == words[i - 1]) run + 1 else 1
            if (run > MAX_SAME_WORD_RUN) return true
        }
        if (section.type in REPEATING_TYPES || lines.size < 4) return false
        return lines.map(::normalise).distinct().size.toDouble() / lines.size < MIN_DISTINCT_LINE_RATIO
    }

    /**
     * The plan must visibly use what the user wrote: the dedication's name when one was given, and
     * some of the distinctive words from the story or message (in the lyrics, title or summary).
     */
    private fun isGrounded(plan: SongwritingPlan, written: Collection<PlanSection>, spec: SongSpecification): Boolean {
        val output = normalise((written.map { it.lyrics } + plan.title + plan.summary).joinToString(" "))
        val recipient = spec.recipient.split(Regex("\\s+")).firstOrNull { it.length >= 2 }?.let(::normalise)
        if (recipient != null && recipient !in output) return false
        val distinctive = normalise("${spec.story} ${spec.desiredMessage}").split(' ')
            .filter { it.length >= 4 && it !in STOPWORDS }.toSet()
        if (distinctive.isEmpty()) return true
        val needed = if (distinctive.size >= 6) 2 else 1
        return distinctive.count { word -> Regex("\\b${Regex.escape(word)}\\b").containsMatchIn(output) } >= needed
    }

    private fun lyricLines(lyrics: String) = lyrics.lines().map { it.trim() }.filter { it.isNotEmpty() }

    private fun normalise(text: String) = text.lowercase().replace(Regex("[^\\p{L}\\p{N}\\s']"), " ").replace(Regex("\\s+"), " ").trim()
}
