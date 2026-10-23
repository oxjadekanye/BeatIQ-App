package com.beatiq.music.musicme

import com.beatiq.music.musicme.audiogeneration.MusicMeSongViewModel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.beatiq.music.musicme.data.MusicMeCatalog
import com.beatiq.music.musicme.domain.MusicMeDraftPolicy
import com.beatiq.music.musicme.model.MusicMeDraft
import com.beatiq.music.musicme.model.MusicMeLyricsDraft
import com.beatiq.music.musicme.model.MusicMeSongSection
import com.beatiq.music.musicme.model.OptionCategory
import com.beatiq.music.musicme.model.SectionOrigin
import com.beatiq.music.musicme.songwriting.SectionType
import androidx.compose.ui.platform.testTag
import com.beatiq.music.ui.StudioCyan
import java.text.DateFormat
import java.util.Date

@Composable
internal fun LyricsStudio(state: MusicMeUiState, viewModel: MusicMeViewModel) {
    val draft = state.draft ?: return
    var addMenu by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().imePadding().testTag("musicme_lyrics_list"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { StudioTopBar("Lyrics Studio", state.saveStatus(), viewModel::back, { viewModel.saveNow("Lyrics saved") }) }
        item {
            SectionTitle("Write it in your voice", "${draft.lyrics.sections.size}-section structure • every section stays editable")
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(draft.lyrics.title, { value -> viewModel.updateLyrics { it.copy(title = value) }; viewModel.updateInput { it.copy(title = value) } }, Modifier.fillMaxWidth(), label = { Text("Song title") }, singleLine = true)
        }
        item { SongwritingPanel(state, draft, viewModel) }
        items(draft.lyrics.sections, key = { it.id }) { section ->
            LyricSectionEditor(
                section, draft.lyrics, state.songwriting, viewModel,
                mock = draft.songwriting?.lastPlan?.engine == MOCK_ENGINE || state.songwriting.engine == MOCK_ENGINE,
            )
        }
        item {
            Box {
                OutlinedButton(onClick = { addMenu = true }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Add section") }
                DropdownMenu(addMenu, { addMenu = false }) {
                    listOf("Intro", "Verse", "Pre-Chorus", "Chorus", "Bridge", "Rap Verse", "Spoken Interlude", "Instrumental Break", "Outro").forEach { type ->
                        DropdownMenuItem({ Text(type) }, { viewModel.addLyricSection(type); addMenu = false })
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { viewModel.saveNow("Lyrics saved") }, Modifier.weight(1f)) { Icon(Icons.Default.Save, null); Text("Save") }
                Button(onClick = { viewModel.navigate(MusicMeDestination.REVIEW) }, Modifier.weight(1f)) { Text("Review") }
            }
        }
    }
}

@Composable
private fun LyricSectionEditor(section: MusicMeSongSection, lyrics: MusicMeLyricsDraft, songwriting: SongwritingUiState, viewModel: MusicMeViewModel, mock: Boolean) {
    val index = lyrics.sections.indexOfFirst { it.id == section.id }
    val sung = (SectionType.fromWire(section.type) ?: SectionType.fromLabel(section.label)).sung
    val rewriting = songwriting.running && songwriting.targetSectionId == section.id
    var confirmRewrite by remember { mutableStateOf(false) }
    if (confirmRewrite) {
        AlertDialog(
            onDismissRequest = { confirmRewrite = false },
            title = { Text("Rewrite “${section.label}”?") },
            text = { Text("Your lyrics for this section will be replaced by a new version. Other sections stay as they are, and you can undo afterwards.") },
            confirmButton = { TextButton(onClick = { confirmRewrite = false; viewModel.regenerateSection(section.id) }) { Text("Rewrite") } },
            dismissButton = { TextButton(onClick = { confirmRewrite = false }) { Text("Keep mine") } },
        )
    }
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.testTag(MusicMeTestTags.lyricSection(section.id))) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(section.label, { value -> viewModel.updateLyrics { draft -> draft.copy(sections = draft.sections.map { if (it.id == section.id) it.copy(label = value) else it }) } }, Modifier.weight(1f), label = { Text("Section ${index + 1}") }, singleLine = true)
                IconButton(enabled = index > 0, onClick = { viewModel.moveLyricSection(section.id, -1) }) { Icon(Icons.Default.KeyboardArrowUp, "Move up") }
                IconButton(enabled = index < lyrics.sections.lastIndex, onClick = { viewModel.moveLyricSection(section.id, 1) }) { Icon(Icons.Default.KeyboardArrowDown, "Move down") }
                IconButton(enabled = lyrics.sections.size > 1, onClick = { viewModel.removeLyricSection(section.id) }) { Icon(Icons.Default.DeleteOutline, "Remove section") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(originLabel(section, mock), style = MaterialTheme.typography.labelMedium, color = StudioCyan, modifier = Modifier.weight(1f))
                if (songwriting.configured && sung) {
                    IconButton(
                        enabled = !songwriting.running,
                        onClick = {
                            val ownWords = section.lyrics.isNotBlank() && section.origin != SectionOrigin.AI
                            if (ownWords) confirmRewrite = true else viewModel.regenerateSection(section.id)
                        },
                        modifier = Modifier.testTag(MusicMeTestTags.rewriteSection(section.id)),
                    ) { Icon(Icons.Default.Autorenew, "Rewrite ${section.label} with AI") }
                }
            }
            if (rewriting) LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 6.dp))
            OutlinedTextField(
                section.lyrics, { value -> viewModel.editSectionLyrics(section.id, value) },
                Modifier.fillMaxWidth().heightIn(min = 110.dp), label = { Text("Lyrics") }, placeholder = { Text(if (sung) "Write this section…" else "Instrumental section — no lyrics needed") },
                supportingText = { Text("${section.lyrics.trim().split(Regex("\\s+")).count { it.isNotBlank() }} words") },
            )
            if (section.performanceNotes.isNotBlank()) {
                Text("Performance: ${section.performanceNotes}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun originLabel(section: MusicMeSongSection, mock: Boolean): String = when {
    section.lyrics.isBlank() -> "Empty"
    section.origin == SectionOrigin.AI && mock -> "Mock placeholder (not AI)"
    section.origin == SectionOrigin.AI -> "AI draft"
    section.origin == SectionOrigin.AI_EDITED -> "Edited by you • kept when regenerating"
    else -> "Your lyrics • kept when regenerating"
}

@Composable
internal fun DraftsScreen(state: MusicMeUiState, viewModel: MusicMeViewModel) {
    var pendingDelete by remember { mutableStateOf<MusicMeDraft?>(null) }
    pendingDelete?.let { draft ->
        AlertDialog(onDismissRequest = { pendingDelete = null }, title = { Text("Delete “${MusicMeDraftPolicy.displayName(draft)}”?") }, text = { Text("This removes the local draft and its attached recording. This cannot be undone.") }, confirmButton = { TextButton(onClick = { viewModel.delete(draft.id); pendingDelete = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } })
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { StudioTopBar("My Drafts", "${state.drafts.size} saved locally", viewModel::back) }
        item { Button(onClick = { viewModel.newDraft() }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Text("New creation") } }
        if (state.drafts.isEmpty()) item { EmptyCollection("No drafts yet", "Your first saved story will appear here.", Icons.Default.FolderOpen) }
        items(state.drafts, key = { it.id }) { draft ->
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth().clickable { viewModel.openDraft(draft.id) }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.MusicNote, null, tint = StudioCyan)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(MusicMeDraftPolicy.displayName(draft), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${draft.status.name} • ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(draft.modifiedAt))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { viewModel.duplicate(draft.id) }) { Icon(Icons.Default.ContentCopy, "Duplicate draft") }
                    IconButton(onClick = { pendingDelete = draft }) { Icon(Icons.Default.DeleteOutline, "Delete draft") }
                }
            }
        }
    }
}

