package com.beatiq.music.musicme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.beatiq.music.musicme.model.MusicMeDraft
import com.beatiq.music.musicme.model.MusicMeSongwritingSummary
import com.beatiq.music.musicme.model.SectionOrigin
import com.beatiq.music.musicme.songwriting.Severity
import com.beatiq.music.musicme.songwriting.SongSpecificationBuilder
import com.beatiq.music.musicme.songwriting.SongwritingPhase
import com.beatiq.music.ui.StudioCyan

/** Lyrics Studio's songwriting controls: honest "not connected" state, issues, progress, errors and results. */
@Composable
internal fun SongwritingPanel(state: MusicMeUiState, draft: MusicMeDraft, viewModel: MusicMeViewModel) {
    val sw = state.songwriting
    sw.consentRequest?.let {
        AlertDialog(
            onDismissRequest = viewModel::dismissSongwritingConsent,
            title = { Text(MusicMeCopy.SONGWRITING_CONSENT_TITLE) },
            text = { Text(MusicMeCopy.SONGWRITING_CONSENT_BODY) },
            confirmButton = { TextButton(onClick = viewModel::grantSongwritingConsent) { Text("Send and write") } },
            dismissButton = { TextButton(onClick = viewModel::dismissSongwritingConsent) { Text("Not now") } },
        )
    }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .6f), modifier = Modifier.testTag(MusicMeTestTags.SONGWRITING_PANEL)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoAwesome, null, tint = StudioCyan)
                Spacer(Modifier.width(10.dp))
                val mock = sw.engine == MOCK_ENGINE || draft.songwriting?.lastPlan?.engine == MOCK_ENGINE
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            !sw.configured -> MusicMeCopy.AI_LYRICS_TITLE
                            mock -> "Songwriting (development mock)"
                            else -> MusicMeCopy.SONGWRITING_TITLE
                        },
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        when {
                            !sw.configured -> MusicMeCopy.AI_LYRICS_BODY
                            mock -> "Placeholder lyrics that rearrange your own words. No AI model is connected."
                            else -> MusicMeCopy.SONGWRITING_BODY
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (!sw.configured) return@Column
            if (sw.engine == MOCK_ENGINE || draft.songwriting?.lastPlan?.engine == MOCK_ENGINE) {
                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.testTag(MusicMeTestTags.MOCK_ENGINE_BANNER)) {
                    Text(
                        "DEVELOPMENT MOCK — ${MusicMeCopy.SONGWRITING_MOCK_ENGINE}",
                        Modifier.padding(10.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }

            val activeHere = sw.draftId == draft.id
            val running = sw.running && activeHere
            val issues = remember(draft, sw.running) { viewModel.songwritingIssues(draft) }
            issues.forEach { issue -> IssueRow(issue.message, blocking = issue.severity == Severity.BLOCKING) }
            val plannedStructure = remember(draft) { SongSpecificationBuilder.structureFor(draft).map { it.label } }
            if (!running && plannedStructure != draft.lyrics.sections.map { it.label }) {
                IssueRow("Lyrics will follow your ${draft.configuration.arrangementName} arrangement: ${plannedStructure.joinToString(", ")}.", blocking = false)
            }

            if (running) {
                Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                    Text(phaseText(sw.phase, sw.targetSectionId != null), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.padding(top = 6.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                TextButton(onClick = viewModel::cancelSongwriting, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
            } else {
                val hasGenerated = draft.lyrics.sections.any { it.origin == SectionOrigin.AI }
                Button(
                    onClick = viewModel::generateLyrics,
                    enabled = !sw.running && issues.none { it.severity == Severity.BLOCKING },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(MusicMeTestTags.GENERATE_LYRICS),
                ) {
                    Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text(if (hasGenerated) "Rewrite unedited sections" else "Generate lyrics")
                }
            }

            sw.error?.takeIf { activeHere }?.let { error ->
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.testTag(MusicMeTestTags.SONGWRITING_ERROR)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp).semantics { liveRegion = LiveRegionMode.Assertive }) {
                        Text(error.message, color = MaterialTheme.colorScheme.onErrorContainer)
                        Row {
                            if (error.retryable) {
                                TextButton(onClick = { viewModel.dismissSongwritingError(); viewModel.generateLyrics() }) {
                                    Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(4.dp)); Text("Try again")
                                }
                            }
                            TextButton(onClick = viewModel::dismissSongwritingError) { Text("Dismiss") }
                        }
                    }
                }
            }
            if (activeHere) sw.warnings.forEach { warning -> IssueRow(warning, blocking = false) }
            if (!running && sw.lyricsUndo?.draftId == draft.id) {
                OutlinedButton(onClick = viewModel::undoSongwriting, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Undo, null); Spacer(Modifier.width(6.dp)); Text("Undo last lyric change")
                }
            }
            draft.songwriting?.lastPlan?.let { PlanSummary(it) }
        }
    }
}

@Composable
private fun IssueRow(message: String, blocking: Boolean) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            if (blocking) Icons.Default.ErrorOutline else Icons.Default.Info, null, Modifier.size(18.dp),
            tint = if (blocking) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text(message, style = MaterialTheme.typography.bodySmall, color = if (blocking) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PlanSummary(plan: MusicMeSongwritingSummary) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(if (expanded) "Hide song plan" else "Show song plan: ${plan.title}")
        }
        if (expanded) {
            PlanLine("Summary", plan.summary)
            PlanLine("Style", plan.styleGuidance)
            PlanLine("Arrangement", plan.arrangementGuidance)
            PlanLine("Tempo and length", "${plan.tempoBpm} BPM • about ${plan.estimatedDurationSeconds / 60}:${"%02d".format(plan.estimatedDurationSeconds % 60)}")
            PlanLine("Vocal performance", plan.vocalInstructions)
            PlanLine("Pronunciation", plan.pronunciationNotes)
            PlanLine("Written by", "${plan.modelId}${if (plan.modelVersion.isNotBlank()) " ${plan.modelVersion}" else ""} (${plan.engine.replace('_', ' ')})")
        }
    }
}

@Composable
private fun PlanLine(label: String, value: String) {
    if (value.isBlank()) return
    Column { Text(label, style = MaterialTheme.typography.labelMedium, color = StudioCyan); Text(value, style = MaterialTheme.typography.bodySmall) }
}

internal fun phaseText(phase: SongwritingPhase?, section: Boolean): String = when (phase) {
    SongwritingPhase.SUBMITTING, null -> "Sending your song details…"
    SongwritingPhase.RETRYING -> "Connection problem. Retrying…"
    SongwritingPhase.QUEUED -> "Waiting for the songwriter…"
    SongwritingPhase.WRITING -> if (section) "Rewriting the section…" else "Writing your lyrics…"
    SongwritingPhase.CHECKING -> "Checking the lyrics…"
    SongwritingPhase.RESUMING -> "Resuming your earlier request…"
}
