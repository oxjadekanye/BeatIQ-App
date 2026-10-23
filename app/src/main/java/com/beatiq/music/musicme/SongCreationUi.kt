package com.beatiq.music.musicme

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.beatiq.music.musicme.audiogeneration.AudioJobState
import com.beatiq.music.musicme.audiogeneration.AudioRequestIssue
import com.beatiq.music.musicme.audiogeneration.MusicMeCreation
import com.beatiq.music.musicme.audiogeneration.MusicMeSongViewModel
import com.beatiq.music.musicme.audiogeneration.SongCreationAvailability
import com.beatiq.music.musicme.audiogeneration.SongCreationUiState
import com.beatiq.music.musicme.model.MusicMeDraft
import com.beatiq.music.ui.StudioCyan
import java.io.File

internal object SongCreationTags {
    const val CREATE = "musicme_song_create"
    const val PROGRESS = "musicme_song_progress"
    const val RESULT = "musicme_song_result"
    const val CREATIONS = "musicme_song_creations"
}

/**
 * Song creation on the Review screen. Builds without a configured service keep the action disabled, while the
 * backend independently enforces its generation mode and stable-account allowlist.
 */
@Composable
internal fun SongCreationSection(draft: MusicMeDraft, song: SongCreationUiState, songViewModel: MusicMeSongViewModel) {
    if (song.availability == SongCreationAvailability.NOT_IN_BUILD) {
        UnavailableGeneration(MusicMeCopy.GENERATION_BODY)
        return
    }
    if (song.availability != SongCreationAvailability.AVAILABLE) {
        UnavailableGeneration(
            when (song.availability) {
                SongCreationAvailability.CHECKING -> MusicMeCopy.SONG_CHECKING
                SongCreationAvailability.NOT_AUTHORISED -> MusicMeCopy.SONG_NOT_AUTHORISED
                SongCreationAvailability.NOT_REAL_ENGINE -> MusicMeCopy.SONG_NOT_REAL_ENGINE
                else -> MusicMeCopy.SONG_UNREACHABLE
            },
        )
        return
    }
    val busyHere = song.running && song.draftId == draft.id
    val unsaved = song.unsavedFor(draft.id)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            busyHere -> SongProgress(song, songViewModel)
            unsaved != null -> SongResult(unsaved, songViewModel)
            else -> {
                val issues = songViewModel.issuesFor(draft)
                Button(
                    onClick = { songViewModel.createSong(draft) },
                    enabled = !song.running && issues.isEmpty(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag(SongCreationTags.CREATE),
                ) { Icon(Icons.Default.AutoAwesome, null); Spacer(Modifier.width(8.dp)); Text(MusicMeCopy.SONG_BUTTON) }
                Text(MusicMeCopy.SONG_DEV_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                issues.forEach { Text("• ${issueText(it)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                if (song.running) Text("Another song is being created. Wait for it to finish.", style = MaterialTheme.typography.bodySmall)
            }
        }
        song.error?.takeIf { song.draftId == draft.id }?.let { error ->
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.errorContainer) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text(error.message, color = MaterialTheme.colorScheme.onErrorContainer)
                    Row {
                        if (error.retryable) TextButton(onClick = { songViewModel.dismissError(); songViewModel.createSong(draft) }) { Text("Try again") }
                        TextButton(onClick = songViewModel::dismissError) { Text("Dismiss") }
                    }
                }
            }
        }
    }
    if (song.consentDraftId == draft.id) {
        AlertDialog(
            onDismissRequest = songViewModel::dismissConsent,
            title = { Text(MusicMeCopy.SONG_CONSENT_TITLE) },
            text = { Text(MusicMeCopy.SONG_CONSENT_BODY) },
            confirmButton = { TextButton(onClick = { songViewModel.grantConsent(draft) }) { Text("Create song") } },
            dismissButton = { TextButton(onClick = songViewModel::dismissConsent) { Text("Not now") } },
        )
    }
}

@Composable
private fun UnavailableGeneration(body: String) {
    Column {
        Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) { Icon(Icons.Default.AutoAwesome, null); Spacer(Modifier.width(8.dp)); Text(MusicMeCopy.GENERATION_BUTTON) }
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SongProgress(song: SongCreationUiState, songViewModel: MusicMeSongViewModel) {
    val job = song.job
    val label = when {
        job == null -> "Sending your lyrics…"
        job.state == AudioJobState.QUEUED -> job.queuePosition?.let { "Waiting in line (position $it)…" } ?: "Waiting in line…"
        job.stage == "rendering" -> "Creating vocals and music…"
        job.stage == "validating" -> "Checking the audio…"
        else -> "Finishing up…"
    }
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.testTag(SongCreationTags.PROGRESS)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(label, fontWeight = FontWeight.Bold)
            // Real service progress only; no simulated animation.
            if (job != null && job.progress > 0) LinearProgressIndicator(progress = { job.progress / 100f }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Generation time varies with provider queue and song length. Closing the app won't duplicate the request.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = songViewModel::cancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun SongResult(creation: MusicMeCreation, songViewModel: MusicMeSongViewModel) {
    var confirmDiscard by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.testTag(SongCreationTags.RESULT)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(creation.title.ifBlank { "Your song" }, fontWeight = FontWeight.Bold)
            AiGeneratedLabel(creation)
            songViewModel.fileFor(creation)?.let { SongPlayer(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { songViewModel.keep(creation) }, Modifier.weight(1f)) { Icon(Icons.Default.Save, null); Spacer(Modifier.width(6.dp)); Text("Keep") }
                OutlinedButton(onClick = { confirmDiscard = true }, Modifier.weight(1f)) { Icon(Icons.Default.Delete, null); Spacer(Modifier.width(6.dp)); Text("Discard") }
            }
        }
    }
    if (confirmDiscard) DeleteDialog(onConfirm = { confirmDiscard = false; songViewModel.delete(creation) }, onDismiss = { confirmDiscard = false })
}