@Composable
internal fun PresetsScreen(state: MusicMeUiState, viewModel: MusicMeViewModel) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { StudioTopBar("Saved Presets", "${state.presets.size} studio setups", viewModel::back) }
        if (state.presets.isEmpty()) item { EmptyCollection("No presets saved", "Save any Advanced Studio setup to reuse it here.", Icons.Default.Tune) }
        items(state.presets, key = { it.id }) { preset ->
            Surface(onClick = {
                viewModel.startFromPreset(preset.id)
            }, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Tune, null, tint = StudioCyan); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) { Text(preset.name, fontWeight = FontWeight.Bold); Text("${preset.configuration.genres.primaryGenreId.replace('_', ' ')} • ${preset.configuration.bpm} BPM • ${preset.configuration.controls.durationSeconds}s", style = MaterialTheme.typography.bodySmall) }
                    Icon(Icons.Default.ChevronRight, null)
                }
            }
        }
    }
}

@Composable
internal fun ReviewScreen(state: MusicMeUiState, viewModel: MusicMeViewModel, songViewModel: MusicMeSongViewModel) {
    val song by songViewModel.state.collectAsState()
    val draft = state.draft ?: return
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { StudioTopBar("Creation Review", state.saveStatus("Draft saved"), viewModel::back, { viewModel.saveNow() }) }
        item { ReviewSummary(draft) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { viewModel.navigate(MusicMeDestination.ADVANCED) }, Modifier.weight(1f)) { Icon(Icons.Default.Tune, null); Text("Customise") }
                OutlinedButton(onClick = { viewModel.navigate(MusicMeDestination.LYRICS) }, Modifier.weight(1f)) { Icon(Icons.Default.EditNote, null); Text("Lyrics") }
            }
        }
        item { SongCreationSection(draft, song, songViewModel) }
    }
}

