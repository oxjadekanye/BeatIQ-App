@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.beatiq.music.musicme

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.beatiq.music.musicme.data.MusicMeCatalog
import com.beatiq.music.musicme.model.*
import com.beatiq.music.ui.StudioCyan
import kotlin.math.roundToInt

private data class GuidedStep(val title: String, val category: OptionCategory, val description: String)
private val guidedSteps = listOf(
    GuidedStep("Genre", OptionCategory.GENRE, "Choose the musical world for your story"),
    GuidedStep("Mood", OptionCategory.MOOD, "Select up to a few emotions; you can blend them"),
    GuidedStep("Voice", OptionCategory.VOCAL_ARRANGEMENT, "Choose how the vocal parts are arranged"),
    GuidedStep("Era", OptionCategory.ERA, "Give the production a time and character"),
    GuidedStep("Tempo", OptionCategory.TEMPO, "Set the pace, then fine-tune the BPM"),
    GuidedStep("Structure", OptionCategory.STRUCTURE, "Start from a proven arrangement"),
    GuidedStep("Review", OptionCategory.PRODUCTION_STYLE, "Check the complete direction before lyrics"),
)

@Composable
internal fun GuidedStudio(state: MusicMeUiState, viewModel: MusicMeViewModel) {
    val draft = state.draft ?: return
    var step by rememberSaveable { mutableIntStateOf(0) }
    val item = guidedSteps[step]
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            StudioTopBar("Guided Create", "Step ${step + 1} of ${guidedSteps.size}", viewModel::back, { viewModel.saveNow() })
            LinearProgressIndicator(progress = { (step + 1f) / guidedSteps.size }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(14.dp))
            Text(item.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
            Text(item.description, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (step == guidedSteps.lastIndex) {
            ReviewSummary(draft)
        } else {
            GuidedChoices(draft.configuration, item.category, viewModel, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { if (step > 0) step-- else viewModel.navigate(MusicMeDestination.INPUT) }, Modifier.weight(1f)) { Text("Back") }
            Button(onClick = { if (step < guidedSteps.lastIndex) step++ else viewModel.navigate(MusicMeDestination.REVIEW) }, Modifier.weight(1f)) { Text(if (step == guidedSteps.lastIndex) "Review" else "Next") }
        }
    }
}

@Composable
private fun GuidedChoices(configuration: MusicMeConfiguration, category: OptionCategory, viewModel: MusicMeViewModel, modifier: Modifier = Modifier) {
    var query by remember(category) { mutableStateOf("") }
    val options = MusicMeCatalog.search(category, query)
    LazyColumn(modifier, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { SearchField(query, { query = it }, "Search ${category.displayName()}") }
        if (category == OptionCategory.GENRE) {
            items(options, key = { it.id }) { option ->
                val primary = configuration.genres.primaryGenreId == option.id
                val secondary = option.id in configuration.genres.secondaryGenreIds
                OptionCard(option, primary || secondary, if (primary) "Primary • 70%" else if (secondary) "Secondary" else null) {
                    if (primary) Unit else if (!secondary) viewModel.setPrimaryGenre(option.id) else viewModel.toggleSecondaryGenre(option.id)
                }
            }
        } else {
            items(options, key = { it.id }) { option ->
                OptionCard(option, option.id in configuration.selections[category].orEmpty(), if (option.recommended) "Recommended" else null) {
                    viewModel.toggleSelection(category, option.id, option.multiSelect)
                }
            }
            if (category == OptionCategory.TEMPO) item { BpmControl(configuration.bpm, viewModel::setBpm) }
            if (category == OptionCategory.STRUCTURE) item { ArrangementPicker(configuration, viewModel) }
        }
    }
}

@Composable
internal fun AdvancedStudio(state: MusicMeUiState, viewModel: MusicMeViewModel) {
    val draft = state.draft ?: return
    var query by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf<OptionCategory?>(OptionCategory.GENRE) }
    var pendingReplace by remember { mutableStateOf<ConfigReplacement?>(null) }
    // Replacing every setting of a saved creation is confirmed first, and can be undone from the snackbar after.
    val requestReplace: (ConfigReplacement) -> Unit = { replacement ->
        if (state.draftPersisted || replacement == ConfigReplacement.RESET) pendingReplace = replacement else replacement.apply(viewModel)
    }
    pendingReplace?.let { replacement ->
        AlertDialog(
            onDismissRequest = { pendingReplace = null },
            title = { Text(replacement.title) },
            text = { Text("${replacement.description} Your story, details, lyrics and voice note are not changed, and you can undo right after.") },
            confirmButton = { TextButton(onClick = { replacement.apply(viewModel); pendingReplace = null }) { Text(replacement.confirm) } },
            dismissButton = { TextButton(onClick = { pendingReplace = null }) { Text("Cancel") } },
        )
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { StudioTopBar("Advanced Studio", state.saveStatus(), viewModel::back, { viewModel.saveNow() }) }
        item {
            SectionTitle("Shape every detail", "Curated choices map to typed future generation parameters")
            Spacer(Modifier.height(12.dp))
            SearchField(query, { query = it }, "Search the entire studio catalogue")
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { requestReplace(ConfigReplacement.QUICK_DEFAULTS) }, label = { Text("Quick defaults") }, leadingIcon = { Icon(Icons.Default.Bolt, null) })
                AssistChip(onClick = { viewModel.setMode(CreationMode.GUIDED); viewModel.navigate(MusicMeDestination.GUIDED) }, label = { Text("Guided") })
                AssistChip(onClick = { requestReplace(ConfigReplacement.SURPRISE) }, label = { Text("Surprise Me") }, leadingIcon = { Icon(Icons.Default.Casino, null) })
                AssistChip(onClick = { requestReplace(ConfigReplacement.RESET) }, label = { Text("Reset") }, leadingIcon = { Icon(Icons.Default.RestartAlt, null) })
            }
        }
        MusicMeCatalog.all.forEach { (category, unfiltered) ->
            val options = unfiltered.filter { query.isBlank() || it.label.contains(query, true) || it.description.contains(query, true) }
            if (options.isNotEmpty()) item(key = "header_${category.name}") {
                CategoryPanel(
                    category = category, options = options,
                    configuration = draft.configuration, expanded = expanded == category || query.isNotBlank(),
                    onExpand = { expanded = if (expanded == category) null else category }, viewModel = viewModel,
                )
            }
        }
        item { ArrangementEditor(draft.configuration, viewModel) }
        item { AdvancedControlsPanel(draft.configuration.controls, viewModel::updateControls) }
        item {
            OutlinedButton(
                onClick = { viewModel.navigate(MusicMeDestination.LYRICS) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(MusicMeTestTags.WRITE_LYRICS),
            ) { Icon(Icons.Default.EditNote, null); Spacer(Modifier.width(6.dp)); Text(if (state.songwriting.configured) "Write lyrics (manual or AI)" else "Write lyrics") }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { viewModel.savePreset(draft.name) }, Modifier.weight(1f)) { Icon(Icons.Default.BookmarkAdd, null); Spacer(Modifier.width(6.dp)); Text("Save preset") }
                Button(onClick = { viewModel.navigate(MusicMeDestination.REVIEW) }, Modifier.weight(1f)) { Text("Review") }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun CategoryPanel(category: OptionCategory, options: List<MusicMeOption>, configuration: MusicMeConfiguration, expanded: Boolean, onExpand: () -> Unit, viewModel: MusicMeViewModel) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(category.displayName(), fontWeight = FontWeight.Bold)
                    val count = if (category == OptionCategory.GENRE) 1 + configuration.genres.secondaryGenreIds.size else configuration.selections[category].orEmpty().size
                    Text("${options.size} choices • $count selected", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onExpand) { Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (expanded) "Collapse" else "Expand") }
            }
            if (expanded) {
                HorizontalDivider()
                FlowRow(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    options.forEach { option ->
                        val selected = if (category == OptionCategory.GENRE) option.id == configuration.genres.primaryGenreId || option.id in configuration.genres.secondaryGenreIds else option.id in configuration.selections[category].orEmpty()
                        FilterChip(selected = selected, onClick = {
                            if (category == OptionCategory.GENRE) {
                                if (option.id == configuration.genres.primaryGenreId) Unit else if (option.id in configuration.genres.secondaryGenreIds) viewModel.toggleSecondaryGenre(option.id) else viewModel.setPrimaryGenre(option.id)
                            } else viewModel.toggleSelection(category, option.id, option.multiSelect)
                        }, label = { Text(option.label) }, leadingIcon = if (selected) ({ Icon(Icons.Default.Check, null, Modifier.size(16.dp)) }) else null)
                    }
                }
                if (category == OptionCategory.GENRE) Text("Tap an unselected genre to make it primary; up to three secondary genres can be blended. ${configuration.genres.weights.entries.joinToString { "${it.key.replace('_', ' ')} ${it.value}%" }}", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = StudioCyan)
                if (category == OptionCategory.GENRE) {
                    (listOf(configuration.genres.primaryGenreId) + configuration.genres.secondaryGenreIds).forEach { genreId ->
                        Column(Modifier.padding(horizontal = 16.dp)) {
                            Row { Text(MusicMeCatalog.genres.firstOrNull { it.id == genreId }?.label ?: genreId, Modifier.weight(1f)); Text("${configuration.genres.weights[genreId] ?: 0}%", color = StudioCyan) }
                            Slider((configuration.genres.weights[genreId] ?: 0).toFloat(), { viewModel.setGenreWeight(genreId, it.roundToInt()) }, valueRange = 0f..100f)
                        }
                    }
                }
                if (category == OptionCategory.TEMPO) BpmControl(configuration.bpm, viewModel::setBpm, Modifier.padding(16.dp))
                if (category == OptionCategory.MOOD) OutlinedTextField(configuration.emotionalProgression, viewModel::setEmotionalProgression, Modifier.fillMaxWidth().padding(16.dp), label = { Text("Emotional progression (optional)") }, placeholder = { Text("Example: reflective opening → triumphant finish") })
                if (category == OptionCategory.LANGUAGE) Text("The first selected language is primary; additional selections form the multilingual arrangement. Pronunciation notes are kept with your story.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = StudioCyan)
            }
        }
    }
}

