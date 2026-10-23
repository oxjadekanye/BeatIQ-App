package com.beatiq.music.musicme

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.beatiq.music.musicme.audio.MusicMeVoiceRecorder
import com.beatiq.music.musicme.audio.RecorderState
import com.beatiq.music.musicme.data.MusicMeCatalog
import com.beatiq.music.musicme.model.MusicMeInput
import com.beatiq.music.ui.StudioCyan
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InputStudio(state: MusicMeUiState, viewModel: MusicMeViewModel) {
    val draft = state.draft ?: return
    var inputMode by rememberSaveable(draft.id) { mutableIntStateOf(if (state.preferVoiceInput) 1 else 0) }
    var confirmClear by remember { mutableStateOf(false) }
    val canContinue = viewModel.canContinueToCustomisation(draft)
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear your story?") },
            text = { Text("The story text is removed from this draft. Your title, details, voice note and lyrics are kept. You can undo until you open another creation.") },
            confirmButton = { TextButton(onClick = { viewModel.clearStory(); confirmClear = false }) { Text("Clear story") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().testTag(MusicMeTestTags.INPUT_LIST),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { StudioTopBar("Input Studio", state.saveStatus(), viewModel::back, { viewModel.saveNow() }) }
            item {
                Text("Bring us the moment", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                Text("Write freely or record a voice note. ${MusicMeCopy.STORAGE_BADGE}.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                PrimaryTabRow(selectedTabIndex = inputMode, containerColor = MaterialTheme.colorScheme.background) {
                    Tab(selected = inputMode == 0, onClick = { inputMode = 0 }, text = { Text("Write") }, icon = { Icon(Icons.Default.EditNote, null) })
                    Tab(selected = inputMode == 1, onClick = { inputMode = 1 }, text = { Text("Record") }, icon = { Icon(Icons.Default.Mic, null) })
                }
            }
            if (inputMode == 0) {
                item {
                    OutlinedTextField(
                        value = draft.input.story,
                        onValueChange = { value -> viewModel.updateInput { it.copy(story = value) } },
                        modifier = Modifier.fillMaxWidth().testTag(MusicMeTestTags.STORY_FIELD),
                        minLines = 4,
                        maxLines = 12,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        label = { Text("Your story, poem or creative direction") },
                        placeholder = { Text("Describe the people, moment, feelings and message you want the song to carry…") },
                        shape = RoundedCornerShape(18.dp),
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${draft.input.story.wordCount()} words", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).padding(start = 4.dp))
                        TextButton(enabled = draft.input.story.isNotBlank(), onClick = { confirmClear = true }) { Icon(Icons.Default.DeleteSweep, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Clear") }
                        TextButton(enabled = state.canUndoClear, onClick = viewModel::undoClearStory) { Icon(Icons.AutoMirrored.Filled.Undo, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Undo") }
                    }
                }
            } else {
                item { VoiceRecordingPanel(draft.recording, viewModel) }
                item {
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f)) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                            Icon(Icons.Default.Info, null, tint = StudioCyan)
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(MusicMeCopy.TRANSCRIPTION_TITLE, fontWeight = FontWeight.Bold)
                                Text(MusicMeCopy.TRANSCRIPTION_BODY, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            item { MetadataFields(draft.input, viewModel) }
            state.recommendationExplanation?.let { explanation ->
                item { Text("Studio suggestion: $explanation", style = MaterialTheme.typography.bodySmall, color = StudioCyan) }
            }
        }
        InputActionBar(
            canContinue = canContinue,
            onSave = { viewModel.saveNow() },
            onContinue = viewModel::continueToCustomisation,
        )
    }
}

/** Pinned above the keyboard and bottom navigation so saving and continuing never require scrolling. */
@Composable
private fun InputActionBar(canContinue: Boolean, onSave: () -> Unit, onContinue: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth().testTag(MusicMeTestTags.INPUT_ACTIONS)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            if (!canContinue) {
                Text("Add your story, a desired message or a voice note to continue.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onSave, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Icon(Icons.Default.Save, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Save", maxLines = 1)
                }
                Button(onClick = onContinue, enabled = canContinue, modifier = Modifier.weight(1.4f).heightIn(min = 48.dp)) {
                    Text("Customise", maxLines = 1); Spacer(Modifier.width(6.dp)); Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MetadataFields(input: MusicMeInput, viewModel: MusicMeViewModel) {
    var occasionOpen by remember { mutableStateOf(false) }
    val hasExtraDetails = input.emotionalContext.isNotBlank() || input.pronunciationNotes.isNotBlank()
    var showMore by rememberSaveable { mutableStateOf(hasExtraDetails) }
    val singleLineText = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next)
    // Multi-line fields start compact, grow with the text, then scroll inside the field.
    val paragraph = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle("Song details", "Optional context makes recommendations more meaningful")
        OutlinedTextField(
            input.title, { value -> viewModel.updateInput { it.copy(title = value) } },
            Modifier.fillMaxWidth().testTag(MusicMeTestTags.TITLE_FIELD),
            label = { Text("Song title") }, singleLine = true, keyboardOptions = singleLineText,
        )
        OutlinedTextField(
            input.recipient, { value -> viewModel.updateInput { it.copy(recipient = value) } },
            Modifier.fillMaxWidth().testTag(MusicMeTestTags.RECIPIENT_FIELD),
            label = { Text("Dedication / recipient") }, singleLine = true, keyboardOptions = singleLineText,
        )
        ExposedDropdownMenuBox(expanded = occasionOpen, onExpandedChange = { occasionOpen = it }) {
            OutlinedTextField(
                MusicMeCatalog.occasions.firstOrNull { it.id == input.occasionId }?.label ?: "Personal Story", {}, readOnly = true,
                label = { Text("Occasion") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(occasionOpen) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth().testTag(MusicMeTestTags.OCCASION_FIELD),
            )
            ExposedDropdownMenu(expanded = occasionOpen, onDismissRequest = { occasionOpen = false }) {
                MusicMeCatalog.occasions.forEach { option -> DropdownMenuItem(text = { Text(option.label) }, onClick = { viewModel.updateInput { it.copy(occasionId = option.id) }; occasionOpen = false }) }
            }
        }
        OutlinedTextField(
            input.desiredMessage, { value -> viewModel.updateInput { it.copy(desiredMessage = value) } },
            Modifier.fillMaxWidth().testTag(MusicMeTestTags.MESSAGE_FIELD),
            label = { Text("Desired message") },
            supportingText = { Text("Enough on its own to continue — no need to repeat your story") },
            maxLines = 6, keyboardOptions = paragraph,
        )
        TextButton(onClick = { showMore = !showMore }, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(if (showMore) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            Spacer(Modifier.width(6.dp))
            Text(if (showMore) "Fewer details" else "More details: emotion, pronunciation")
        }
        if (showMore || hasExtraDetails) {
            OutlinedTextField(
                input.emotionalContext, { value -> viewModel.updateInput { it.copy(emotionalContext = value) } },
                Modifier.fillMaxWidth().testTag(MusicMeTestTags.EMOTION_FIELD),
                label = { Text("Emotional context") }, maxLines = 6, keyboardOptions = paragraph,
            )
            OutlinedTextField(
                input.pronunciationNotes, { value -> viewModel.updateInput { it.copy(pronunciationNotes = value) } },
                Modifier.fillMaxWidth().testTag(MusicMeTestTags.PRONUNCIATION_FIELD),
                label = { Text("Pronunciation guidance (optional)") }, supportingText = { Text("Example: Adé — ah-DEH") },
                maxLines = 4, keyboardOptions = paragraph,
            )
        }
    }
}

@Composable
private fun VoiceRecordingPanel(recording: com.beatiq.music.musicme.model.MusicMeVoiceRecording?, viewModel: MusicMeViewModel) {
    val context = LocalContext.current
    val recorder = remember { MusicMeVoiceRecorder(context, viewModel.repository) }
    val recorderState by recorder.state.collectAsState()
    val error by recorder.error.collectAsState()
    var permissionDenied by remember { mutableStateOf(false) }
    var duration by remember { mutableLongStateOf(recording?.durationMs ?: 0L) }
    var amplitude by remember { mutableIntStateOf(0) }
    var pendingStart by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionDenied = !granted
        if (granted && pendingStart) { recorder.start(); pendingStart = false }
    }
    LaunchedEffect(recorderState) {
        while (recorderState == RecorderState.RECORDING || recorderState == RecorderState.PAUSED) {
            duration = recorder.durationMs(); amplitude = recorder.amplitude(); delay(100)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            if (recorder.state.value == RecorderState.RECORDING || recorder.state.value == RecorderState.PAUSED) recorder.stop()?.let(viewModel::attachRecording)
            else recorder.releaseAll()
        }
    }
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(when (recorderState) { RecorderState.RECORDING -> "Recording…"; RecorderState.PAUSED -> "Recording paused"; RecorderState.PLAYING -> "Playing your voice note"; else -> if (recording != null) "Voice note attached" else "Ready to record" }, fontWeight = FontWeight.Bold)
            Waveform(amplitude, active = recorderState == RecorderState.RECORDING || recorderState == RecorderState.PLAYING)
            Text(duration.formatDuration(), style = MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                when (recorderState) {
                    RecorderState.RECORDING -> {
                        FilledTonalIconButton(onClick = recorder::pause) { Icon(Icons.Default.Pause, "Pause recording") }
                        FilledIconButton(onClick = { recorder.stop()?.let { viewModel.attachRecording(it); duration = it.durationMs } }) { Icon(Icons.Default.Stop, "Stop recording") }
                    }
                    RecorderState.PAUSED -> {
                        FilledTonalIconButton(onClick = recorder::resume) { Icon(Icons.Default.PlayArrow, "Resume recording") }
                        FilledIconButton(onClick = { recorder.stop()?.let { viewModel.attachRecording(it); duration = it.durationMs } }) { Icon(Icons.Default.Stop, "Stop recording") }
                    }
                    RecorderState.PLAYING -> FilledIconButton(onClick = recorder::stopPlayback) { Icon(Icons.Default.Stop, "Stop playback") }
                    else -> if (recording == null) {
                        FilledIconButton(modifier = Modifier.size(64.dp), onClick = {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) recorder.start()
                            else { pendingStart = true; launcher.launch(Manifest.permission.RECORD_AUDIO) }
                        }) { Icon(Icons.Default.Mic, "Start recording", Modifier.size(30.dp)) }
                    } else {
                        FilledIconButton(onClick = { recorder.play(recording) }) { Icon(Icons.Default.PlayArrow, "Play recording") }
                        OutlinedIconButton(onClick = { recorder.delete(recording); viewModel.attachRecording(null); duration = 0 }) { Icon(Icons.Default.Delete, "Delete recording") }
                        OutlinedButton(onClick = { recorder.delete(recording); viewModel.attachRecording(null); duration = 0 }) { Text("Re-record") }
                    }
                }
            }
            if (permissionDenied) Text("Microphone permission was denied. You can enable it in Android settings or continue with text.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun Waveform(amplitude: Int, active: Boolean) {
    val normalized = (amplitude / 32767f).coerceIn(0f, 1f)
    Canvas(Modifier.fillMaxWidth().height(58.dp)) {
        val bars = 36
        val spacing = size.width / bars
        repeat(bars) { index ->
            val patterned = .18f + ((index * 17 % 13) / 13f) * .82f
            val strength = if (active) (.22f + normalized * .78f) * patterned else .14f * patterned
            val half = size.height * strength / 2
            drawLine(StudioCyan.copy(alpha = if (active) .9f else .35f), Offset(index * spacing + spacing / 2, size.height / 2 - half), Offset(index * spacing + spacing / 2, size.height / 2 + half), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

@Composable
internal fun StudioTopBar(title: String, status: String? = null, onBack: () -> Unit, onSave: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); status?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = StudioCyan) } }
        onSave?.let { IconButton(onClick = it) { Icon(Icons.Default.Save, "Save draft") } }
    }
}

private fun String.wordCount() = trim().split(Regex("\\s+")).count { it.isNotBlank() }
private fun Long.formatDuration(): String = "%02d:%02d".format(this / 60_000, (this / 1_000) % 60)
