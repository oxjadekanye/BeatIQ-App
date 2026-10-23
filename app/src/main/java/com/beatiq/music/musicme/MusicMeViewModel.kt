package com.beatiq.music.musicme

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.beatiq.music.core.auth.AuthPreferences
import com.beatiq.music.musicme.data.MusicMeRepository
import com.beatiq.music.musicme.domain.LocalMusicMeRecommendationService
import com.beatiq.music.musicme.domain.MusicMeDraftPolicy
import com.beatiq.music.musicme.domain.MusicMeConfigurationValidator
import com.beatiq.music.musicme.model.*
import com.beatiq.music.musicme.songwriting.SongSpecificationBuilder
import com.beatiq.music.musicme.songwriting.SongwritingCapabilities
import com.beatiq.music.musicme.songwriting.SongwritingCoordinator
import com.beatiq.music.musicme.songwriting.SongwritingDependencies
import com.beatiq.music.musicme.songwriting.SongwritingErrorCode
import com.beatiq.music.musicme.songwriting.SongwritingException
import com.beatiq.music.musicme.songwriting.SongwritingMerger
import com.beatiq.music.musicme.songwriting.SongwritingPhase
import com.beatiq.music.musicme.songwriting.SpecIssue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class MusicMeDestination(val requiresDraft: Boolean) {
    LANDING(false), INPUT(true), GUIDED(true), ADVANCED(true), LYRICS(true), DRAFTS(false), PRESETS(false), REVIEW(true), CREATIONS(false)
}

data class MusicMeUiState(
    val destination: MusicMeDestination = MusicMeDestination.LANDING,
    val draft: MusicMeDraft? = null,
    val drafts: List<MusicMeDraft> = emptyList(),
    val presets: List<MusicMePreset> = emptyList(),
    val recommendationExplanation: String? = null,
    val saved: Boolean = true,
    /** False while a new creation has not been saved or meaningfully edited; it stays out of Recent Creations. */
    val draftPersisted: Boolean = true,
    val message: String? = null,
    val preferVoiceInput: Boolean = false,
    /** True once the active draft's studio settings belong to the user (opened from storage or edited by hand). */
    val configCustomised: Boolean = false,
    /** Story removed by Clear; restorable only into the same draft while its story is still empty. */
    val storyUndo: MusicMeUndo<String>? = null,
    /** Settings replaced by Quick defaults, Surprise, Reset or a preset; restorable into the same draft. */
    val configUndo: MusicMeUndo<MusicMeConfiguration>? = null,
    val songwriting: SongwritingUiState = SongwritingUiState(),
) {
    val canUndoClear: Boolean
        get() = storyUndo != null && storyUndo.draftId == draft?.id && draft.input.story.isBlank()
    val canUndoConfiguration: Boolean
        get() = configUndo != null && configUndo.draftId == draft?.id
}

data class MusicMeUndo<T>(val draftId: String, val previous: T)

data class SongwritingUiState(
    /** False when this build has no songwriting service; generation controls are then replaced by an explanation. */
    val configured: Boolean = false,
    val running: Boolean = false,
    /** Engine reported by the connected service ("self_hosted" or "mock"); null until first contact. */
    val engine: String? = null,
    /** Draft the running or last request belongs to. */
    val draftId: String? = null,
    val phase: SongwritingPhase? = null,
    val targetSectionId: String? = null,
    val issues: List<SpecIssue> = emptyList(),
    val error: SongwritingError? = null,
    val warnings: List<String> = emptyList(),
    /** Set while the one-time "send to the songwriting service" confirmation is showing. */
    val consentRequest: SongwritingConsentRequest? = null,
    val lyricsUndo: MusicMeUndo<MusicMeLyricsDraft>? = null,
)

const val MOCK_ENGINE = "mock"

data class SongwritingError(val code: SongwritingErrorCode, val message: String, val retryable: Boolean)

data class SongwritingConsentRequest(val sectionId: String?)

