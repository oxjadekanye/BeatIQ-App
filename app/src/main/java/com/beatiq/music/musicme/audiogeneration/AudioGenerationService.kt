package com.beatiq.music.musicme.audiogeneration

import com.beatiq.music.musicme.model.MusicMeGenerationStatus

/**
 * Abstraction over BeatIQ's self-hosted audio generation service (services/musicme-audio, contract
 * musicme.audio.v1). The app never talks to an audio model directly and never uploads recordings.
 *
 * Release builds receive the remote implementation only when a backend URL is supplied at build time. The backend
 * remains authoritative for account eligibility and generation mode, so a configured APK cannot enable generation.
 */
interface AudioGenerationService {
    /** False when this build has no audio service configured; the UI must then not offer song creation. */
    val isConfigured: Boolean

    suspend fun capabilities(): AudioGenerationCapabilities

    /** Safe to repeat with the same [idempotencyKey]: the service returns the job it already created. */
    suspend fun submit(request: AudioGenerationRequest, idempotencyKey: String): AudioJob

    suspend fun status(jobId: String): AudioJob

    suspend fun cancel(jobId: String)

    /** A short-lived signed URL for one finished track; request a fresh one for every playback or download. */
    suspend fun downloadLink(trackId: String): AudioDownloadLink

    suspend fun deleteTrack(trackId: String)

    suspend fun history(): List<AudioLibraryTrack>

    suspend fun updateTrack(trackId: String, title: String? = null, favorite: Boolean? = null, saved: Boolean? = null): AudioLibraryTrack
}

data class AudioGenerationCapabilities(
    val contractVersion: String,
    val engine: String,
    val modelId: String,
    /** False for the development mock engine, whose output is test tones rather than music. */
    val realAudio: Boolean,
    val languages: Set<String>,
    val vocalTypes: Set<String>,
    val maxSections: Int,
    val minDurationSeconds: Int,
    val maxDurationSeconds: Int,
    val maxTakes: Int,
    val songsPerDay: Int,
    val usedStorageBytes: Long,
    val storageQuotaBytes: Long,
    val generationMode: String = "disabled",
    val generationAllowed: Boolean = false,
    val commercialDistribution: Boolean = false,
    val publicSharing: Boolean = false,
    val languageWarning: String = "",
)

enum class AudioJobState { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }

data class AudioJob(
    val id: String,
    val state: AudioJobState,
    /** Service stage within [state]: queued, rendering, validating, storing, done, failed or cancelled. */
    val stage: String,
    val progress: Int,
    val queuePosition: Int? = null,
    val tracks: List<AudioTrack> = emptyList(),
    val generation: AudioGenerationInfo? = null,
    val error: AudioGenerationException? = null,
) {
    /** How MusicMe's existing generation states present this job (queued, generating, completed, failed). */
    val displayStatus: MusicMeGenerationStatus
        get() = when (state) {
            AudioJobState.QUEUED -> MusicMeGenerationStatus.QUEUED
            AudioJobState.RUNNING -> if (stage == "rendering") MusicMeGenerationStatus.GENERATING else MusicMeGenerationStatus.PROCESSING
            AudioJobState.SUCCEEDED -> MusicMeGenerationStatus.COMPLETED
            AudioJobState.FAILED -> MusicMeGenerationStatus.FAILED
            AudioJobState.CANCELLED -> MusicMeGenerationStatus.CANCELLED
        }
}

data class AudioTrack(
    val id: String,
    val take: Int,
    val format: String,
    val contentType: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val sha256: String,
    val seed: Long,
    val available: Boolean,
    val expiresAt: Long?,
)

/**
 * Reproducibility metadata. [mock] is true for development test tones: never present those as a song.
 * Both flags fail safe: a response that omits them is treated as mock, not real audio.
 */
data class AudioGenerationInfo(
    val engine: String,
    val modelId: String,
    val modelVersion: String,
    val mock: Boolean,
    val realAudio: Boolean,
    val seed: Long,
)

data class AudioDownloadLink(val url: String, val expiresAt: Long, val contentType: String, val sizeBytes: Long)

data class AudioLibraryTrack(
    val id: String,
    val title: String,
    val favorite: Boolean,
    val saved: Boolean,
    val format: String,
    val contentType: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val sha256: String,
    val createdAt: Long,
)

enum class AudioGenerationErrorCode(val retryable: Boolean) {
    NOT_CONFIGURED(false),
    GENERATION_DISABLED(false),
    OFFLINE(true),
    UNAVAILABLE(true),
    TIMEOUT(true),
    RATE_LIMITED(true),
    STORAGE_QUOTA_EXCEEDED(false),
    UNAUTHORIZED(false),
    INVALID_REQUEST(false),
    POLICY_REJECTED(false),
    UNSUPPORTED_LANGUAGE(false),
    AUDIO_QUALITY(true),
    JOB_NOT_FOUND(false),
    TRACK_NOT_FOUND(false),
    LINK_EXPIRED(true),
    CANCELLED(false),
    UNKNOWN(true),
}

class AudioGenerationException(
    val code: AudioGenerationErrorCode,
    /** Plain-language explanation safe to show; never contains user content. */
    val userMessage: String,
    val retryAfterSeconds: Long? = null,
) : Exception(code.name) {
    val retryable: Boolean get() = code.retryable
}

/** Used whenever no audio service URL is configured; this is the fail-closed default for every build. */
object UnconfiguredAudioGenerationService : AudioGenerationService {
    override val isConfigured = false

    private fun unavailable(): Nothing =
        throw AudioGenerationException(AudioGenerationErrorCode.NOT_CONFIGURED, "Song creation isn't available in this build.")

    override suspend fun capabilities(): AudioGenerationCapabilities = unavailable()
    override suspend fun submit(request: AudioGenerationRequest, idempotencyKey: String): AudioJob = unavailable()
    override suspend fun status(jobId: String): AudioJob = unavailable()
    override suspend fun cancel(jobId: String) = Unit
    override suspend fun downloadLink(trackId: String): AudioDownloadLink = unavailable()
    override suspend fun deleteTrack(trackId: String) = unavailable()
    override suspend fun history(): List<AudioLibraryTrack> = unavailable()
    override suspend fun updateTrack(trackId: String, title: String?, favorite: Boolean?, saved: Boolean?): AudioLibraryTrack = unavailable()
}
