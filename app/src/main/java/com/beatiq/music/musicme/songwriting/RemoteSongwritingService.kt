package com.beatiq.music.musicme.songwriting

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Client for BeatIQ's own songwriting service (services/musicme-songwriter). Requests carry the
 * signed-in user's access token, so jobs are scoped to that account server-side; no model or
 * provider credential exists in the app.
 */
class RemoteSongwritingService(
    private val baseUrl: HttpUrl,
    private val accessToken: () -> String?,
    private val client: OkHttpClient = defaultClient,
) : SongwritingService {
    override val isConfigured = true

    override suspend fun capabilities(): SongwritingCapabilities {
        val json = call(Request.Builder().url(endpoint("capabilities")).get())
        val languages = json.getJSONArray("languages")
        return SongwritingCapabilities(
            contractVersion = json.getString("contract_version"),
            modelId = json.getString("model_id"),
            engine = json.getString("engine"),
            languages = List(languages.length()) { languages.getString(it) }.toSet(),
            maxSections = json.optInt("max_sections", SongSpecificationBuilder.DEFAULT_MAX_SECTIONS),
            supportsSectionRegeneration = json.optBoolean("supports_section_regeneration", false),
        )
    }

    override suspend fun submit(spec: SongSpecification, idempotencyKey: String): SongwritingJob {
        val body = JSONObject().put("contract_version", spec.contractVersion).put("specification", SongwritingJson.encodeSpecification(spec))
        return decodeJob(
            call(
                Request.Builder().url(endpoint("jobs")).header("Idempotency-Key", idempotencyKey)
                    .post(body.toString().toRequestBody(JSON)),
            ),
        )
    }

    override suspend fun status(jobId: String): SongwritingJob = decodeJob(call(Request.Builder().url(endpoint("jobs", jobId)).get()))

    override suspend fun cancel(jobId: String) {
        call(Request.Builder().url(endpoint("jobs", jobId, "cancel")).post(ByteArray(0).toRequestBody(JSON)))
    }

    private fun endpoint(vararg segments: String): HttpUrl =
        baseUrl.newBuilder().addPathSegments("v1/songwriting").apply { segments.forEach { addPathSegment(it) } }.build()

    private suspend fun call(builder: Request.Builder): JSONObject = withContext(Dispatchers.IO) {
        val token = accessToken()?.takeIf { it.isNotBlank() }
            ?: throw SongwritingException(SongwritingErrorCode.UNAUTHORIZED, "Sign in again to use AI songwriting.")
        val request = builder.header("Authorization", "Bearer $token").header("Accept", "application/json").build()
        val response = try {
            client.newCall(request).execute()
        } catch (error: UnknownHostException) {
            throw SongwritingException(SongwritingErrorCode.OFFLINE, "You appear to be offline. Your draft is saved; try again when connected.")
        } catch (error: ConnectException) {
            throw SongwritingException(SongwritingErrorCode.UNAVAILABLE, "The songwriting service isn't reachable right now. Your draft is saved.")
        } catch (error: SocketTimeoutException) {
            throw SongwritingException(SongwritingErrorCode.TIMEOUT, "The songwriting service didn't respond in time. Your draft is saved.")
        } catch (error: IOException) {
            throw SongwritingException(SongwritingErrorCode.UNAVAILABLE, "The songwriting service couldn't be reached. Your draft is saved.")
        }
        response.use { parse(it) }
    }

    private fun parse(response: Response): JSONObject {
        val text = response.body?.string().orEmpty()
        val json = runCatching { JSONObject(text) }.getOrNull()
        if (response.isSuccessful) {
            return json ?: throw SongwritingException(SongwritingErrorCode.UNAVAILABLE, "The songwriting service sent an unreadable response.")
        }
        throw errorFrom(response.code, json?.optJSONObject("error"), response.header("Retry-After")?.toLongOrNull())
    }

    private fun decodeJob(json: JSONObject): SongwritingJob {
        val state = runCatching { JobState.valueOf(json.getString("state").uppercase()) }.getOrElse {
            throw SongwritingException(SongwritingErrorCode.UNAVAILABLE, "The songwriting service sent an unreadable response.")
        }
        return SongwritingJob(
            id = json.getString("id"),
            state = state,
            plan = json.optJSONObject("plan")?.let(SongwritingJson::decodePlan),
            error = json.optJSONObject("error")?.let { errorFrom(200, it, null) },
        )
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        private val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        }

        /** Returns null (feature off) for blank, malformed, or non-HTTPS URLs other than loopback development hosts. */
        fun parseBaseUrl(raw: String): HttpUrl? {
            val url = raw.trim().takeIf { it.isNotEmpty() }?.toHttpUrlOrNull() ?: return null
            val loopback = url.host == "localhost" || url.host == "127.0.0.1" || url.host == "10.0.2.2"
            return url.takeIf { it.isHttps || loopback }
        }

        fun errorFrom(status: Int, error: JSONObject?, retryAfter: Long?): SongwritingException {
            val code = error?.optString("code")?.let { raw -> runCatching { SongwritingErrorCode.valueOf(raw) }.getOrNull() }
                ?: when (status) {
                    401, 403 -> SongwritingErrorCode.UNAUTHORIZED
                    404 -> SongwritingErrorCode.JOB_NOT_FOUND
                    409, 400, 413, 422 -> SongwritingErrorCode.INVALID_REQUEST
                    429 -> SongwritingErrorCode.RATE_LIMITED
                    in 500..599 -> SongwritingErrorCode.UNAVAILABLE
                    else -> SongwritingErrorCode.UNKNOWN
                }
            // Service messages are shown only for codes where its specific explanation helps.
            val serverMessage = error?.optString("message")?.takeIf { it.isNotBlank() && it.length <= 240 }
            val message = when (code) {
                SongwritingErrorCode.POLICY_REJECTED, SongwritingErrorCode.UNSUPPORTED_LANGUAGE, SongwritingErrorCode.INVALID_REQUEST ->
                    serverMessage ?: defaultMessage(code)
                else -> defaultMessage(code)
            }
            return SongwritingException(code, message, retryAfter)
        }

        fun defaultMessage(code: SongwritingErrorCode): String = when (code) {
            SongwritingErrorCode.NOT_CONFIGURED -> "AI songwriting isn't connected in this build."
            SongwritingErrorCode.OFFLINE -> "You appear to be offline. Your draft is saved; try again when connected."
            SongwritingErrorCode.UNAVAILABLE -> "The songwriting service is unavailable right now. Your draft is saved."
            SongwritingErrorCode.TIMEOUT -> "The songwriting service is taking too long. Please try again."
            SongwritingErrorCode.RATE_LIMITED -> "You've reached the songwriting limit for now. Please wait a little and try again."
            SongwritingErrorCode.UNAUTHORIZED -> "Sign in again to use AI songwriting."
            SongwritingErrorCode.INVALID_REQUEST -> "The songwriting service couldn't accept these song details."
            SongwritingErrorCode.POLICY_REJECTED -> "This request can't be written. MusicMe only writes original lyrics without imitating real artists."
            SongwritingErrorCode.UNSUPPORTED_LANGUAGE -> "The songwriting model can't write reliably in the chosen language yet."
            SongwritingErrorCode.MODEL_OUTPUT_INVALID -> "The generated lyrics didn't meet quality checks, so they weren't applied. Try again."
            SongwritingErrorCode.JOB_NOT_FOUND -> "The earlier request is no longer available. Please try again."
            SongwritingErrorCode.CANCELLED -> "The request was cancelled."
            SongwritingErrorCode.UNKNOWN -> "Something went wrong while writing lyrics. Your draft is saved."
        }
    }
}
