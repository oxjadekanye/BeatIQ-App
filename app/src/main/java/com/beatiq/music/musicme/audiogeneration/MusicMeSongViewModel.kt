package com.beatiq.music.musicme.audiogeneration

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.beatiq.music.core.auth.AuthPreferences
import com.beatiq.music.musicme.model.MusicMeDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

enum class SongCreationAvailability {
    CHECKING,
    AVAILABLE,
    /** This build has no configured audio service. */
    NOT_IN_BUILD,
    /** The server kill switch is off or this stable authenticated account is not allowlisted. */
    NOT_AUTHORISED,
    /** The service runs the development mock engine: it can't create songs. */
    NOT_REAL_ENGINE,
    UNREACHABLE,
}

data class SongCreationError(val code: AudioGenerationErrorCode, val message: String, val retryable: Boolean)

data class SongCreationUiState(
    val availability: SongCreationAvailability = SongCreationAvailability.CHECKING,
    val capabilities: AudioGenerationCapabilities? = null,
    val running: Boolean = false,
    val draftId: String? = null,
    val job: AudioJob? = null,
    val issues: List<AudioRequestIssue> = emptyList(),
    val error: SongCreationError? = null,
    /** Draft waiting for the one-time confirmation before sending lyrics to BeatIQ's private song service. */
    val consentDraftId: String? = null,
    val creations: List<MusicMeCreation> = emptyList(),
    val message: String? = null,
) {
    /** The newest finished take for a draft that the user hasn't kept or discarded yet. */
    fun unsavedFor(draftId: String): MusicMeCreation? = creations.firstOrNull { it.draftId == draftId && !it.saved }
    val saved: List<MusicMeCreation> get() = creations.filter { it.saved }
}

/**
 * Song creation for MusicMe: lyrics and musical choices → the BeatIQ audio service → a validated real song
 * saved to this device. Kept apart from [com.beatiq.music.musicme.MusicMeViewModel] so drafts,
 * their schema and the Library are untouched; songs live in [MusicMeCreationStore].
 */
