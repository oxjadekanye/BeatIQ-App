package com.beatiq.music.musicme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.beatiq.music.musicme.audiogeneration.MusicMeSongViewModel
import com.beatiq.music.musicme.audiogeneration.SongCreationAvailability
import com.beatiq.music.musicme.domain.MusicMeDraftPolicy
import com.beatiq.music.musicme.model.CreationMode
import com.beatiq.music.musicme.model.MusicMeDraft
import com.beatiq.music.ui.StudioCyan
import com.beatiq.music.ui.StudioPurple
import com.beatiq.music.ui.StudioViolet
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun MusicMeRoot(viewModel: MusicMeViewModel, songViewModel: MusicMeSongViewModel = androidx.lifecycle.viewmodel.compose.viewModel()) {
    val state by viewModel.state.collectAsState()
    val song by songViewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(song.message) {
        val message = song.message ?: return@LaunchedEffect
        songViewModel.clearMessage()
        snackbar.showSnackbar(message)
    }
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        val offerUndo = state.canUndoConfiguration
        val result = snackbar.showSnackbar(message, actionLabel = if (offerUndo) "Undo" else null, withDismissAction = offerUndo)
        if (result == SnackbarResult.ActionPerformed) viewModel.undoConfigurationChange()
        viewModel.clearMessage()
    }
    Box(Modifier.fillMaxSize()) {
        // A draft-bound screen with no draft (e.g. an unsaved creation lost to process death) falls back to Home.
        val destination = if (state.draft == null && state.destination.requiresDraft) MusicMeDestination.LANDING else state.destination
        when (destination) {
            MusicMeDestination.LANDING -> MusicMeLanding(state, viewModel, showSongs = song.availability == SongCreationAvailability.AVAILABLE || song.saved.isNotEmpty())
            MusicMeDestination.INPUT -> InputStudio(state, viewModel)
            MusicMeDestination.GUIDED -> GuidedStudio(state, viewModel)
            MusicMeDestination.ADVANCED -> AdvancedStudio(state, viewModel)
            MusicMeDestination.LYRICS -> LyricsStudio(state, viewModel)
            MusicMeDestination.DRAFTS -> DraftsScreen(state, viewModel)
            MusicMeDestination.PRESETS -> PresetsScreen(state, viewModel)
            MusicMeDestination.REVIEW -> ReviewScreen(state, viewModel, songViewModel)
            MusicMeDestination.CREATIONS -> CreationsScreen(viewModel, song, songViewModel)
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(12.dp))
    }
}

@Composable
private fun MusicMeLanding(state: MusicMeUiState, viewModel: MusicMeViewModel, showSongs: Boolean = false) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag(MusicMeTestTags.LANDING),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(13.dp))
                        .background(Brush.linearGradient(listOf(StudioPurple, StudioCyan))),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(22.dp)) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("MUSICME", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, modifier = Modifier.semantics { heading() })
                    Text("Your Story. Your Sound. Your Music.", style = MaterialTheme.typography.labelMedium, color = StudioCyan, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                val count = state.drafts.size
                HeaderAction(
                    tooltip = "My creations",
                    description = if (count == 0) "My creations, none saved yet" else "My creations, $count saved",
                    onClick = { viewModel.navigate(MusicMeDestination.DRAFTS) },
                ) {
                    BadgedBox(badge = { if (count > 0) Badge { Text(count.toString(), Modifier.clearAndSetSemantics {}) } }) {
                        Icon(Icons.Default.LibraryMusic, contentDescription = null)
                    }
                }
                HeaderAction(tooltip = "New creation", description = "Start a new creation", onClick = { viewModel.newDraft() }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                }
            }
        }
        item { HeroCard(state, viewModel) }
        // The service decides whether this authenticated account may generate; saved private songs remain visible.
        if (showSongs) {
            item {
                Surface(onClick = { viewModel.navigate(MusicMeDestination.CREATIONS) }, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.QueueMusic, null, tint = StudioCyan, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("My songs", fontWeight = FontWeight.Bold)
                            Text("Your private AI-generated songs, downloads and favourites", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Default.ChevronRight, null)
                    }
                }
            }
        }
        item {
            QuickActionCarousel { action ->
                when (action.label) {
                    "My Drafts" -> viewModel.navigate(MusicMeDestination.DRAFTS)
                    "Saved Presets" -> viewModel.navigate(MusicMeDestination.PRESETS)
                    "Surprise Me" -> viewModel.startSurprise()
                    "Create from Voice" -> viewModel.newDraft(action.occasion, action.mode, action.destination, preferVoice = true)
                    else -> viewModel.newDraft(action.occasion, action.mode, action.destination)
                }
            }
        }
        item { SectionTitle("Recent creations", "Saved drafts and their current status") }
        if (state.drafts.isEmpty()) {
            item {
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .6f)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.GraphicEq, null, tint = StudioViolet, modifier = Modifier.size(30.dp))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("Your studio is ready", fontWeight = FontWeight.Bold)
                            Text("Creations appear here once you save or add your story.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        } else {
            items(state.drafts.take(5), key = { it.id }) { draft -> RecentDraft(draft) { viewModel.openDraft(draft.id) } }
        }
        item { StorageNote() }
    }
}

