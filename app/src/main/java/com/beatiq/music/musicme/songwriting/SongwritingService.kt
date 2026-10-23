package com.beatiq.music.musicme.songwriting

/** Abstraction over BeatIQ's self-hosted songwriting service. The app never talks to a model directly. */
interface SongwritingService {
    /** False when this build has no service configured; the UI then says so instead of offering generation. */
    val isConfigured: Boolean

    suspend fun capabilities(): SongwritingCapabilities

    /** Safe to repeat with the same [idempotencyKey]: the service returns the job it already created. */
    suspend fun submit(spec: SongSpecification, idempotencyKey: String): SongwritingJob

    suspend fun status(jobId: String): SongwritingJob

    suspend fun cancel(jobId: String)
}

data class SongwritingCapabilities(
    val contractVersion: String,
    val modelId: String,
    val engine: String,
    /** Lyric language ids (catalogue ids such as "english", "yoruba") the configured model handles well. */
    val languages: Set<String>,
    val maxSections: Int,
    val supportsSectionRegeneration: Boolean,
)

enum class JobState { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }

data class SongwritingJob(
    val id: String,
    val state: JobState,
    val plan: SongwritingPlan? = null,
    val error: SongwritingException? = null,
)

enum class SongwritingErrorCode(val retryable: Boolean) {
    NOT_CONFIGURED(false),
    OFFLINE(true),
    UNAVAILABLE(true),
    TIMEOUT(true),
    RATE_LIMITED(true),
    UNAUTHORIZED(false),
    INVALID_REQUEST(false),
    POLICY_REJECTED(false),
    UNSUPPORTED_LANGUAGE(false),
    MODEL_OUTPUT_INVALID(true),
    JOB_NOT_FOUND(true),
    CANCELLED(false),
    UNKNOWN(true),
}

class SongwritingException(
    val code: SongwritingErrorCode,
    /** Plain-language explanation safe to show; never contains user content. */
    val userMessage: String,
    val retryAfterSeconds: Long? = null,
) : Exception(code.name) {
    val retryable: Boolean get() = code.retryable
}

/** Used whenever no songwriting service URL is configured (the default for every build). */
object UnconfiguredSongwritingService : SongwritingService {
    override val isConfigured = false

    private fun unavailable(): Nothing =
        throw SongwritingException(SongwritingErrorCode.NOT_CONFIGURED, "AI songwriting isn't connected in this build.")

    override suspend fun capabilities(): SongwritingCapabilities = unavailable()
    override suspend fun submit(spec: SongSpecification, idempotencyKey: String): SongwritingJob = unavailable()
    override suspend fun status(jobId: String): SongwritingJob = unavailable()
    override suspend fun cancel(jobId: String) = Unit
}
