package com.beatiq.music.musicme.domain

import com.beatiq.music.musicme.data.MusicMeCatalog
import com.beatiq.music.musicme.model.MusicMeDraft
import com.beatiq.music.musicme.model.MusicMeInput
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Decides when a new creation is worth keeping and how untitled creations are named. */
object MusicMeDraftPolicy {
    /** Name stored by earlier builds for every untitled draft; still present in saved data. */
    const val LEGACY_UNTITLED_NAME = "Untitled creation"

    /** True once the user has given the draft something of their own: text, a voice note or lyrics. */
    fun hasMeaningfulContent(draft: MusicMeDraft): Boolean {
        val input = draft.input
        return listOf(
            input.title,
            input.story,
            input.recipient,
            input.desiredMessage,
            input.emotionalContext,
            input.pronunciationNotes,
            draft.lyrics.title,
        ).any { it.isNotBlank() } ||
            draft.recording != null ||
            draft.lyrics.sections.any { it.lyrics.isNotBlank() }
    }

    /**
     * Customisation needs something to build on: the story, the desired message, or a voice note whose
     * audio file is actually present ([recordingAvailable]). Whitespace-only text never counts, and the
     * same words never need to be entered in two fields.
     */
    fun canContinueToCustomisation(draft: MusicMeDraft, recordingAvailable: Boolean): Boolean =
        draft.input.story.isNotBlank() ||
            draft.input.desiredMessage.isNotBlank() ||
            (draft.recording != null && draft.recording.durationMs > 0 && recordingAvailable)

    /**
     * A draft that is not yet in Recent Creations is persisted only after an explicit save, a direct
     * customisation by the user, or once it has meaningful content. Automatic defaults never count.
     */
    fun shouldPersist(alreadyPersisted: Boolean, userCustomisation: Boolean, draft: MusicMeDraft): Boolean =
        alreadyPersisted || userCustomisation || hasMeaningfulContent(draft)

    /** e.g. "Birthday song for Ada" or "Love Declaration song · 20 Oct 14:32". */
    fun defaultName(input: MusicMeInput, createdAt: Long, locale: Locale = Locale.getDefault()): String {
        input.title.trim().takeIf { it.isNotEmpty() }?.let { return it }
        val base = occasionSongLabel(input.occasionId)
        val recipient = input.recipient.trim()
        return if (recipient.isNotEmpty()) {
            "$base for $recipient"
        } else {
            "$base · ${SimpleDateFormat("d MMM HH:mm", locale).format(Date(createdAt))}"
        }
    }

    /** Appends " (2)", " (3)"… when [name] is already used by another creation. */
    fun uniqueName(name: String, takenNames: Collection<String>): String {
        if (name !in takenNames) return name
        var n = 2
        while ("$name ($n)" in takenNames) n++
        return "$name ($n)"
    }

    /** Name to show for a stored draft; legacy "Untitled creation" drafts get a descriptive name without rewriting data. */
    fun displayName(draft: MusicMeDraft, locale: Locale = Locale.getDefault()): String =
        draft.name.trim().takeUnless { it.isEmpty() || it == LEGACY_UNTITLED_NAME }
            ?: defaultName(draft.input, draft.createdAt, locale)

    private fun occasionSongLabel(occasionId: String): String {
        if (occasionId == "personal_story") return "Story song"
        val label = MusicMeCatalog.occasions.firstOrNull { it.id == occasionId }?.label ?: return "New song"
        return if (label.contains("song", ignoreCase = true) || label.contains("jingle", ignoreCase = true)) {
            label
        } else {
            "$label song"
        }
    }
}