/** Top-bar status; a creation that has never been written to storage says so instead of "Saving…". */
fun MusicMeUiState.saveStatus(savedLabel: String = "Saved"): String = when {
    !draftPersisted -> "Not saved yet"
    saved -> savedLabel
    else -> "Saving…"
}

class MusicMeViewModel(application: Application, private val savedStateHandle: SavedStateHandle) : AndroidViewModel(application) {
    private val accountId =
        requireNotNull(AuthPreferences(application).userId?.takeIf { it.isNotBlank() }) {
            "MusicMe requires a signed-in account"
        }
    val repository = MusicMeRepository(application, accountId)
    val recommender = LocalMusicMeRecommendationService()
    private val _state = MutableStateFlow(MusicMeUiState(
        destination = savedStateHandle.get<String>("destination")?.let { runCatching { MusicMeDestination.valueOf(it) }.getOrNull() } ?: MusicMeDestination.LANDING,
    ))
    val state: StateFlow<MusicMeUiState> = _state.asStateFlow()
    private var autosaveJob: Job? = null
    private var pendingSave: MusicMeDraft? = null
    private val songwritingService = SongwritingDependencies.serviceFactory(application)
    private val songwritingCoordinator = SongwritingCoordinator(songwritingService, SongwritingDependencies.log, sleep = SongwritingDependencies.sleep)
    private var songwritingJob: Job? = null
    private var songwritingCapabilities: SongwritingCapabilities? = null
    private val resumeChecked = mutableSetOf<String>()

    init {
        _state.update { it.copy(songwriting = it.songwriting.copy(configured = songwritingService.isConfigured)) }
        viewModelScope.launch { repository.drafts.collect { drafts ->
            _state.update { state ->
                val activeId = savedStateHandle.get<String>("activeDraftId")
                state.copy(drafts = drafts.sortedByDescending(MusicMeDraft::modifiedAt), draft = state.draft ?: drafts.firstOrNull { it.id == activeId })
            }
            _state.value.draft?.let(::maybeResumeSongwriting)
        } }
        viewModelScope.launch { repository.presets.collect { _state.update { state -> state.copy(presets = it.sortedByDescending(MusicMePreset::createdAt)) } } }
    }

    fun navigate(destination: MusicMeDestination) {
        savedStateHandle["destination"] = destination.name
        _state.update { it.copy(destination = destination, message = null) }
    }
    fun back(): Boolean {
        if (_state.value.destination == MusicMeDestination.LANDING) return false
        savedStateHandle["destination"] = MusicMeDestination.LANDING.name
        _state.update { it.copy(destination = MusicMeDestination.LANDING, message = null) }
        return true
    }

    /** Opens a fresh creation in memory only; it is written to storage on save or the first meaningful edit. */
    fun newDraft(occasionId: String = "personal_story", mode: CreationMode = CreationMode.QUICK, destination: MusicMeDestination = MusicMeDestination.INPUT, preferVoice: Boolean = false) {
        flushPendingSave()
        val input = MusicMeInput(occasionId = occasionId)
        val recommendation = recommender.recommend(input, MusicMeConfiguration(mode = mode))
        val createdAt = System.currentTimeMillis()
        val draft = MusicMeDraft(
            name = untitledName(input, createdAt, draftId = null),
            createdAt = createdAt,
            modifiedAt = createdAt,
            input = input,
            configuration = recommendation.configuration.copy(mode = mode),
        )
        savedStateHandle["activeDraftId"] = draft.id
        savedStateHandle["destination"] = destination.name
        _state.update { it.copy(draft = draft, destination = destination, recommendationExplanation = recommendation.explanation, saved = false, draftPersisted = false, preferVoiceInput = preferVoice, configCustomised = false, storyUndo = null, configUndo = null) }
    }

    /** Home "Surprise Me": always a new creation, never a reroll of whichever saved draft was last open. */
    fun startSurprise() {
        newDraft(mode = CreationMode.SURPRISE, destination = MusicMeDestination.ADVANCED)
        surprise()
    }