@Composable
private fun HeroCard(state: MusicMeUiState, viewModel: MusicMeViewModel) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        Modifier.fillMaxWidth()
            .clip(shape)
            .background(Brush.linearGradient(listOf(StudioPurple.copy(alpha = .75f), Color(0xFF202044), StudioCyan.copy(alpha = .28f))))
            .padding(horizontal = 18.dp, vertical = 16.dp)
            .testTag(MusicMeTestTags.HERO),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Lock, null, Modifier.size(14.dp), tint = StudioCyan)
            Spacer(Modifier.width(6.dp))
            Text(MusicMeCopy.STORAGE_BADGE.uppercase(), style = MaterialTheme.typography.labelSmall, color = StudioCyan)
        }
        Text(MusicMeCopy.HERO_TITLE, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
        Text(MusicMeCopy.HERO_BODY, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = .82f))
        Spacer(Modifier.height(2.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { viewModel.newDraft() }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Create", maxLines = 1)
            }
            state.drafts.firstOrNull()?.let { latest ->
                OutlinedButton(onClick = { viewModel.openDraft(latest.id) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Continue", maxLines = 1) }
            }
        }
    }
}

@Composable
private fun StorageNote() {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Default.Info, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(MusicMeCopy.STORAGE_DETAIL, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private data class QuickAction(val label: String, val caption: String, val icon: ImageVector, val occasion: String = "personal_story", val mode: CreationMode = CreationMode.QUICK, val destination: MusicMeDestination = MusicMeDestination.INPUT)
private val quickActions = listOf(
    QuickAction("Create from Text", "Write your story", Icons.Default.EditNote),
    QuickAction("Create from Voice", "Record a voice note", Icons.Default.Mic),
    QuickAction("Birthday Song", "Bright & uplifting", Icons.Default.Cake, "birthday"),
    QuickAction("Love Song", "Warm R&B/Soul", Icons.Default.Favorite, "love_declaration"),
    QuickAction("Wedding Song", "Romantic duet", Icons.Default.Diamond, "wedding"),
    QuickAction("Gospel & Worship", "Choir & spirit", Icons.Default.Church, "worship"),
    QuickAction("Inspirational Song", "Build & triumph", Icons.Default.Bolt, "motivation"),
    QuickAction("Surprise Me", "Valid local mix", Icons.Default.Casino, mode = CreationMode.SURPRISE, destination = MusicMeDestination.ADVANCED),
    QuickAction("My Drafts", "Continue creating", Icons.Default.Folder),
    QuickAction("Saved Presets", "Reuse your sound", Icons.Default.Tune),
)

/** Icon-only header button with a long-press tooltip and a spoken description. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HeaderAction(tooltip: String, description: String, onClick: () -> Unit, icon: @Composable () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(tooltip) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(48.dp).semantics { contentDescription = description }) { icon() }
    }
}

/**
 * Cards are sized so the next one always peeks in; an edge fade and arrow buttons show there is more,
 * and the arrows give keyboard, switch-access and non-swipe users a way to page through.
 */
@Composable
private fun QuickActionCarousel(onAction: (QuickAction) -> Unit) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val spacing = 10.dp
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SectionTitle("Start your session", "${quickActions.size} ways to start • swipe for more") }
            // Arrows live in the header so they never cover card text.
            CarouselArrow(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Show previous ways to start", enabled = listState.canScrollBackward) {
                scope.launch { listState.animateScrollToItem((listState.firstVisibleItemIndex - 2).coerceAtLeast(0)) }
            }
            Spacer(Modifier.width(4.dp))
            CarouselArrow(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Show more ways to start", enabled = listState.canScrollForward, Modifier.testTag(MusicMeTestTags.QUICK_ACTIONS_MORE)) {
                scope.launch { listState.animateScrollToItem((listState.firstVisibleItemIndex + 2).coerceAtMost(quickActions.lastIndex)) }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val cardWidth = ((maxWidth - spacing * 2) / 2.35f).coerceIn(124.dp, 172.dp)
            LazyRow(
                state = listState,
                horizontalArrangement = Arrangement.spacedBy(spacing),
                contentPadding = PaddingValues(end = 8.dp),
                modifier = Modifier.testTag(MusicMeTestTags.QUICK_ACTIONS),
            ) {
                items(quickActions) { action -> QuickActionCard(action, cardWidth) { onAction(action) } }
            }
            if (listState.canScrollForward) {
                Box(
                    Modifier.matchParentSize().wrapContentWidth(Alignment.End).width(28.dp)
                        .background(Brush.horizontalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.background))),
                )
            }
        }
    }
}

