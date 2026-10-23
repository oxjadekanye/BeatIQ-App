package com.beatiq.music.musicme.audiogeneration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.UUID

/** A song request that has been (or is about to be) sent; stored so it can be resumed after process death. */
data class PendingAudioJob(val draftId: String, val idempotencyKey: String, val jobId: String? = null, val createdAt: Long = System.currentTimeMillis())

/**
 * Runs one song request against [AudioGenerationService]: submit (idempotently), poll until finished,
 * and accept only real, validated audio. The idempotency key is persisted before anything is sent, so a
 * crash or retry never creates a second song; cancellation tells the service to stop.
 */
class AudioGenerationCoordinator(
    private val service: AudioGenerationService,
    private val sleep: suspend (Long) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun execute(
        request: AudioGenerationRequest,
        resume: PendingAudioJob?,
        persistPending: suspend (PendingAudioJob?) -> Unit,
        onProgress: (AudioJob) -> Unit,
    ): AudioJob {
        var pending = resume ?: PendingAudioJob(request.draftId, "song-${UUID.randomUUID()}").also { persistPending(it) }
        var jobId: String? = pending.jobId
        try {
            var job = if (jobId != null) retrying { service.status(jobId!!) } else retrying { service.submit(request, pending.idempotencyKey) }
            if (jobId == null) {
                jobId = job.id
                pending = pending.copy(jobId = job.id)
                persistPending(pending)
            }
            val giveUpAt = clock() + MAX_WAIT_MS
            var interval = FIRST_POLL_MS
            while (job.state == AudioJobState.QUEUED || job.state == AudioJobState.RUNNING) {
                onProgress(job)
                if (clock() > giveUpAt) {
                    throw AudioGenerationException(AudioGenerationErrorCode.TIMEOUT, "The song took too long to create. Try again later.")
                }
                sleep(interval)
                interval = (interval * 3 / 2).coerceAtMost(MAX_POLL_MS)
                job = retrying { service.status(job.id) }
            }
            onProgress(job)
            persistPending(null)
            return when (job.state) {
                AudioJobState.SUCCEEDED -> requireRealSong(job)
                AudioJobState.CANCELLED -> throw AudioGenerationException(AudioGenerationErrorCode.CANCELLED, "Song creation was cancelled.")
                else -> throw job.error ?: AudioGenerationException(AudioGenerationErrorCode.UNKNOWN, "The song couldn't be created. Try again.")
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                jobId?.let { runCatching { service.cancel(it) } }
                persistPending(null)
            }
            throw cancelled
        } catch (failure: AudioGenerationException) {
            // Keep the pending record only when it can still finish on the server (e.g. the phone went offline).
            if (!failure.retryable || jobId == null) persistPending(null)
            throw failure
        }
    }

    /** Test tones from the development mock engine, or anything unlabelled, are never presented as a song. */
    private fun requireRealSong(job: AudioJob): AudioJob {
        val generation = job.generation
        if (generation == null || generation.mock || !generation.realAudio) {
            throw AudioGenerationException(AudioGenerationErrorCode.NOT_CONFIGURED, "This audio service returned development test audio, not a song.")
        }
        if (job.tracks.none { it.available }) {
            throw AudioGenerationException(AudioGenerationErrorCode.TRACK_NOT_FOUND, "The finished song isn't available any more. Try again.")
        }
        return job
    }

    private suspend fun <T> retrying(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (failure: AudioGenerationException) {
                val transient = failure.code in TRANSIENT
                if (!transient || ++attempt > MAX_RETRIES) throw failure
                sleep((failure.retryAfterSeconds?.times(1000) ?: (2_000L shl (attempt - 1))).coerceAtMost(30_000))
            }
        }
    }

    companion object {
        const val FIRST_POLL_MS = 2_000L
        const val MAX_POLL_MS = 5_000L
        const val MAX_WAIT_MS = 15 * 60 * 1000L
        const val MAX_RETRIES = 4
        private val TRANSIENT = setOf(AudioGenerationErrorCode.OFFLINE, AudioGenerationErrorCode.UNAVAILABLE, AudioGenerationErrorCode.TIMEOUT, AudioGenerationErrorCode.RATE_LIMITED)
    }
}