    /** Presets list (reached from Home): starts a new creation from the preset instead of overwriting the last open draft. */
    fun startFromPreset(id: String) {
        if (_state.value.presets.none { it.id == id }) return
        newDraft(destination = MusicMeDestination.ADVANCED)
        applyPreset(id)
    }

    fun openDraft(id: String, destination: MusicMeDestination = MusicMeDestination.INPUT) {
        _state.value.drafts.firstOrNull { it.id == id }?.let { draft ->
            flushPendingSave()
            savedStateHandle["activeDraftId"] = id; savedStateHandle["destination"] = destination.name
            _state.update { it.copy(draft = draft, destination = destination, saved = true, draftPersisted = true, preferVoiceInput = false, configCustomised = true, storyUndo = null, configUndo = null) }
            maybeResumeSongwriting(draft)
        }
    }

    fun updateInput(transform: (MusicMeInput) -> MusicMeInput) = updateDraft { draft ->
        val input = transform(draft.input)
        draft.copy(name = untitledName(input, draft.createdAt, draft.id), input = input, lyrics = draft.lyrics.copy(title = input.title))
    }

    /** User titles are kept as typed; generated names get a suffix if another creation already uses them. */
    private fun untitledName(input: MusicMeInput, createdAt: Long, draftId: String?): String {
        val name = MusicMeDraftPolicy.defaultName(input, createdAt)
        if (input.title.isNotBlank()) return name
        val taken = _state.value.drafts.filter { it.id != draftId }.map(MusicMeDraftPolicy::displayName)
        return MusicMeDraftPolicy.uniqueName(name, taken)
    }

    fun clearStory() {
        val draft = _state.value.draft ?: return
        if (draft.input.story.isBlank()) return
        _state.update { it.copy(storyUndo = MusicMeUndo(draft.id, draft.input.story)) }
        updateInput { it.copy(story = "") }
    }

    /** Restores a cleared story only into the draft it came from, and never over text typed since. */
    fun undoClearStory() {
        val state = _state.value
        if (!state.canUndoClear) return
        val restore = state.storyUndo!!.previous
        _state.update { it.copy(storyUndo = null) }
        updateInput { it.copy(story = restore) }
    }

    fun recordingAvailable(recording: MusicMeVoiceRecording?): Boolean =
        recording != null && repository.hasRecordingFile(recording)

    fun canContinueToCustomisation(draft: MusicMeDraft): Boolean =
        MusicMeDraftPolicy.canContinueToCustomisation(draft, recordingAvailable(draft.recording))

    /**
     * Input Studio "Customise": keeps every entered field as-is, refreshes untouched defaults from the
     * story or message, and never replaces settings the user owns. Ineligible drafts stay on Input.
     */
    fun continueToCustomisation() {
        val draft = _state.value.draft ?: return
        if (!canContinueToCustomisation(draft)) return
        if (!_state.value.configCustomised) {
            applyRecommendations()
            setMode(CreationMode.GUIDED)
        }
        navigate(MusicMeDestination.GUIDED)
    }
    fun attachRecording(recording: MusicMeVoiceRecording?) = updateDraft { it.copy(recording = recording) }

    fun applyRecommendations() = updateDraft(userCustomisation = false) { draft ->
        val result = recommender.recommend(draft.input, draft.configuration)
        _state.update { it.copy(recommendationExplanation = result.explanation) }
        draft.copy(configuration = result.configuration)
    }

    fun surprise() = replaceConfiguration("A compatible configuration was selected locally. No audio has been generated.") { recommender.surprise() }.also {
        _state.update { it.copy(destination = MusicMeDestination.ADVANCED) }
    }

    fun quickDefaults() = replaceConfiguration("Quick defaults applied") { draft ->
        val result = recommender.recommend(draft.input, draft.configuration)
        _state.update { it.copy(recommendationExplanation = result.explanation) }
        result.configuration.copy(mode = CreationMode.QUICK)
    }

