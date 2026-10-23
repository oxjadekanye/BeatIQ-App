package com.beatiq.music.musicme.audio

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import com.beatiq.music.musicme.data.MusicMeRepository
import com.beatiq.music.musicme.model.MusicMeVoiceRecording
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.UUID

enum class RecorderState { IDLE, RECORDING, PAUSED, RECORDED, PLAYING, ERROR }

class MusicMeVoiceRecorder(
    private val context: Context,
    private val repository: MusicMeRepository,
) {
    private var recorder: MediaRecorder? = null
    private var player: MediaPlayer? = null
    private var file: File? = null
    private var startedAt = 0L
    private var pauseStartedAt = 0L
    private var pausedDuration = 0L

    private val _state = MutableStateFlow(RecorderState.IDLE)
    val state: StateFlow<RecorderState> = _state
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    fun start(): Boolean = runCatching {
        releaseRecorder()
        val id = UUID.randomUUID().toString()
        file = repository.recordingFile(id)
        recorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        recorder!!.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(128_000)
            setAudioSamplingRate(44_100)
            setOutputFile(file!!.absolutePath)
            prepare()
            start()
        }
        startedAt = SystemClock.elapsedRealtime()
        pausedDuration = 0L
        _state.value = RecorderState.RECORDING
        true
    }.getOrElse {
        _error.value = "Microphone could not start. Check permission and try again."
        releaseRecorder(deleteFile = true)
        _state.value = RecorderState.ERROR
        false
    }

    fun pause() = runCatching {
        if (_state.value == RecorderState.RECORDING) {
            recorder?.pause(); pauseStartedAt = SystemClock.elapsedRealtime(); _state.value = RecorderState.PAUSED
        }
    }.onFailure { failRecording() }

    fun resume() = runCatching {
        if (_state.value == RecorderState.PAUSED) {
            recorder?.resume(); pausedDuration += SystemClock.elapsedRealtime() - pauseStartedAt; _state.value = RecorderState.RECORDING
        }
    }.onFailure { failRecording() }

    fun durationMs(): Long = when (_state.value) {
        RecorderState.RECORDING -> SystemClock.elapsedRealtime() - startedAt - pausedDuration
        RecorderState.PAUSED -> pauseStartedAt - startedAt - pausedDuration
        else -> 0L
    }.coerceAtLeast(0L)

    fun amplitude(): Int = if (_state.value == RecorderState.RECORDING) runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0) else 0

    fun stop(): MusicMeVoiceRecording? {
        val duration = durationMs()
        return runCatching {
            recorder?.stop()
            val savedFile = requireNotNull(file)
            releaseRecorder()
            _state.value = RecorderState.RECORDED
            MusicMeVoiceRecording(
                id = savedFile.nameWithoutExtension.removePrefix("recording_"),
                localFileName = savedFile.name,
                durationMs = duration,
            )
        }.getOrElse { failRecording(); null }
    }

    fun play(recording: MusicMeVoiceRecording, onComplete: () -> Unit = {}) = runCatching {
        releasePlayer()
        val target = repository.recordingFile(recording.id)
        if (!target.exists()) error("Recording missing")
        player = MediaPlayer().apply {
            setDataSource(target.absolutePath)
            setOnCompletionListener { _state.value = RecorderState.RECORDED; releasePlayer(); onComplete() }
            prepare(); start()
        }
        _state.value = RecorderState.PLAYING
    }.onFailure {
        _error.value = "The recording could not be played."
        _state.value = RecorderState.ERROR
    }

    fun stopPlayback() { releasePlayer(); _state.value = RecorderState.RECORDED }

    fun delete(recording: MusicMeVoiceRecording?) {
        releaseAll()
        recording?.let { repository.recordingFile(it.id).delete() }
        file?.delete(); file = null; _state.value = RecorderState.IDLE
    }

    fun releaseAll() { releasePlayer(); if (_state.value == RecorderState.RECORDING || _state.value == RecorderState.PAUSED) runCatching { recorder?.stop() }; releaseRecorder() }
    private fun failRecording() { _error.value = "Recording was interrupted. Your story text is safe."; releaseRecorder(deleteFile = true); _state.value = RecorderState.ERROR }
    private fun releaseRecorder(deleteFile: Boolean = false) { runCatching { recorder?.release() }; recorder = null; if (deleteFile) file?.delete() }
    private fun releasePlayer() { runCatching { player?.release() }; player = null }
}
