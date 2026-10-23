package com.beatiq.music.musicme.audiogeneration

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Client for BeatIQ's own audio service (services/musicme-audio). Requests carry the signed-in user's
 * access token, so jobs and tracks are scoped to that account server-side. Availability in every build is controlled
 * by the configured backend URL and the backend's fail-closed generation policy (see [AudioGenerationDependencies]).
 */
class RemoteAudioGenerationService(
    private val baseUrl: HttpUrl,
    private val accessToken: () -> String?,
    private val client: OkHttpClient = defaultClient,
) : AudioGenerationService {
    override val isConfigured = true

    override suspend fun capabilities(): AudioGenerationCapabilities {
        val json = call(Request.Builder().url(endpoint("capabilities")).get())
        val storage = json.optJSONObject("storage") ?: JSONObject()
        return AudioGenerationCapabilities(
            contractVersion = json.getString("contract_version"),
            engine = json.getString("engine"),
            modelId = json.getString("model_id"),
            realAudio = json.optBoolean("real_audio", false),
            languages = json.getJSONArray("languages").strings().toSet(),
            vocalTypes = json.optJSONArray("vocal_types")?.strings()?.toSet().orEmpty(),
            maxSections = json.optInt("max_sections", 16),
            minDurationSeconds = json.optInt("min_duration_seconds", 30),
            maxDurationSeconds = json.optInt("max_duration_seconds", 240),
            maxTakes = json.optInt("max_takes", 1),
            songsPerDay = json.optInt("songs_per_day", 0),
            usedStorageBytes = storage.optLong("used_bytes", 0),
            storageQuotaBytes = storage.optLong("quota_bytes", 0),
            generationMode = json.optString("generation_mode", "disabled"),
            generationAllowed = json.optBoolean("generation_allowed", false),
            commercialDistribution = json.optBoolean("commercial_distribution", false),
            publicSharing = json.optBoolean("public_sharing", false),
            languageWarning = json.optString("language_warning"),
        )
    }

    override suspend fun submit(request: AudioGenerationRequest, idempotencyKey: String): AudioJob =
        decodeJob(call(Request.Builder().url(endpoint("jobs")).header("Idempotency-Key", idempotencyKey).post(request.toJson().toString().toRequestBody(JSON))))

    override suspend fun status(jobId: String): AudioJob = decodeJob(call(Request.Builder().url(endpoint("jobs", jobId)).get()))

    override suspend fun cancel(jobId: String) {
        call(Request.Builder().url(endpoint("jobs", jobId, "cancel")).post(ByteArray(0).toRequestBody(JSON)))
    }

    override suspend fun downloadLink(trackId: String): AudioDownloadLink {
        val json = call(Request.Builder().url(endpoint("tracks", trackId, "link")).post(ByteArray(0).toRequestBody(JSON)))
        val url = json.getString("url").toHttpUrlOrNull()
        // Only follow links to the configured service origin over HTTPS (or loopback in development).
        if (url == null || url.host != baseUrl.host || url.scheme != baseUrl.scheme || url.port != baseUrl.port ||
            !(url.isHttps || isLoopback(url))
        ) {
            throw AudioGenerationException(AudioGenerationErrorCode.UNAVAILABLE, "The audio service sent an unexpected download link.")
        }
        return AudioDownloadLink(url.toString(), json.getLong("expires_at"), json.getString("content_type"), json.getLong("size_bytes"))
    }

    override suspend fun deleteTrack(trackId: String) {
        call(Request.Builder().url(endpoint("tracks", trackId)).delete())
    }

    override suspend fun history(): List<AudioLibraryTrack> =
        decodeHistory(call(Request.Builder().url(endpoint("tracks")).get()).optJSONArray("tracks") ?: JSONArray())

    override suspend fun updateTrack(trackId: String, title: String?, favorite: Boolean?, saved: Boolean?): AudioLibraryTrack {
        val update = JSONObject()
        title?.let { update.put("title", it) }
        favorite?.let { update.put("favorite", it) }
        saved?.let { update.put("saved", it) }
        val json = call(Request.Builder().url(endpoint("tracks", trackId)).patch(update.toString().toRequestBody(JSON)))
        return decodeLibraryTrack(json.getJSONObject("track"))
    }

    private fun endpoint(vararg segments: String): HttpUrl =
        baseUrl.newBuilder().addPathSegments("v1/audio").apply { segments.forEach { addPathSegment(it) } }.build()

    private suspend fun call(builder: Request.Builder): JSONObject = withContext(Dispatchers.IO) {
        val token = accessToken()?.takeIf { it.isNotBlank() }
            ?: throw AudioGenerationException(AudioGenerationErrorCode.UNAUTHORIZED, defaultMessage(AudioGenerationErrorCode.UNAUTHORIZED))
        val request = builder.header("Authorization", "Bearer $token").header("Accept", "application/json").build()
        val response = try {
            client.newCall(request).execute()
        } catch (error: UnknownHostException) {
            throw AudioGenerationException(AudioGenerationErrorCode.OFFLINE, defaultMessage(AudioGenerationErrorCode.OFFLINE))
        } catch (error: ConnectException) {
            throw AudioGenerationException(AudioGenerationErrorCode.UNAVAILABLE, defaultMessage(AudioGenerationErrorCode.UNAVAILABLE))
        } catch (error: SocketTimeoutException) {
            throw AudioGenerationException(AudioGenerationErrorCode.TIMEOUT, defaultMessage(AudioGenerationErrorCode.TIMEOUT))
        } catch (error: IOException) {
            throw AudioGenerationException(AudioGenerationErrorCode.UNAVAILABLE, defaultMessage(AudioGenerationErrorCode.UNAVAILABLE))
        }
        response.use { parse(it) }
    }

    private fun parse(response: Response): JSONObject {
        val text = response.body?.string().orEmpty()
        val json = runCatching { JSONObject(text) }.getOrNull()
        if (response.isSuccessful) {
            return json ?: throw AudioGenerationException(AudioGenerationErrorCode.UNAVAILABLE, "The audio service sent an unreadable response.")
        }
        throw errorFrom(response.code, json?.optJSONObject("error"), response.header("Retry-After")?.toLongOrNull())
    }

    private fun decodeJob(json: JSONObject): AudioJob {
        val state = runCatching { AudioJobState.valueOf(json.getString("state").uppercase()) }.getOrElse {
            throw AudioGenerationException(AudioGenerationErrorCode.UNAVAILABLE, "The audio service sent an unreadable response.")
        }
        val result = json.optJSONObject("result")
        val tracks = result?.optJSONArray("tracks")?.let { array ->
            List(array.length()) { index ->
                val t = array.getJSONObject(index)
                AudioTrack(
                    id = t.getString("id"), take = t.getInt("take"), format = t.getString("format"),
                    contentType = t.getString("content_type"), sizeBytes = t.getLong("size_bytes"), durationMs = t.getLong("duration_ms"),
                    sha256 = t.getString("sha256"), seed = t.getLong("seed"), available = t.optBoolean("available", false),
                    expiresAt = if (t.isNull("expires_at")) null else t.optLong("expires_at"),
                )
            }
        }.orEmpty()
        val generation = result?.optJSONObject("generation")?.let { g ->
            val mock = g.optBoolean("mock", true)
            AudioGenerationInfo(
                g.getString("engine"), g.getString("model_id"), g.optString("model_version"),
                mock = mock, realAudio = !mock && g.optBoolean("real_audio", false), seed = g.optLong("seed"),
            )
        }
        return AudioJob(
            id = json.getString("id"),
            state = state,
            stage = json.optString("stage", state.name.lowercase()),
            progress = json.optInt("progress", 0),
            queuePosition = if (json.isNull("queue_position")) null else json.optInt("queue_position"),
            tracks = tracks,
            generation = generation,
            error = json.optJSONObject("error")?.let { errorFrom(200, it, null) },
        )
    }

    private fun decodeHistory(array: JSONArray): List<AudioLibraryTrack> =
        List(array.length()) { decodeLibraryTrack(array.getJSONObject(it)) }

    private fun decodeLibraryTrack(track: JSONObject) = AudioLibraryTrack(
        id = track.getString("id"), title = track.optString("title"), favorite = track.optBoolean("favorite"),
        saved = track.optBoolean("saved"), format = track.getString("format"), contentType = track.getString("content_type"),
        sizeBytes = track.getLong("size_bytes"), durationMs = track.getLong("duration_ms"), sha256 = track.getString("sha256"),
        createdAt = track.getLong("created_at"),
    )

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        private val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        }

        private fun isLoopback(url: HttpUrl) = url.host == "localhost" || url.host == "127.0.0.1" || url.host == "10.0.2.2"

        /** Returns null (feature off) for blank, malformed, or non-HTTPS URLs other than loopback development hosts. */
        fun parseBaseUrl(raw: String): HttpUrl? {
            val url = raw.trim().takeIf { it.isNotEmpty() }?.toHttpUrlOrNull() ?: return null
            return url.takeIf { it.isHttps || isLoopback(it) }
        }

        fun errorFrom(status: Int, error: JSONObject?, retryAfter: Long?): AudioGenerationException {
            val raw = error?.optString("code").orEmpty()
            val code = runCatching { AudioGenerationErrorCode.valueOf(raw) }.getOrNull()
                ?: when {
                    raw.startsWith("AUDIO_") -> AudioGenerationErrorCode.AUDIO_QUALITY
                    raw in setOf("WORKER_LOST", "ENGINE_UNAVAILABLE", "EXPIRED", "ENGINE_REJECTED") ||
                        raw.startsWith("PROVIDER_") || raw.startsWith("BUDGET_") -> AudioGenerationErrorCode.UNAVAILABLE
                    raw == "LINK_INVALID" -> AudioGenerationErrorCode.LINK_EXPIRED
                    status == 401 || status == 403 -> AudioGenerationErrorCode.UNAUTHORIZED
                    status == 404 -> AudioGenerationErrorCode.JOB_NOT_FOUND
                    status == 410 -> AudioGenerationErrorCode.LINK_EXPIRED
                    status in setOf(400, 409, 413, 422) -> AudioGenerationErrorCode.INVALID_REQUEST
                    status == 429 -> AudioGenerationErrorCode.RATE_LIMITED
                    status in 500..599 -> AudioGenerationErrorCode.UNAVAILABLE
                    else -> AudioGenerationErrorCode.UNKNOWN
                }
            // Service messages are shown only where its specific explanation helps; they never contain user content.
            val serverMessage = error?.optString("message")?.takeIf { it.isNotBlank() && it.length <= 240 }
            val message = when (code) {
                AudioGenerationErrorCode.POLICY_REJECTED, AudioGenerationErrorCode.UNSUPPORTED_LANGUAGE,
                AudioGenerationErrorCode.INVALID_REQUEST, AudioGenerationErrorCode.AUDIO_QUALITY -> serverMessage ?: defaultMessage(code)
                else -> defaultMessage(code)
            }
            return AudioGenerationException(code, message, retryAfter)
        }

        fun defaultMessage(code: AudioGenerationErrorCode): String = when (code) {
            AudioGenerationErrorCode.NOT_CONFIGURED -> "Song creation isn't available in this build."
            AudioGenerationErrorCode.GENERATION_DISABLED -> "Song creation isn't enabled for this account."
            AudioGenerationErrorCode.OFFLINE -> "You appear to be offline. Your lyrics are saved; try again when connected."
            AudioGenerationErrorCode.UNAVAILABLE -> "Song creation is unavailable right now. Your lyrics are saved."
            AudioGenerationErrorCode.TIMEOUT -> "Song creation is taking too long to respond. Please try again."
            AudioGenerationErrorCode.RATE_LIMITED -> "You've reached the song limit for now. Please try again later."
            AudioGenerationErrorCode.STORAGE_QUOTA_EXCEEDED -> "Your song storage is full. Delete older songs to make room."
            AudioGenerationErrorCode.UNAUTHORIZED -> "Sign in again to create songs with MusicMe."
            AudioGenerationErrorCode.INVALID_REQUEST -> "These song details couldn't be accepted."
            AudioGenerationErrorCode.POLICY_REJECTED -> "MusicMe creates original songs and can't imitate real artists or use recordings."
            AudioGenerationErrorCode.UNSUPPORTED_LANGUAGE -> "Sung vocals aren't available in the chosen language yet."
            AudioGenerationErrorCode.AUDIO_QUALITY -> "The song couldn't be created this time. Please try again."
            AudioGenerationErrorCode.JOB_NOT_FOUND -> "The earlier request is no longer available."
            AudioGenerationErrorCode.TRACK_NOT_FOUND -> "This song is no longer available."
            AudioGenerationErrorCode.LINK_EXPIRED -> "The download link expired. Please try again."
            AudioGenerationErrorCode.CANCELLED -> "Song creation was cancelled."
            AudioGenerationErrorCode.UNKNOWN -> "Something went wrong while creating your song. Your lyrics are saved."
        }

        private fun JSONArray.strings(): List<String> = List(length()) { getString(it) }
    }
}
