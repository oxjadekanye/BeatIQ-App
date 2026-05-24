package com.beatiq.music.core.recommendations

import com.beatiq.music.data.model.OnlineRecommendation
import com.beatiq.music.data.model.Song
import com.beatiq.music.presentation.home.HomeGenreTab
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class HomeAiFeed(
    val genreTab: HomeGenreTab,
    val featured: List<OnlineRecommendation>,
    val gridSixteen: List<OnlineRecommendation>,
    val trending: List<OnlineRecommendation>,
    val trendingAlbums: List<OnlineRecommendation>,
    val djMix: List<OnlineRecommendation>,
    val popularPlaylists: List<OnlineRecommendation>,
)

/**
 * Taste-aware online music discovery via the public iTunes Search API.
 */
object OnlineRecommendationEngine {

    private val client =
        OkHttpClient.Builder()
            .connectTimeout(14, TimeUnit.SECONDS)
            .readTimeout(14, TimeUnit.SECONDS)
            .build()

    suspend fun loadHomeFeed(
        genreTab: HomeGenreTab,
        library: List<Song>,
    ): HomeAiFeed =
        withContext(Dispatchers.IO) {
            val taste = buildTasteSuffix(library, genreTab)
            coroutineScope {
                val featuredDef = async { fetchSongs("${genreTab.searchTerm} $taste", 8) }
                val gridDef = async { fetchSongs("${genreTab.searchTerm} latest $taste", 20) }
                val trendingDef = async { fetchSongs("trending ${genreTab.searchTerm} 2024", 12) }
                val albumsDef = async { fetchAlbums("${genreTab.searchTerm} album", 10) }
                val djDef = async { fetchSongs("dj mix ${genreTab.searchTerm}", 10) }
                val playlistsDef = async { fetchSongs("playlist ${genreTab.searchTerm} hits", 10) }
                val featured = featuredDef.await().take(8)
                val featuredIds = featured.map { it.id }.toSet()
                val gridPool =
                    dedupe(gridDef.await()).filter { it.id !in featuredIds }
                HomeAiFeed(
                    genreTab = genreTab,
                    featured = featured,
                    gridSixteen = gridPool.take(16),
                    trending = trendingDef.await().take(12),
                    trendingAlbums = albumsDef.await().take(10),
                    djMix = djDef.await().take(10),
                    popularPlaylists = playlistsDef.await().take(10),
                )
            }
        }

    suspend fun lookupArtwork(title: String, artist: String, album: String?): String? =
        withContext(Dispatchers.IO) {
            fetchSongs("$title $artist", 1).firstOrNull()?.artworkUrl
        }

    private fun buildTasteSuffix(library: List<Song>, tab: HomeGenreTab): String {
        if (library.isEmpty()) return tab.label
        val topArtist =
            library
                .groupingBy { it.artist.trim() }
                .eachCount()
                .entries
                .maxByOrNull { it.value }
                ?.key
                .orEmpty()
        return listOf(tab.label, topArtist).filter { it.isNotBlank() }.joinToString(" ")
    }

    private fun dedupe(items: List<OnlineRecommendation>): List<OnlineRecommendation> {
        val seen = linkedSetOf<String>()
        return items.filter { seen.add(it.id) }
    }

    private fun fetchSongs(term: String, limit: Int): List<OnlineRecommendation> =
        fetch(term, limit, entity = "song", isAlbum = false)

    private fun fetchAlbums(term: String, limit: Int): List<OnlineRecommendation> =
        fetch(term, limit, entity = "album", isAlbum = true)

    private fun fetch(term: String, limit: Int, entity: String, isAlbum: Boolean): List<OnlineRecommendation> {
        if (limit <= 0) return emptyList()
        val encoded = URLEncoder.encode(term.trim(), StandardCharsets.UTF_8.name())
        val url =
            "https://itunes.apple.com/search?term=$encoded&media=music&entity=$entity&limit=${limit.coerceIn(1, 50)}"
        val body =
            runCatching {
                client.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                    if (!resp.isSuccessful) return emptyList()
                    resp.body?.string().orEmpty()
                }
            }.getOrElse { return emptyList() }
        if (body.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONObject(body).optJSONArray("results") ?: return emptyList()
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val parsed = parseResult(o, isAlbum) ?: continue
                    if (parsed.artworkUrl.isBlank()) continue
                    add(parsed)
                }
            }.sortedByDescending { it.releaseDate.orEmpty() }
        }.getOrElse { emptyList() }
    }

    private fun parseResult(o: JSONObject, isAlbum: Boolean): OnlineRecommendation? {
        val trackId = o.optLong("trackId").takeIf { it > 0 } ?: o.optLong("collectionId")
        if (trackId <= 0) return null
        val title =
            if (isAlbum) {
                o.optString("collectionName").trim()
            } else {
                o.optString("trackName").trim()
            }
        val artist = o.optString("artistName").trim()
        if (title.isEmpty() || artist.isEmpty()) return null
        val art =
            o.optString("artworkUrl100")
                .replace("100x100bb", "600x600bb")
                .ifBlank { o.optString("artworkUrl60") }
        val viewUrl = o.optString("trackViewUrl").ifBlank { o.optString("collectionViewUrl") }
        return OnlineRecommendation(
            id = "itunes_$trackId",
            title = title,
            artist = artist,
            album = o.optString("collectionName", ""),
            artworkUrl = art,
            genreHint = o.optString("primaryGenreName", ""),
            trackViewUrl = viewUrl,
            previewUrl = o.optString("previewUrl").takeIf { it.isNotBlank() },
            releaseDate = o.optString("releaseDate").takeIf { it.isNotBlank() },
            isAlbum = isAlbum,
        )
    }
}