@Composable
private fun OptionCard(option: MusicMeOption, selected: Boolean, badge: String?, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(18.dp), color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .2f) else MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Text(option.label, fontWeight = FontWeight.Bold); badge?.let { Spacer(Modifier.width(8.dp)); Text(it, style = MaterialTheme.typography.labelSmall, color = StudioCyan) } }
                Text(option.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RadioButton(selected, onClick = null)
        }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), placeholder = { Text(placeholder) }, leadingIcon = { Icon(Icons.Default.Search, null) }, trailingIcon = { if (value.isNotBlank()) IconButton(onClick = { onChange("") }) { Icon(Icons.Default.Close, "Clear search") } }, singleLine = true, shape = RoundedCornerShape(18.dp))
}

@Composable
private fun BpmControl(bpm: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Tempo", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            OutlinedTextField(bpm.toString(), { raw -> raw.toIntOrNull()?.let(onChange) }, Modifier.width(96.dp), suffix = { Text("BPM") }, singleLine = true)
        }
        Slider(bpm.toFloat(), { onChange(it.roundToInt()) }, valueRange = 40f..220f)
        Text("40–220 BPM • manual entry supported", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArrangementPicker(configuration: MusicMeConfiguration, viewModel: MusicMeViewModel) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.padding(top = 12.dp)) {
        Text("Predefined arrangement", fontWeight = FontWeight.Bold)
        ExposedDropdownMenuBox(expanded, { expanded = it }) {
            OutlinedTextField(configuration.arrangementName, {}, Modifier.menuAnchor().fillMaxWidth(), readOnly = true, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) })
            ExposedDropdownMenu(expanded, { expanded = false }) { MusicMeCatalog.arrangements.keys.forEach { name -> DropdownMenuItem({ Text(name) }, { viewModel.setArrangement(name); expanded = false }) } }
        }
    }
}

