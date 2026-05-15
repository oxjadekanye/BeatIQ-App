package com.beatiq.music.core.identify

import com.beatiq.music.BuildConfig
import com.beatiq.music.core.network.BeatIQApiFailureLogger
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class IdentifyMatch(
    val artist: String,
    val title: String,
    val album: String?,
    val artworkUrl: String?,
    val previewDownloadUrl: String?,
    val appleMusicUrl: String?,
    val spotifyUrl: String?,
)

/**
 * Identifies a short audio sample via [AudD](https://docs.audd.io/).
 * Set `audd.api.token` in `local.properties` (see README in code comments).
 */
object AuddIdentifyClient {

    private val http =
        OkHttpClient.Builder()
            .addInterceptor(BeatIQApiFailureLogger)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()

    fun isConfigured(): Boolean = BuildConfig.AUDD_API_TOKEN.isNotBlank()

    suspend fun identify(audioFile: File): Result<IdentifyMatch> =
        withContext(Dispatchers.IO) {
            val token = BuildConfig.AUDD_API_TOKEN
            if (token.isBlank()) {
                return@withContext Result.failure(IllegalStateException("Missing AudD API token"))
            }
            if (!audioFile.exists() || audioFile.length() < 512) {
                return@withContext Result.failure(IllegalStateException("Recording too short"))
            }
            val bodyType = "audio/mp4".toMediaType()
            val form =
                MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("api_token", token)
                    .addFormDataPart("return", "apple_music,spotify,deezer")
                    .addFormDataPart("file", audioFile.name, audioFile.asRequestBody(bodyType))
                    .build()
            val req =
                Request.Builder()
                    .url("https://api.audd.io/")
                    .post(form)
                    .build()
            runCatching {
                http.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        return@use Result.failure(IllegalStateException("HTTP ${resp.code}: $text"))
                    }
                    val root = JSONObject(text)
                    val status = root.optString("status", "")
                    if (status != "success") {
                        return@use Result.failure(IllegalStateException(root.optString("error", "No match")))
                    }
                    val result = root.optJSONObject("result") ?: return@use Result.failure(IllegalStateException("Empty result"))
                    val artist = result.optString("artist").ifBlank { return@use Result.failure(IllegalStateException("No artist")) }
                    val title = result.optString("title").ifBlank { return@use Result.failure(IllegalStateException("No title")) }
                    val album = result.optString("album").takeIf { it.isNotBlank() }
                    val apple = result.optJSONObject("apple_music")
                    val spotify = result.optJSONObject("spotify")
                    val deezer = result.optJSONObject("deezer")
                    val artwork =
                        listOfNotNull(
                            apple?.optString("artwork", "")?.takeIf { it.startsWith("http") },
                            deezer?.optJSONObject("album")?.optString("cover_xl", "")?.takeIf { it.startsWith("http") },
                            spotify?.optJSONObject("album")?.optJSONArray("images")?.optJSONObject(0)?.optString("url"),
                        ).firstOrNull()
                    val preview =
                        deezer?.optString("preview", "")?.takeIf { it.startsWith("https://") }
                            ?: spotify?.optString("preview_url", "")?.takeIf { it.startsWith("https://") }
                    Result.success(
                        IdentifyMatch(
                            artist = artist,
                            title = title,
                            album = album,
                            artworkUrl = artwork,
                            previewDownloadUrl = preview,
                            appleMusicUrl = apple?.optString("url")?.takeIf { it.startsWith("http") },
                            spotifyUrl = spotify?.optJSONObject("external_urls")?.optString("spotify"),
                        ),
                    )
                }
            }.getOrElse { Result.failure(it) }
        }
}