    fun setMode(mode: CreationMode) = updateConfiguration(userCustomisation = false) { it.copy(mode = mode) }
    fun setPrimaryGenre(id: String) = updateConfiguration { config ->
        val secondary = config.genres.secondaryGenreIds - id
        config.copy(genres = config.genres.copy(primaryGenreId = id, secondaryGenreIds = secondary, weights = mapOf(id to 70) + secondary.associateWith { 30 / secondary.size.coerceAtLeast(1) }))
    }
    fun toggleSecondaryGenre(id: String) = updateConfiguration { config ->
        if (id == config.genres.primaryGenreId) config else {
            val updated = if (id in config.genres.secondaryGenreIds) config.genres.secondaryGenreIds - id else (config.genres.secondaryGenreIds + id).take(3).toSet()
            config.copy(genres = config.genres.copy(secondaryGenreIds = updated, weights = mapOf(config.genres.primaryGenreId to 70) + updated.associateWith { 30 / updated.size.coerceAtLeast(1) }))
        }
    }
    fun setGenreWeight(id: String, weight: Int) = updateConfiguration { config ->
        if (id != config.genres.primaryGenreId && id !in config.genres.secondaryGenreIds) config
        else config.copy(genres = config.genres.copy(weights = config.genres.weights + (id to weight.coerceIn(0, 100))))
    }
    fun toggleSelection(category: OptionCategory, id: String, multi: Boolean) = updateConfiguration { config ->
        val current = config.selections[category].orEmpty()
        val next = if (!multi) setOf(id) else if (id in current) current - id else current + id
        var updated = config.copy(selections = config.selections + (category to next))
        if ((category == OptionCategory.VOCAL_CHARACTER || category == OptionCategory.VOCAL_DELIVERY) &&
            "instrumental_only" in updated.selections[OptionCategory.VOCAL_ARRANGEMENT].orEmpty()) {
            updated = updated.copy(selections = updated.selections + (OptionCategory.VOCAL_ARRANGEMENT to setOf("lead_and_backing_vocals")))
        }
        MusicMeConfigurationValidator.normalize(updated)
    }
    fun setBpm(value: Int) = updateConfiguration { it.copy(bpm = value.coerceIn(40, 220)) }
    fun setEmotionalProgression(value: String) = updateConfiguration { it.copy(emotionalProgression = value) }
    fun setArrangement(name: String) = updateConfiguration { it.copy(arrangementName = name, sections = com.beatiq.music.musicme.data.MusicMeCatalog.arrangements[name].orEmpty()) }
    fun setSections(sections: List<String>) = updateConfiguration { it.copy(sections = sections) }
    fun moveConfigSection(index: Int, delta: Int) = updateConfiguration { config ->
        val list = config.sections.toMutableList(); val to = (index + delta).coerceIn(0, list.lastIndex)
        if (index in list.indices && index != to) list.add(to, list.removeAt(index))
        config.copy(sections = list)
    }
    fun updateControls(controls: AdvancedControls) = updateConfiguration { it.copy(controls = controls) }
    fun resetConfiguration() = replaceConfiguration("Customisation reset") { recommender.recommend(it.input).configuration }

    fun undoConfigurationChange() {
        val state = _state.value
        if (!state.canUndoConfiguration) return
        val previous = state.configUndo!!.previous
        _state.update { it.copy(configUndo = null) }
        updateDraft(userCustomisation = state.configCustomised) { it.copy(configuration = previous) }
    }

    /** Whole-configuration replacements keep the previous settings for a one-step undo. */
    private fun replaceConfiguration(message: String, userCustomisation: Boolean = false, next: (MusicMeDraft) -> MusicMeConfiguration) {
        val current = _state.value.draft ?: return
        _state.update { it.copy(configUndo = MusicMeUndo(current.id, current.configuration), message = message) }
        updateDraft(userCustomisation) { it.copy(configuration = next(it)) }
    }