@Composable
private fun AiGeneratedLabel(creation: MusicMeCreation) {
    Text(
        "AI-generated song • ${creation.generation.modelId} • review before use",
        style = MaterialTheme.typography.labelMedium, color = StudioCyan,
    )
}

@Composable
private fun DeleteDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete this song?") },
        text = { Text("It's removed from this device and the song service. Your draft and lyrics are kept.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Plays one song file from app storage; released when it leaves the screen. */
@Composable
internal fun SongPlayer(file: File) {
    val context = LocalContext.current
    val player = remember(file) {
        ExoPlayer.Builder(context).build().apply { setMediaItem(MediaItem.fromUri(Uri.fromFile(file))); prepare() }
    }
    var playing by remember(file) { mutableStateOf(false) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) { player.seekTo(0); player.pause() } }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }
    OutlinedButton(onClick = { if (playing) player.pause() else player.play() }) {
        Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null)
        Spacer(Modifier.width(6.dp))
        Text(if (playing) "Pause" else "Play")
    }
}

/** My songs: finished songs kept on this device (separate from drafts and from the BeatIQ Library). */
@Composable
internal fun CreationsScreen(viewModel: MusicMeViewModel, song: SongCreationUiState, songViewModel: MusicMeSongViewModel) {
    var exporting by remember { mutableStateOf<MusicMeCreation?>(null) }
    var deleting by remember { mutableStateOf<MusicMeCreation?>(null) }
    var playingId by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<MusicMeCreation?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        val target = exporting
        exporting = null
        if (uri != null && target != null) songViewModel.export(target, uri)
    }
    LazyColumn(Modifier.fillMaxSize().testTag(SongCreationTags.CREATIONS), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { StudioTopBar("My songs", "${song.saved.size} on this device", viewModel::back) }
        item { Text(MusicMeCopy.SONG_STORAGE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (song.saved.isEmpty()) {
            item { Text("No songs yet. Create one from a draft's Review screen.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(song.saved, key = { it.id }) { creation ->
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(creation.title.ifBlank { "Untitled song" }, fontWeight = FontWeight.Bold)
                    AiGeneratedLabel(creation)
                    Text("${creation.track.durationMs / 1000}s • ${creation.track.format.uppercase()}", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (playingId == creation.id) songViewModel.fileFor(creation)?.let { SongPlayer(it) }
                        else OutlinedButton(onClick = { playingId = creation.id }) { Icon(Icons.Default.PlayArrow, null); Text("Listen") }
                        IconButton(onClick = { songViewModel.favorite(creation) }) {
                            Icon(if (creation.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = if (creation.favorite) "Remove from favourites" else "Add to favourites")
                        }
                        IconButton(onClick = { renaming = creation }) { Icon(Icons.Default.Edit, contentDescription = "Rename song") }
                        IconButton(onClick = {
                            exporting = creation
                            exportLauncher.launch("${creation.title.ifBlank { "musicme-song" }.replace(Regex("[^A-Za-z0-9 _-]"), "").take(60)}.wav")
                        }) { Icon(Icons.Default.Download, contentDescription = "Download a copy") }
                        IconButton(onClick = { deleting = creation }) { Icon(Icons.Default.Delete, contentDescription = "Delete song") }
                    }
                }
            }
        }
    }
    deleting?.let { creation -> DeleteDialog(onConfirm = { deleting = null; songViewModel.delete(creation) }, onDismiss = { deleting = null }) }
    renaming?.let { creation -> RenameDialog(creation, { renaming = null; songViewModel.rename(creation, it) }, { renaming = null }) }
}

@Composable
private fun RenameDialog(creation: MusicMeCreation, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var title by remember(creation.id) { mutableStateOf(creation.title) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename song") },
        text = { OutlinedTextField(title, { title = it.take(120) }, singleLine = true, label = { Text("Song title") }) },
        confirmButton = { TextButton(onClick = { onConfirm(title) }, enabled = title.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun issueText(issue: AudioRequestIssue): String = when (issue) {
    AudioRequestIssue.NO_LYRICS -> "Add lyrics to at least one section."
    AudioRequestIssue.LYRICS_IN_INSTRUMENTAL_SECTION -> "Instrumental sections can't have lyrics."
    AudioRequestIssue.UNSUPPORTED_SECTION -> "One section type isn't supported for songs yet."
    AudioRequestIssue.TOO_MANY_SECTIONS -> "Use fewer sections."
    AudioRequestIssue.VOCAL_TYPE_NEEDED -> "Choose a single vocal arrangement."
    AudioRequestIssue.UNSUPPORTED_LANGUAGE -> "This language isn't supported by the song engine yet."
    AudioRequestIssue.NO_GENRE -> "Choose a genre."
}