@Composable
private fun CarouselArrow(icon: ImageVector, description: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, enabled = enabled, modifier = modifier.size(48.dp)) {
        Icon(icon, contentDescription = description)
    }
}

@Composable
private fun QuickActionCard(action: QuickAction, width: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.width(width).heightIn(min = 104.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(action.icon, null, tint = StudioCyan, modifier = Modifier.size(24.dp))
            Column { Text(action.label, fontWeight = FontWeight.Bold, maxLines = 2); Text(action.caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
internal fun SectionTitle(title: String, subtitle: String? = null) {
    Column {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun RecentDraft(draft: MusicMeDraft, onClick: () -> Unit) {
    Surface(Modifier.fillMaxWidth().clickable(onClick = onClick).testTag(MusicMeTestTags.RECENT_DRAFT), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).background(StudioViolet.copy(alpha = .18f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Default.MusicNote, null, tint = StudioViolet) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(MusicMeDraftPolicy.displayName(draft), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${draft.status.name.lowercase().replaceFirstChar(Char::uppercase)} • ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(draft.modifiedAt))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronRight, null)
        }
    }
}

internal object MusicMeTestTags {
    const val LANDING = "musicme_landing"
    const val HERO = "musicme_hero"
    const val RECENT_DRAFT = "musicme_recent_draft"
    const val INPUT_ACTIONS = "musicme_input_actions"
    const val STORY_FIELD = "musicme_story_field"
    const val TITLE_FIELD = "musicme_title_field"
    const val RECIPIENT_FIELD = "musicme_recipient_field"
    const val OCCASION_FIELD = "musicme_occasion_field"
    const val MESSAGE_FIELD = "musicme_message_field"
    const val EMOTION_FIELD = "musicme_emotion_field"
    const val PRONUNCIATION_FIELD = "musicme_pronunciation_field"
    const val INPUT_LIST = "musicme_input_list"
    const val QUICK_ACTIONS = "musicme_quick_actions"
    const val QUICK_ACTIONS_MORE = "musicme_quick_actions_more"
    const val SONGWRITING_PANEL = "musicme_songwriting_panel"
    const val SONGWRITING_ERROR = "musicme_songwriting_error"
    const val MOCK_ENGINE_BANNER = "musicme_mock_engine_banner"
    const val GENERATE_LYRICS = "musicme_generate_lyrics"
    const val WRITE_LYRICS = "musicme_write_lyrics"
    fun lyricSection(id: String) = "musicme_lyric_section_$id"
    fun rewriteSection(id: String) = "musicme_rewrite_section_$id"
}