@Composable
internal fun ReviewSummary(draft: MusicMeDraft) {
    val config = draft.configuration
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle(MusicMeDraftPolicy.displayName(draft), draft.input.recipient.takeIf { it.isNotBlank() }?.let { "For $it" } ?: "Personal creation")
            SummaryRow("Mode", config.mode.name.lowercase().replaceFirstChar(Char::uppercase))
            SummaryRow("Genre", (listOf(config.genres.primaryGenreId) + config.genres.secondaryGenreIds).joinToString { optionLabel(OptionCategory.GENRE, it) })
            SummaryRow("Mood", config.selections[OptionCategory.MOOD].orEmpty().joinToString { optionLabel(OptionCategory.MOOD, it) })
            SummaryRow("Voice", config.selections[OptionCategory.VOCAL_ARRANGEMENT].orEmpty().joinToString { optionLabel(OptionCategory.VOCAL_ARRANGEMENT, it) })
            SummaryRow("Tempo", "${config.bpm} BPM • ${config.selections[OptionCategory.TEMPO].orEmpty().joinToString { optionLabel(OptionCategory.TEMPO, it) }}")
            SummaryRow("Arrangement", "${config.arrangementName} • ${config.sections.size} sections")
            SummaryRow("Language", config.selections[OptionCategory.LANGUAGE].orEmpty().joinToString { optionLabel(OptionCategory.LANGUAGE, it) })
            SummaryRow("Duration", "${config.controls.durationSeconds} seconds")
            SummaryRow("Lyrics", "${draft.lyrics.sections.size} editable sections")
            SummaryRow("Voice note", if (draft.recording == null) "Not attached" else "Attached • saved on this device")
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) { Text(label, Modifier.width(102.dp), color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value.ifBlank { "Not selected" }, Modifier.weight(1f), fontWeight = FontWeight.Medium) }
}

@Composable
private fun EmptyCollection(title: String, message: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f)) {
        Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(icon, null, Modifier.size(40.dp), tint = StudioCyan); Spacer(Modifier.height(10.dp)); Text(title, fontWeight = FontWeight.Bold); Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

private fun optionLabel(category: OptionCategory, id: String) = MusicMeCatalog.all[category]?.firstOrNull { it.id == id }?.label ?: id.replace('_', ' ')