    fun updateLyrics(transform: (MusicMeLyricsDraft) -> MusicMeLyricsDraft) = updateDraft { it.copy(lyrics = transform(it.lyrics)) }
    fun addLyricSection(type: String) = updateLyrics { lyrics -> lyrics.copy(sections = lyrics.sections + MusicMeSongSection(type = type.lowercase(), label = type, order = lyrics.sections.size)) }
    fun removeLyricSection(id: String) = updateLyrics { lyrics -> lyrics.copy(sections = lyrics.sections.filterNot { it.id == id }.mapIndexed { index, section -> section.copy(order = index) }) }
    fun moveLyricSection(id: String, delta: Int) = updateLyrics { lyrics ->
        val list = lyrics.sections.toMutableList(); val from = list.indexOfFirst { it.id == id }; val to = (from + delta).coerceIn(0, list.lastIndex)
        if (from >= 0 && from != to) list.add(to, list.removeAt(from))
        lyrics.copy(sections = list.mapIndexed { index, section -> section.copy(order = index) })
    }

    fun saveNow(message: String = MusicMeCopy.DRAFT_SAVED) {
        val draft = _state.value.draft ?: return
        autosaveJob?.cancel()
        pendingSave = null
        _state.update { it.copy(draftPersisted = true) }
        viewModelScope.launch { repository.upsertDraft(draft.copy(modifiedAt = System.currentTimeMillis())); _state.update { it.copy(saved = true, message = message) } }
    }
    fun savePreset(name: String) {
        val configuration = _state.value.draft?.configuration ?: return
        viewModelScope.launch { repository.savePreset(MusicMePreset(name = name.ifBlank { "My studio preset" }, configuration = configuration)); _state.update { it.copy(message = "Preset saved") } }
    }
    fun applyPreset(id: String) {
        val preset = _state.value.presets.firstOrNull { it.id == id } ?: return
        replaceConfiguration("Preset “${preset.name}” applied", userCustomisation = true) { preset.configuration }
        navigate(MusicMeDestination.ADVANCED)
    }
    fun duplicate(id: String) { viewModelScope.launch { repository.duplicateDraft(id) } }
    fun delete(id: String) {
        if (_state.value.songwriting.draftId == id) songwritingJob?.cancel()
        if (pendingSave?.id == id) { autosaveJob?.cancel(); pendingSave = null }
        viewModelScope.launch { repository.deleteDraft(id); if (_state.value.draft?.id == id) _state.update { it.copy(draft = null) } }
    }
    fun clearMessage() = _state.update { it.copy(message = null) }

    private fun updateConfiguration(userCustomisation: Boolean = true, transform: (MusicMeConfiguration) -> MusicMeConfiguration) =
        updateDraft(userCustomisation) { it.copy(configuration = transform(it.configuration)) }

    /** Content edits persist once meaningful; [userCustomisation] marks deliberate studio choices, not automatic defaults. */
    private fun updateDraft(userCustomisation: Boolean = false, transform: (MusicMeDraft) -> MusicMeDraft) {
        val current = _state.value.draft ?: return
        val updated = transform(current).copy(modifiedAt = System.currentTimeMillis())
        val persist = MusicMeDraftPolicy.shouldPersist(_state.value.draftPersisted, userCustomisation, updated)
        _state.update { it.copy(draft = updated, saved = false, draftPersisted = persist, configCustomised = it.configCustomised || userCustomisation) }
        if (persist) scheduleSave(updated)
    }

    /** Debounced write of exactly [draft]; switching drafts flushes it so the edit can't land on, or be lost to, another draft. */
    private fun scheduleSave(draft: MusicMeDraft) {
        autosaveJob?.cancel()
        pendingSave = draft
        autosaveJob = viewModelScope.launch {
            delay(700)
            repository.upsertDraft(draft)
            if (pendingSave === draft) pendingSave = null
            if (_state.value.draft?.id == draft.id) _state.update { it.copy(saved = true) }
        }
    }

