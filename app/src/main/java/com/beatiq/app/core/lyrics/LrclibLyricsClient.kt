package com.beatiq.app.core.lyrics

import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

data class LyricsResult(
    val plainLyrics: String?,
    val syncedLyrics: String?,
)

/**
 * Fetches lyrics from [LRCLIB](https://lrclib.net/) (free community API, no token).
 */
object LrclibLyricsClient {

    suspend fun fetchLyrics(artist: String, title: String, album: String?): LyricsResult? =
        withContext(Dispatchers.IO) {
            val base = "https://lrclib.net/api/get"
            val q =
                buildString {
                    append("?artist_name=").append(URLEncoder.encode(artist, StandardCharsets.UTF_8.name()))
                    append("&track_name=").append(URLEncoder.encode(title, StandardCharsets.UTF_8.name()))
                    if (!album.isNullOrBlank()) {
                        append("&album_name=").append(URLEncoder.encode(album, StandardCharsets.UTF_8.name()))
                    }
                }
            val conn = (URL(base + q).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12_000
                readTimeout = 12_000
                setRequestProperty("User-Agent", "BeatIQ/1.0")
            }
            runCatching {
                val code = conn.responseCode
                val body =
                    (if (code in 200..299) conn.inputStream else conn.errorStream)
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        .orEmpty()
                conn.disconnect()
                if (code == 404 || body.isBlank()) return@withContext null
                val json = JSONObject(body)
                val plain = json.optString("plainLyrics", "").takeIf { it.isNotBlank() }
                val synced = json.optString("syncedLyrics", "").takeIf { it.isNotBlank() }
                if (plain == null && synced == null) null else LyricsResult(plain, synced)
            }.getOrNull()
        }
}