class MusicMeSongViewModel(application: Application) : AndroidViewModel(application) {
    private val accountId: String? = AuthPreferences(application).userId?.takeIf { it.isNotBlank() }
    private val service = AudioGenerationDependencies.serviceFactory(application)
    private val coordinator = AudioGenerationCoordinator(service, AudioGenerationDependencies.sleep)
    private val store = accountId?.let { MusicMeCreationStore(application, it) }
    private val files = accountId?.let { AudioGenerationDependencies.filesFactory(application, it) }
    private val _state = MutableStateFlow(SongCreationUiState())
    val state: StateFlow<SongCreationUiState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        viewModelScope.launch {
            refreshCreations()
            checkAvailability()
        }
    }

    fun checkAvailability() {
        viewModelScope.launch {
            val availability = when {
                !service.isConfigured || store == null -> SongCreationAvailability.NOT_IN_BUILD
                else -> try {
                    val capabilities = service.capabilities()
                    _state.update { it.copy(capabilities = capabilities) }
                    when {
                        !capabilities.generationAllowed -> SongCreationAvailability.NOT_AUTHORISED
                        capabilities.realAudio -> SongCreationAvailability.AVAILABLE
                        else -> SongCreationAvailability.NOT_REAL_ENGINE
                    }
                } catch (failure: AudioGenerationException) {
                    SongCreationAvailability.UNREACHABLE
                }
            }
            _state.update { it.copy(availability = availability) }
            if (availability == SongCreationAvailability.AVAILABLE) resumePending()
        }
    }

    fun issuesFor(draft: MusicMeDraft): List<AudioRequestIssue> = AudioGenerationRequestBuilder.build(draft, _state.value.capabilities).issues

    fun createSong(draft: MusicMeDraft) {
        val current = _state.value
        if (current.running || current.availability != SongCreationAvailability.AVAILABLE || store == null) return
        val build = AudioGenerationRequestBuilder.build(draft, current.capabilities)
        _state.update { it.copy(issues = build.issues, error = null, draftId = draft.id) }
        val request = build.request ?: return
        viewModelScope.launch {
            if (store.hasConsent()) start(request, resume = null) else _state.update { it.copy(consentDraftId = draft.id) }
        }
    }

    fun grantConsent(draft: MusicMeDraft) {
        if (_state.value.consentDraftId != draft.id || store == null) return
        _state.update { it.copy(consentDraftId = null) }
        viewModelScope.launch {
            store.setConsent(true)
            createSong(draft)
        }
    }

    fun dismissConsent() = _state.update { it.copy(consentDraftId = null) }

    fun cancel() { job?.cancel() }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun clearMessage() = _state.update { it.copy(message = null) }

    fun keep(creation: MusicMeCreation) {
        viewModelScope.launch {
            store?.markSaved(creation.id)
            runCatching { service.updateTrack(creation.id, saved = true) }
            refreshCreations()
            _state.update { it.copy(message = "Saved to My songs.") }
        }
    }

    fun favorite(creation: MusicMeCreation) {
        viewModelScope.launch {
            val next = !creation.favorite
            store?.setFavorite(creation.id, next)
            runCatching { service.updateTrack(creation.id, favorite = next) }
            refreshCreations()
        }
    }

    fun rename(creation: MusicMeCreation, title: String) {
        val clean = title.trim().take(120)
        if (clean.isBlank()) return
        viewModelScope.launch {
            store?.rename(creation.id, clean)
            runCatching { service.updateTrack(creation.id, title = clean) }
            refreshCreations()
            _state.update { it.copy(message = "Song renamed.") }
        }
    }

    /** Removes a song from this device and asks the service to delete its copy too. */
    fun delete(creation: MusicMeCreation) {
        viewModelScope.launch {
            files?.delete(creation.track)
            store?.remove(creation.id)
            refreshCreations()
            val serverDeleted = runCatching { service.deleteTrack(creation.id) }.isSuccess
            _state.update {
                it.copy(message = if (serverDeleted) "Song deleted from this device and the song service." else "Song deleted from this device. The service copy expires on its own.")
            }
        }
    }

    fun fileFor(creation: MusicMeCreation): File? = files?.let { runCatching { it.fileFor(creation.track) }.getOrNull()?.takeIf(File::isFile) }

    /** Copies a song to a location the user picked with the system file picker (no storage permission needed). */
    fun export(creation: MusicMeCreation, destination: Uri) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openOutputStream(destination, "w")?.use { files!!.exportTo(creation.track, it) }
                        ?: throw IOException("No output")
                }.isSuccess
            }
            _state.update { it.copy(message = if (ok) "Song exported." else "The song couldn't be exported. Try another location.") }
        }
    }

    private suspend fun resumePending() {
        val pending = store?.pending() ?: return
        if (_state.value.running) return
        // The request is rebuilt only to satisfy the coordinator; with a stored job id nothing is resubmitted.
        if (pending.jobId == null) {
            store.setPending(null)
            return
        }
        start(null, resume = pending)
    }

    private fun start(request: AudioGenerationRequest?, resume: PendingAudioJob?) {
        val store = store ?: return
        val draftId = request?.draftId ?: resume?.draftId ?: return
        job = viewModelScope.launch {
            _state.update { it.copy(running = true, draftId = draftId, job = null, error = null) }
            try {
                val finished = coordinator.execute(
                    request ?: placeholderRequest(draftId), resume,
                    persistPending = { store.setPending(it) },
                    onProgress = { progress -> _state.update { it.copy(job = progress) } },
                )
                val track = finished.tracks.first { it.available }
                files!!.download(service.downloadLink(track.id), track)
                store.add(MusicMeCreation(track, finished.generation!!, draftId, request?.title.orEmpty(), System.currentTimeMillis(), saved = false))
                refreshCreations()
                _state.update { it.copy(message = "Your song is ready. Listen, then keep or discard it.") }
            } catch (failure: AudioGenerationException) {
                _state.update { it.copy(error = SongCreationError(failure.code, failure.userMessage, failure.retryable)) }
            } catch (failure: IOException) {
                _state.update { it.copy(error = SongCreationError(AudioGenerationErrorCode.UNAVAILABLE, "The song couldn't be saved on this device. Try again.", true)) }
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(message = "Song creation cancelled.") }
                throw cancelled
            } finally {
                _state.update { it.copy(running = false) }
            }
        }
    }

    private suspend fun refreshCreations() {
        val list = runCatching { store?.creations().orEmpty() }.getOrElse {
            _state.update { state -> state.copy(error = SongCreationError(AudioGenerationErrorCode.UNKNOWN, "Your song list couldn't be read.", false)) }
            return
        }
        // Only list songs whose audio is actually on this device.
        _state.update { it.copy(creations = list.filter { creation -> files?.isSaved(creation.track) == true }) }
    }

    private fun placeholderRequest(draftId: String) = AudioGenerationRequest(
        draftId = draftId, title = "", prompt = "", language = "english", sections = emptyList(), genres = emptyList(), moods = emptyList(), bpm = null,
        instrumentation = emptyList(), vocalType = "none", vocalDescriptors = emptyList(), targetDurationSeconds = 30,
    )
}
