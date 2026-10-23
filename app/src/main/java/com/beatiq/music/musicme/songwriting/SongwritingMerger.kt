package com.beatiq.music.musicme.songwriting

import com.beatiq.music.musicme.model.MusicMeDraft
import com.beatiq.music.musicme.model.MusicMeSongSection
import com.beatiq.music.musicme.model.MusicMeSongwritingState
import com.beatiq.music.musicme.model.MusicMeSongwritingSummary
import com.beatiq.music.musicme.model.SectionOrigin

/** Applies a validated plan to a draft without ever touching sections the user wrote or edited. */
object SongwritingMerger {
    /**
     * [requested] is the draft's sections when the request was sent. A section the user changed while
     * generation was running keeps their text, as does every section the plan wasn't asked to write.
     */
    fun apply(draft: MusicMeDraft, plan: SongwritingPlan, spec: SongSpecification, requested: List<MusicMeSongSection>): MusicMeDraft {
        val written = SongwritingPlanValidator.writtenSections(plan, spec)
        val atRequest = requested.associateBy { it.id }
        val sections = draft.lyrics.sections.map { section ->
            val unchangedSinceRequest = atRequest[section.id]?.lyrics == section.lyrics
            if (unchangedSinceRequest) merge(section, written[section.id]) else section
        }
        return draft.copy(
            lyrics = draft.lyrics.copy(
                // The user's own title always wins; the generated one only fills a blank.
                title = draft.lyrics.title.ifBlank { draft.input.title.ifBlank { plan.title } },
                sections = sections,
            ),
            songwriting = MusicMeSongwritingState(lastPlan = summaryOf(plan), pendingJob = null),
        )
    }

    private fun merge(section: MusicMeSongSection, generated: PlanSection?): MusicMeSongSection = when {
        generated == null -> section
        else -> section.copy(lyrics = generated.lyrics.trim(), origin = SectionOrigin.AI, performanceNotes = generated.performanceNotes.trim())
    }

    fun summaryOf(plan: SongwritingPlan) = MusicMeSongwritingSummary(
        contractVersion = plan.contractVersion,
        title = plan.title,
        summary = plan.summary,
        styleGuidance = listOf(plan.style.genres.joinToString(" / "), plan.style.era, plan.style.production).filter { it.isNotBlank() }.joinToString(" • "),
        arrangementGuidance = listOf(plan.style.arrangement, plan.style.instrumentation).filter { it.isNotBlank() }.joinToString(" • "),
        tempoBpm = plan.tempoBpm,
        estimatedDurationSeconds = plan.estimatedDurationSeconds,
        vocalInstructions = plan.vocalInstructions,
        pronunciationNotes = plan.pronunciationNotes,
        language = plan.language,
        modelId = plan.metadata.modelId,
        modelVersion = plan.metadata.modelVersion,
        engine = plan.metadata.engine,
        requestId = plan.metadata.requestId,
        generatedAt = plan.metadata.generatedAt,
    )
}