@Composable
private fun ArrangementEditor(configuration: MusicMeConfiguration, viewModel: MusicMeViewModel) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("Editable arrangement", configuration.arrangementName)
            ArrangementPicker(configuration, viewModel)
            configuration.sections.forEachIndexed { index, section ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(section, { value -> viewModel.setSections(configuration.sections.toMutableList().also { it[index] = value }) }, Modifier.weight(1f), label = { Text("Section ${index + 1}") }, singleLine = true)
                    IconButton(enabled = index > 0, onClick = { viewModel.moveConfigSection(index, -1) }) { Icon(Icons.Default.KeyboardArrowUp, "Move up") }
                    IconButton(enabled = index < configuration.sections.lastIndex, onClick = { viewModel.moveConfigSection(index, 1) }) { Icon(Icons.Default.KeyboardArrowDown, "Move down") }
                    IconButton(onClick = { viewModel.setSections(configuration.sections.toMutableList().also { it.removeAt(index) }) }) { Icon(Icons.Default.Close, "Remove section") }
                }
            }
            OutlinedButton(onClick = { viewModel.setSections(configuration.sections + "New Section") }) { Icon(Icons.Default.Add, null); Text("Add section") }
        }
    }
}

@Composable
private fun AdvancedControlsPanel(controls: AdvancedControls, update: (AdvancedControls) -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("Advanced production controls", "Values are preserved for a future engine; support is capability-gated")
            Text("Duration", fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(30, 60, 90, 150, 180).forEach { seconds -> FilterChip(controls.durationSeconds == seconds, { update(controls.copy(durationSeconds = seconds)) }, { Text("${seconds}s") }) }
            }
            OutlinedTextField(
                controls.durationSeconds.toString(),
                { raw -> raw.toIntOrNull()?.let { update(controls.copy(durationSeconds = it.coerceIn(30, 600))) } },
                Modifier.fillMaxWidth(), label = { Text("Custom duration") }, suffix = { Text("seconds") },
                supportingText = { Text("30–600 seconds • target default is 150") }, singleLine = true,
            )
            IntensitySlider("Vocal intensity", controls.vocalIntensity) { update(controls.copy(vocalIntensity = it)) }
            IntensitySlider("Emotional intensity", controls.emotionalIntensity) { update(controls.copy(emotionalIntensity = it)) }
            IntensitySlider("Instrumental complexity", controls.instrumentalComplexity) { update(controls.copy(instrumentalComplexity = it)) }
            IntensitySlider("Bass intensity", controls.bassIntensity) { update(controls.copy(bassIntensity = it)) }
            IntensitySlider("Chorus repetition", controls.chorusRepetition) { update(controls.copy(chorusRepetition = it)) }
            IntensitySlider("Harmony richness", controls.harmonyRichness) { update(controls.copy(harmonyRichness = it)) }
            IntensitySlider("Lyric density", controls.lyricDensity) { update(controls.copy(lyricDensity = it)) }
            IntensitySlider("Intro length", controls.introLength) { update(controls.copy(introLength = it)) }
            IntensitySlider("Outro length", controls.outroLength) { update(controls.copy(outroLength = it)) }
            IntensitySlider("Energy progression", controls.energyProgression) { update(controls.copy(energyProgression = it)) }
            IntensitySlider("Vocal / instrumental balance", controls.vocalBalance) { update(controls.copy(vocalBalance = it)) }
            IntensitySlider("Acoustic / electronic balance", controls.electronicBalance) { update(controls.copy(electronicBalance = it)) }
            IntensitySlider("Organic / synthetic sound", controls.syntheticBalance) { update(controls.copy(syntheticBalance = it)) }
            ToggleRow("Fade in", controls.fadeIn) { update(controls.copy(fadeIn = it)) }
            ToggleRow("Fade out", controls.fadeOut) { update(controls.copy(fadeOut = it)) }
            ToggleRow("Allow explicit content", controls.allowExplicit) { update(controls.copy(allowExplicit = it)) }
            ToggleRow("AI automatic", controls.aiAutomatic) { update(controls.copy(aiAutomatic = it)) }
        }
    }
}

@Composable
private fun IntensitySlider(label: String, value: Int, update: (Int) -> Unit) {
    Column { Row { Text(label, Modifier.weight(1f)); Text("$value%", color = StudioCyan) }; Slider(value.toFloat(), { update(it.roundToInt()) }, valueRange = 0f..100f) }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, update: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f)); Switch(checked, update) }
}

private fun OptionCategory.displayName() = name.lowercase().replace('_', ' ').split(' ').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

private enum class ConfigReplacement(val title: String, val description: String, val confirm: String, val apply: (MusicMeViewModel) -> Unit) {
    QUICK_DEFAULTS("Use quick defaults?", "All studio settings are replaced with suggestions based on your story.", "Use defaults", MusicMeViewModel::quickDefaults),
    SURPRISE("Surprise me?", "All studio settings are replaced with a random compatible mix.", "Surprise me", MusicMeViewModel::surprise),
    RESET("Reset customisation?", "All studio settings return to defaults based on your story.", "Reset", MusicMeViewModel::resetConfiguration),
}