    /** Lyrics Studio edits; once a generated section is changed by hand it is never rewritten automatically. */
    fun editSectionLyrics(id: String, value: String) = updateLyrics { lyrics ->
        lyrics.copy(sections = lyrics.sections.map { section ->
            if (section.id != id) section
            else section.copy(lyrics = value, origin = if (section.origin == SectionOrigin.AI) SectionOrigin.AI_EDITED else section.origin)
        })
    }

    /** Issues for the current inputs, shown before the user asks for lyrics. */
    fun songwritingIssues(draft: MusicMeDraft, sectionId: String? = null): List<SpecIssue> =
        SongSpecificationBuilder.build(draft, songwritingCapabilities, sectionId).issues

    fun generateLyrics() = requestSongwriting(sectionId = null)

    fun regenerateSection(sectionId: String) = requestSongwriting(sectionId)

    fun grantSongwritingConsent() {
        val request = _state.value.songwriting.consentRequest ?: return
        val draftId = _state.value.draft?.id ?: return
        updateSongwriting { it.copy(consentRequest = null) }
        viewModelScope.launch {
            repository.setSongwritingConsent(true)
            startSongwriting(draftId, request.sectionId, resume = null)
        }
    }

    fun dismissSongwritingConsent() = updateSongwriting { it.copy(consentRequest = null) }

    fun cancelSongwriting() { songwritingJob?.cancel() }

    fun dismissSongwritingError() = updateSongwriting { it.copy(error = null) }

    /** Restores the lyrics as they were before the last generation, for the same draft only. */
    fun undoSongwriting() {
        val undo = _state.value.songwriting.lyricsUndo ?: return
        if (undo.draftId != _state.value.draft?.id || _state.value.songwriting.running) return
        updateSongwriting { it.copy(lyricsUndo = null) }
        updateDraft(userCustomisation = true) { it.copy(lyrics = undo.previous) }
    }

    private fun requestSongwriting(sectionId: String?) {
        val draft = _state.value.draft ?: return
        if (_state.value.songwriting.running) return
        if (!songwritingService.isConfigured) {
            updateSongwriting { it.copy(draftId = draft.id, error = SongwritingError(SongwritingErrorCode.NOT_CONFIGURED, "AI songwriting isn't connected in this build.", false)) }
            return
        }
        viewModelScope.launch {
            if (!repository.hasSongwritingConsent()) {
                updateSongwriting { it.copy(consentRequest = SongwritingConsentRequest(sectionId)) }
            } else {
                startSongwriting(draft.id, sectionId, resume = null)
            }
        }
    }

    /** Picks up a request interrupted by process death (its id or idempotency key is stored with the draft). */
    private fun maybeResumeSongwriting(draft: MusicMeDraft) {
        val pending = draft.songwriting?.pendingJob ?: return
        if (!songwritingService.isConfigured || _state.value.songwriting.running || !resumeChecked.add(draft.id)) return
        startSongwriting(draft.id, pending.targetSectionId, resume = pending)
    }

