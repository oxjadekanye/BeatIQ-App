package com.beatiq.music.core.recommendations

import com.beatiq.music.data.model.OnlineRecommendation
import com.beatiq.music.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Builds taste-aware online suggestions from the user's library (genres, artists, favourites)
 * using the public iTunes Search API (metadata + artwork only).
 */
object OnlineRecommendationEngine {

    private val client =
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()

    private val tasteKeywords =
        listOf(
            "afrobeats",
            "afrobeat",
            "nigerian",
            "naija",
            "amapiano",
            "highlife",
            "juju",
            "bongo",
        )

    suspend fun recommendForLibrary(library: List<Song>, limit: Int = 24): List<OnlineRecommendation> =
        withContext(Dispatchers.IO) {
            if (library.isEmpty()) return@withContext emptyList()
            val terms = buildSearchTerms(library)
            val results = linkedSetOf<OnlineRecommendation>()
            for (term in terms) {
                if (results.size >= limit) break
                results.addAll(fetchItunes(term, limit - results.size))
            }
            results.take(limit).toList()
        }

    private fun buildSearchTerms(library: List<Song>): List<String> {
        val weighted = linkedMapOf<String, Int>()
        val favs = library.filter { it.isFavorite }
        val played = library.sortedByDescending { it.playCount }.take(40)
        val pool = (favs + played + library).distinctBy { it.id }

        fun bump(raw: String, score: Int) {
            val token = raw.trim().lowercase()
            if (token.length < 3) return
            weighted[token] = (weighted[token] ?: 0) + score
        }

        pool.forEach { song ->
            bump(song.genre, if (song.isFavorite) 6 else 3)
            bump(song.artist, if (song.isFavorite) 5 else 2)
            tasteKeywords.forEach { key ->
                if (song.genre.lowercase().contains(key) ||
                    song.artist.lowercase().contains(key) ||
                    song.title.lowercase().contains(key) ||
                    song.album.lowercase().contains(key)
                ) {
                    bump(key, 8)
                }
            }
        }

        val ranked = weighted.entries.sortedByDescending { it.value }.map { it.key }
        if (ranked.isEmpty()) {
            return listOf("afrobeats", "african music", "world music")
        }
        return ranked.take(5)
    }

    private fun fetchItunes(term: String, limit: Int): List<OnlineRecommendation> {
        if (limit <= 0) return emptyList()
        val url =
            "https://itunes.apple.com/search?" +
                "term=${java.net.URLEncoder.encode(term, Charsets.UTF_8.name())}" +
                "&media=music&entity=song&limit=${limit.coerceIn(1, 25)}"
        val body =
            runCatching {
                client.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                    if (!resp.isSuccessful) return emptyList()
                    resp.body?.string().orEmpty()
                }
            }.getOrElse { return emptyList() }
        if (body.isBlank()) return emptyList()
        return runCatching {
            val root = JSONObject(body)
            val arr = root.optJSONArray("results") ?: return emptyList()
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val title = o.optString("trackName").trim()
                    val artist = o.optString("artistName").trim()
                    if (title.isEmpty() || artist.isEmpty()) continue
                    val art =
                        o.optString("artworkUrl100")
                            .replace("100x100bb", "600x600bb")
                            .ifBlank { o.optString("artworkUrl60") }
                    if (art.isBlank()) continue
                    add(
                        OnlineRecommendation(
                            id = "itunes_${o.optLong("trackId")}",
                            title = title,
                            artist = artist,
                            album = o.optString("collectionName", ""),
                            artworkUrl = art,
                            genreHint = o.optString("primaryGenreName", term),
                        ),
                    )
                }
            }
        }.getOrElse { emptyList() }
    }
}
