package com.beatiq.app.core.player

import android.app.Application
import android.content.ComponentName
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.beatiq.app.data.model.PlaybackUiState
import com.beatiq.app.data.model.Song
import com.beatiq.app.data.repository.MusicRepository
import com.beatiq.app.core.storage.PlaybackPreferences
import com.beatiq.app.services.playback.BeatPlaybackService
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * UI-facing Media3 controller. All public entry points hop to the main thread.
 *
 * TODO(AI): Stream analytics events (session embeddings) to on-device ranking models.
 */
class PlaybackController private constructor(
    private val app: Application,
    private val musicRepository: MusicRepository,
) : PlaybackBridge.Listener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val playbackPreferences = PlaybackPreferences.get(app)
    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    private var ticker: Job? = null

    private var queueSongs: List<Song> = emptyList()

    private var restoreAttempted = false
    private var saveThrottleCounter = 0

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) {
                persistPlaybackState()
            }
            updateFromController()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val id = mediaItem?.mediaId
            if (id != null) {
                scope.launch { musicRepository.markRecentlyPlayed(id) }
            }
            updateFromController()
        }
    }

    init {
        connectIfNeeded()
    }

    fun connectIfNeeded() {
        if (mediaController != null || controllerFuture != null) return
        val token = SessionToken(app, ComponentName(app, BeatPlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                try {
                    val controller = future.get()
                    mediaController = controller
                    controller.addListener(playerListener)
                    startTicker()
                    updateFromController()
                    if (!restoreAttempted) {
                        restoreAttempted = true
                        scope.launch { tryRestoreFromPreferences() }
                    }
                } catch (_: Throwable) {
                    // TODO: Surface degraded playback UX when controller binding fails.
                }
            },
            ContextCompat.getMainExecutor(app),
        )
    }

    suspend fun playQueue(songs: List<Song>, startIndex: Int) = withContext(Dispatchers.Main) {
        playQueueOnMain(songs, startIndex, startPlayback = true)
    }

    suspend fun playSongWithQueue(song: Song, queue: List<Song>) = withContext(Dispatchers.Main) {
        val index = queue.indexOfFirst { it.id == song.id }.takeIf { it >= 0 } ?: 0
        playQueueOnMain(queue, index, startPlayback = true)
    }

    private fun playQueueOnMain(songs: List<Song>, startIndex: Int, startPlayback: Boolean = true) {
        if (songs.isEmpty()) return
        val safeIndex = startIndex.coerceIn(0, songs.lastIndex)
        queueSongs = songs
        connectIfNeeded()
        val ctrl = mediaController ?: return
        ctrl.setMediaItems(songs.map { it.asMediaItem() }, safeIndex, C.TIME_UNSET)
        ctrl.prepare()
        if (startPlayback) {
            ctrl.play()
        } else {
            ctrl.pause()
        }
        scope.launch { musicRepository.markRecentlyPlayed(songs[safeIndex].id) }
        updateFromController()
        persistPlaybackState()
    }

    suspend fun pause() = withContext(Dispatchers.Main) {
        mediaController?.pause()
        persistPlaybackState()
        updateFromController()
    }

    suspend fun resume() = withContext(Dispatchers.Main) {
        mediaController?.play()
        persistPlaybackState()
        updateFromController()
    }

    suspend fun seekTo(positionMs: Long) = withContext(Dispatchers.Main) {
        mediaController?.seekTo(positionMs)
        persistPlaybackState()
        updateFromController()
    }

    suspend fun skipToNext() = withContext(Dispatchers.Main) {
        mediaController?.seekToNext()
        persistPlaybackState()
        updateFromController()
    }

    suspend fun skipToPrevious() = withContext(Dispatchers.Main) {
        mediaController?.seekToPrevious()
        persistPlaybackState()
        updateFromController()
    }

    suspend fun setShuffle(enabled: Boolean) = withContext(Dispatchers.Main) {
        mediaController?.shuffleModeEnabled = enabled
        persistPlaybackState()
        updateFromController()
    }

    suspend fun setRepeatMode(repeatMode: Int) = withContext(Dispatchers.Main) {
        mediaController?.repeatMode = repeatMode
        persistPlaybackState()
        updateFromController()
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(320L)
                val ctrl = mediaController ?: continue
                if (ctrl.isPlaying) {
                    saveThrottleCounter++
                    if (saveThrottleCounter >= 16) {
                        saveThrottleCounter = 0
                        persistPlaybackState()
                    }
                    _state.update { st ->
                        st.copy(
                            positionMs = ctrl.currentPosition,
                            durationMs = ctrl.duration.coerceAtLeast(st.durationMs),
                        )
                    }
                } else {
                    saveThrottleCounter = 0
                }
            }
        }
    }

    private fun persistPlaybackState() {
        mediaController?.let { playbackPreferences.saveFromPlayer(it) }
    }

    private suspend fun tryRestoreFromPreferences() {
        val ctrl = mediaController ?: return
        if (ctrl.mediaItemCount > 0 || ctrl.playbackState != Player.STATE_IDLE) return
        val snapshot = playbackPreferences.readSnapshot() ?: return
        if (snapshot.queueIds.isEmpty()) return

        val currentId = snapshot.queueIds.getOrNull(
            snapshot.queueIndex.coerceIn(0, snapshot.queueIds.lastIndex),
        )
        val songs = withContext(Dispatchers.Default) {
            snapshot.queueIds.mapNotNull { id -> musicRepository.getSongById(id) }
        }
        if (songs.isEmpty()) return

        val startIndex = currentId?.let { id -> songs.indexOfFirst { it.id == id }.takeIf { it >= 0 } }
            ?: snapshot.queueIndex.coerceIn(0, songs.lastIndex)

        playQueueOnMain(songs, startIndex, startPlayback = false)
        val c = mediaController ?: return
        c.seekTo(snapshot.positionMs.coerceAtLeast(0L))
        c.shuffleModeEnabled = snapshot.shuffle
        c.repeatMode = snapshot.repeatMode
        if (snapshot.wasPlaying) {
            c.play()
        }
        updateFromController()
        persistPlaybackState()
    }

    private fun updateFromController() {
        val ctrl = mediaController ?: return
        val currentItem = ctrl.currentMediaItem
        val currentSong = currentItem?.mediaId?.let { id -> queueSongs.find { it.id == id } }
        val index = currentSong?.let { queueSongs.indexOf(it) }?.takeIf { it >= 0 } ?: ctrl.currentMediaItemIndex

        _state.update {
            PlaybackUiState(
                currentSong = currentSong,
                isPlaying = ctrl.isPlaying,
                positionMs = ctrl.currentPosition,
                durationMs = ctrl.duration.coerceAtLeast(0L),
                queue = queueSongs,
                queueIndex = index.coerceAtLeast(0),
                shuffle = ctrl.shuffleModeEnabled,
                repeatMode = ctrl.repeatMode,
            )
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (PlaybackBridge.mapEnded(playbackState)) {
            val id = _state.value.currentSong?.id
            if (id != null) {
                scope.launch { musicRepository.incrementPlayCount(id) }
            }
            persistPlaybackState()
        }
        updateFromController()
    }

    companion object {
        @Volatile
        private var instance: PlaybackController? = null

        fun get(app: Application, musicRepository: MusicRepository): PlaybackController {
            val existing = instance
            if (existing != null) return existing
            return synchronized(this) {
                instance?.let { return it }
                val created = PlaybackController(app, musicRepository)
                PlaybackBridge.listener = created
                instance = created
                created
            }
        }
    }
}