    private fun startSongwriting(draftId: String, sectionId: String?, resume: MusicMePendingSongwritingJob?) {
        songwritingJob = viewModelScope.launch {
            updateSongwriting {
                it.copy(running = true, draftId = draftId, targetSectionId = sectionId, error = null, warnings = emptyList(),
                    phase = if (resume != null) SongwritingPhase.RESUMING else SongwritingPhase.SUBMITTING)
            }
            try {
                val capabilities = songwritingCapabilities ?: songwritingService.capabilities().also { songwritingCapabilities = it }
                updateSongwriting { it.copy(engine = capabilities.engine) }
                val base = currentDraft(draftId) ?: return@launch
                val build = SongSpecificationBuilder.build(base, capabilities, sectionId)
                updateSongwriting { it.copy(issues = build.issues) }
                val spec = build.specification
                if (!build.canGenerate || spec == null) {
                    if (resume != null) persistDraftNow(draftId) { d -> d.copy(songwriting = d.songwriting?.copy(pendingJob = null)) }
                    val first = build.blocking.firstOrNull()?.message ?: "These song details can't be written yet."
                    updateSongwriting { it.copy(error = SongwritingError(SongwritingErrorCode.INVALID_REQUEST, first, false)) }
                    return@launch
                }
                // Fix the section structure (and its ids) in the saved draft before anything is sent.
                val prepared = persistDraftNow(draftId) { d ->
                    d.copy(lyrics = d.lyrics.copy(sections = build.sections.mapIndexed { index, section -> section.copy(order = index) }))
                } ?: return@launch
                if (resume == null) updateSongwriting { it.copy(lyricsUndo = MusicMeUndo(draftId, base.lyrics)) }
                val result = songwritingCoordinator.execute(
                    spec, resume,
                    persistPending = { pending ->
                        persistDraftNow(draftId) { d -> d.copy(songwriting = (d.songwriting ?: MusicMeSongwritingState()).copy(pendingJob = pending)) }
                    },
                    onPhase = { phase -> updateSongwriting { it.copy(phase = phase) } },
                )
                persistDraftNow(draftId) { latest -> SongwritingMerger.apply(latest, result.plan, spec, prepared.lyrics.sections) }
                updateSongwriting { it.copy(warnings = result.warnings.map { warning -> warning.message }) }
                val mock = result.plan.metadata.engine == MOCK_ENGINE
                _state.update {
                    it.copy(
                        message = when {
                            mock -> "Mock placeholder lyrics inserted by the development engine. These were not written by AI."
                            sectionId == null -> "Lyrics written. Review and edit them freely."
                            else -> "Section rewritten. Your other sections were kept."
                        },
                    )
                }
            } catch (failure: SongwritingException) {
                updateSongwriting { it.copy(error = SongwritingError(failure.code, failure.userMessage, failure.retryable)) }
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(message = "Lyric writing cancelled. No lyrics were changed.") }
                throw cancelled
            } finally {
                updateSongwriting { it.copy(running = false, phase = null) }
            }
        }
    }

    private fun currentDraft(draftId: String): MusicMeDraft? = _state.value.draft?.takeIf { it.id == draftId }
        ?: _state.value.drafts.firstOrNull { it.id == draftId }

    /**
     * Applies [transform] to the newest copy of a draft and writes it immediately (used for songwriting
     * state that must survive a crash). A pending debounced save for that draft is superseded, not lost:
     * the in-memory draft it would have written is the base of this write.
     */
    private suspend fun persistDraftNow(draftId: String, transform: (MusicMeDraft) -> MusicMeDraft): MusicMeDraft? {
        val active = _state.value.draft?.takeIf { it.id == draftId }
        val unsaved = pendingSave?.takeIf { it.id == draftId }
        if (unsaved != null) { autosaveJob?.cancel(); pendingSave = null }
        val base = active ?: unsaved ?: _state.value.drafts.firstOrNull { it.id == draftId }
            ?: repository.drafts.first().firstOrNull { it.id == draftId } ?: return null
        val updated = transform(base).copy(modifiedAt = System.currentTimeMillis())
        if (_state.value.draft?.id == draftId) _state.update { it.copy(draft = updated, saved = true, draftPersisted = true) }
        repository.upsertDraft(updated)
        return updated
    }

    private fun updateSongwriting(transform: (SongwritingUiState) -> SongwritingUiState) =
        _state.update { it.copy(songwriting = transform(it.songwriting)) }

    override fun onCleared() {
        // viewModelScope is already cancelled here; finish the last debounced write outside it.
        val draft = pendingSave ?: return
        pendingSave = null
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { repository.upsertDraft(draft) }
    }

    private fun flushPendingSave() {
        val draft = pendingSave ?: return
        autosaveJob?.cancel()
        pendingSave = null
        viewModelScope.launch { repository.upsertDraft(draft) }
    }
}
