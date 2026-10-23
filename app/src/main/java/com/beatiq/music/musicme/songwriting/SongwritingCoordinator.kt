package com.beatiq.music.musicme.songwriting

import com.beatiq.music.musicme.model.MusicMePendingSongwritingJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.UUID

enum class SongwritingPhase { SUBMITTING, RETRYING, QUEUED, WRITING, CHECKING, RESUMING }

data class SongwritingResult(val plan: SongwritingPlan, val warnings: List<PlanIssue>)

/**
 * Runs one songwriting request end to end. The pending job is recorded (via [persistPending]) before
 * any network call and updated once the service assigns an id, so a request interrupted by process
 * death resumes instead of being sent twice. Every failure surfaces as a [SongwritingException].
 */
class SongwritingCoordinator(
    private val service: SongwritingService,
    private val log: SongwritingLog,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val clock: () -> Long = System::currentTimeMillis,
    private val newKey: () -> String = { UUID.randomUUID().toString() },
    private val pollIntervalMs: Long = 1_500,
    private val maxWaitMs: Long = 180_000,
    private val maxTransientRetries: Int = 3,
) {
    suspend fun execute(
        spec: SongSpecification,
        resume: MusicMePendingSongwritingJob?,
        persistPending: suspend (MusicMePendingSongwritingJob?) -> Unit,
        onPhase: (SongwritingPhase) -> Unit,
    ): SongwritingResult {
        var pending = resume ?: MusicMePendingSongwritingJob(null, newKey(), spec.regenerateSlotId, clock())
        var jobId: String? = pending.jobId
        try {
            persistPending(pending)
            if (jobId == null) {
                onPhase(if (resume != null) SongwritingPhase.RESUMING else SongwritingPhase.SUBMITTING)
                val job = withTransientRetries(onPhase) { service.submit(spec, pending.idempotencyKey) }
                jobId = job.id
                pending = pending.copy(jobId = job.id)
                persistPending(pending)
                log.event("submitted", "draft_id" to spec.draftId, "job_id" to job.id, "sections" to spec.writableSlots.size)
            } else {
                onPhase(SongwritingPhase.RESUMING)
                log.event("resumed", "draft_id" to spec.draftId, "job_id" to jobId)
            }

            val plan = awaitPlan(jobId, onPhase)
            onPhase(SongwritingPhase.CHECKING)
            val validation = SongwritingPlanValidator.validate(plan, spec)
            if (!validation.valid) {
                log.event("rejected_output", "job_id" to jobId, "code" to validation.errors.first().code, "issues" to validation.errors.size)
                throw SongwritingException(SongwritingErrorCode.MODEL_OUTPUT_INVALID, validation.errors.first().message)
            }
            persistPending(null)
            log.event("completed", "job_id" to jobId, "engine" to plan.metadata.engine)
            return SongwritingResult(plan, validation.warnings)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                jobId?.let { id -> runCatching { service.cancel(id) } }
                persistPending(null)
            }
            log.event("cancelled", "job_id" to jobId)
            throw cancelled
        } catch (failure: SongwritingException) {
            // A failed job can't be resumed; the next attempt gets a fresh idempotency key.
            withContext(NonCancellable) { persistPending(null) }
            log.event("failed", "job_id" to jobId, "code" to failure.code)
            throw failure
        }
    }

    private suspend fun awaitPlan(jobId: String, onPhase: (SongwritingPhase) -> Unit): SongwritingPlan {
        val deadline = clock() + maxWaitMs
        while (true) {
            val job = withTransientRetries(onPhase) { service.status(jobId) }
            when (job.state) {
                JobState.QUEUED -> onPhase(SongwritingPhase.QUEUED)
                JobState.RUNNING -> onPhase(SongwritingPhase.WRITING)
                JobState.SUCCEEDED -> return job.plan
                    ?: throw SongwritingException(SongwritingErrorCode.MODEL_OUTPUT_INVALID, "The songwriting service finished without a song plan.")
                JobState.FAILED -> throw job.error
                    ?: SongwritingException(SongwritingErrorCode.UNKNOWN, "The songwriting service couldn't finish these lyrics.")
                JobState.CANCELLED -> throw SongwritingException(SongwritingErrorCode.CANCELLED, "The request was cancelled.")
            }
            if (clock() >= deadline) {
                runCatching { service.cancel(jobId) }
                throw SongwritingException(SongwritingErrorCode.TIMEOUT, "The songwriting service is taking too long. Please try again.")
            }
            sleep(pollIntervalMs)
        }
    }

    private suspend fun <T> withTransientRetries(onPhase: (SongwritingPhase) -> Unit, call: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return call()
            } catch (error: SongwritingException) {
                val transient = error.code in TRANSIENT
                if (!transient || attempt >= maxTransientRetries) throw error
                attempt++
                onPhase(SongwritingPhase.RETRYING)
                log.event("retry", "code" to error.code, "attempt" to attempt)
                sleep(error.retryAfterSeconds?.times(1_000) ?: (1_000L shl (attempt - 1)))
            }
        }
    }

    private companion object {
        val TRANSIENT = setOf(SongwritingErrorCode.OFFLINE, SongwritingErrorCode.UNAVAILABLE, SongwritingErrorCode.TIMEOUT, SongwritingErrorCode.RATE_LIMITED)
    }
}
