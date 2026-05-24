package com.beatiq.music.core.recommendations

import com.beatiq.music.data.model.OnlineRecommendation
import com.beatiq.music.data.model.RecommendationProvider
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
 * Taste-aware online music discovery via the [Deezer Public API](https://developers.deezer.com/api).
 *
 * Mixes Deezer previews with YouTube Music browse links (no stream extraction).
 */
object OnlineRecommendationEngine {

    private const val BASE = "https://api.deezer.com"
    private const val USER_AGENT = "BeatIQ/1.0 (Android; legal music discovery)"

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
            val genreHint = genreTab.label
            coroutineScope {
                val featuredDef = async { fetchTracks("${genreTab.searchTerm} $taste", 8, genreHint) }
                val gridDef = async { fetchTracks("${genreTab.searchTerm} latest $taste", 20, genreHint) }
                val trendingDef = async { fetchTracks("trending ${genreTab.searchTerm}", 12, genreHint) }
                val albumsDef = async { fetchAlbums("${genreTab.searchTerm} album", 10, genreHint) }
                val djDef = async { fetchTracks("dj mix ${genreTab.searchTerm}", 10, genreHint) }
                val playlistsDef = async { fetchPlaylists("${genreTab.searchTerm} hits playlist", 10, genreHint) }
                val featuredDeezer = featuredDef.await().take(8)
                val featuredIds = featuredDeezer.map { it.id }.toSet()
                val gridPool =
                    dedupe(gridDef.await()).filter { it.id !in featuredIds }
                HomeAiFeed(
                    genreTab = genreTab,
                    featured = interleaveYoutubeMusic(featuredDeezer, youtubeSlots = 2),
                    gridSixteen = interleaveYoutubeMusic(gridPool.take(16), youtubeSlots = 4),
                    trending = interleaveYoutubeMusic(trendingDef.await().take(12), youtubeSlots = 3),
                    trendingAlbums =
                        interleaveYoutubeMusic(albumsDef.await().take(10), youtubeSlots = 2, everyNth = 4),
                    djMix = interleaveYoutubeMusic(djDef.await().take(10), youtubeSlots = 2),
                    popularPlaylists =
                        interleaveYoutubeMusic(playlistsDef.await().take(10), youtubeSlots = 2, everyNth = 4),
                )
            }
        }

    suspend fun lookupArtwork(title: String, artist: String, album: String?): String? =
        withContext(Dispatchers.IO) {
            fetchTracks("$title $artist", 1, album?.takeIf { it.isNotBlank() } ?: "Deezer")
                .firstOrNull()
                ?.artworkUrl
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

    /**
     * Adds YouTube Music browse cards (HTTPS search pages only) alongside Deezer rows.
     */
    private fun interleaveYoutubeMusic(
        deezer: List<OnlineRecommendation>,
        youtubeSlots: Int,
        everyNth: Int = 3,
    ): List<OnlineRecommendation> {
        if (deezer.isEmpty() || youtubeSlots <= 0) return deezer
        val youtube =
            deezer
                .filter { !it.isAlbum }
                .distinctBy { "${it.title.lowercase()}|${it.artist.lowercase()}" }
                .take(youtubeSlots)
                .map { toYoutubeMusicBrowse(it) }
        if (youtube.isEmpty()) return deezer
        val merged = mutableListOf<OnlineRecommendation>()
        var yIndex = 0
        deezer.forEachIndexed { index, item ->
            merged.add(item)
            if ((index + 1) % everyNth == 0 && yIndex < youtube.size) {
                merged.add(youtube[yIndex++])
            }
        }
        return merged
    }

    private fun toYoutubeMusicBrowse(deezer: OnlineRecommendation): OnlineRecommendation =
        deezer.copy(
            id = "youtube_music_${deezer.id}",
            provider = RecommendationProvider.YOUTUBE_MUSIC,
            trackViewUrl = YoutubeMusicLinks.searchUrlForTrack(deezer.title, deezer.artist),
            previewUrl = null,
            isPlayablePreview = false,
            sourceLabel = "YouTube Music",
        )

    private fun fetchTracks(term: String, limit: Int, genreHint: String): List<OnlineRecommendation> =
        fetchDeezer("$BASE/search", term, limit) { arr ->
            buildList {
                for (i in 0 until arr.length()) {
                    parseTrack(arr.getJSONObject(i), genreHint)?.let { add(it) }
                }
            }
        }

    private fun fetchAlbums(term: String, limit: Int, genreHint: String): List<OnlineRecommendation> =
        fetchDeezer("$BASE/search/album", term, limit) { arr ->
            buildList {
                for (i in 0 until arr.length()) {
                    parseAlbum(arr.getJSONObject(i), genreHint)?.let { add(it) }
                }
            }
        }

    private fun fetchPlaylists(term: String, limit: Int, genreHint: String): List<OnlineRecommendation> =
        fetchDeezer("$BASE/search/playlist", term, limit) { arr ->
            buildList {
                for (i in 0 until arr.length()) {
                    parsePlaylist(arr.getJSONObject(i), genreHint)?.let { add(it) }
                }
            }
        }

    private inline fun fetchDeezer(
        endpoint: String,
        term: String,
        limit: Int,
        parseArray: (org.json.JSONArray) -> List<OnlineRecommendation>,
    ): List<OnlineRecommendation> {
        if (limit <= 0 || term.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(term.trim(), StandardCharsets.UTF_8.name())
        val url = "$endpoint?q=$encoded&limit=${limit.coerceIn(1, 50)}"
        val body = httpGet(url) ?: return emptyList()
        return runCatching {
            val arr = JSONObject(body).optJSONArray("data") ?: return emptyList()
            parseArray(arr)
                .filter { it.artworkUrl.isNotBlank() }
                .sortedByDescending { it.releaseDate.orEmpty() }
        }.getOrElse { emptyList() }
    }

    private fun httpGet(url: String): String? =
        runCatching {
            val request =
                Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .get()
                    .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string().orEmpty().ifBlank { null }
            }
        }.getOrNull()

    private fun parseTrack(o: JSONObject, genreHint: String): OnlineRecommendation? {
        val id = o.optLong("id")
        if (id <= 0) return null
        val title = o.optString("title").trim()
        val artist = o.optJSONObject("artist")?.optString("name")?.trim().orEmpty()
        if (title.isEmpty() || artist.isEmpty()) return null
        val albumObj = o.optJSONObject("album")
        val album = albumObj?.optString("title")?.trim().orEmpty()
        val artwork = pickCover(albumObj) ?: return null
        val preview = o.optString("preview").trim().takeIf { it.startsWith("https://") }
        val link = o.optString("link").trim()
        val releaseDate = albumObj?.optString("release_date")?.takeIf { it.isNotBlank() }
        return OnlineRecommendation(
            id = "deezer_track_$id",
            title = title,
            artist = artist,
            album = album,
            artworkUrl = artwork,
            genreHint = genreHint,
            trackViewUrl = link,
            previewUrl = preview,
            releaseDate = releaseDate,
            isAlbum = false,
            provider = RecommendationProvider.DEEZER,
            sourceLabel = "Deezer",
            isPlayablePreview = !preview.isNullOrBlank(),
        )
    }

    private fun parseAlbum(o: JSONObject, genreHint: String): OnlineRecommendation? {
        val id = o.optLong("id")
        if (id <= 0) return null
        val title = o.optString("title").trim()
        val artist = o.optJSONObject("artist")?.optString("name")?.trim().orEmpty()
        if (title.isEmpty()) return null
        val artwork = pickCover(o) ?: return null
        val link = o.optString("link").trim()
        val releaseDate = o.optString("release_date").takeIf { it.isNotBlank() }
        return OnlineRecommendation(
            id = "deezer_album_$id",
            title = title,
            artist = artist.ifBlank { "Various artists" },
            album = title,
            artworkUrl = artwork,
            genreHint = genreHint,
            trackViewUrl = link,
            previewUrl = null,
            releaseDate = releaseDate,
            isAlbum = true,
            provider = RecommendationProvider.DEEZER,
            sourceLabel = "Deezer",
            isPlayablePreview = false,
        )
    }

    private fun parsePlaylist(o: JSONObject, genreHint: String): OnlineRecommendation? {
        val id = o.optLong("id")
        if (id <= 0) return null
        val title = o.optString("title").trim()
        if (title.isEmpty()) return null
        val curator = o.optJSONObject("user")?.optString("name")?.trim().orEmpty()
        val artwork =
            sequenceOf(
                o.optString("picture_xl"),
                o.optString("picture_big"),
                o.optString("picture_medium"),
            ).firstOrNull { it.startsWith("https://") }
                ?: return null
        val link = o.optString("link").trim()
        return OnlineRecommendation(
            id = "deezer_playlist_$id",
            title = title,
            artist = curator.ifBlank { "Deezer" },
            album = "",
            artworkUrl = artwork,
            genreHint = genreHint,
            trackViewUrl = link,
            previewUrl = null,
            releaseDate = null,
            isAlbum = false,
            provider = RecommendationProvider.DEEZER,
            sourceLabel = "Deezer",
            isPlayablePreview = false,
        )
    }

    private fun pickCover(albumOrSelf: JSONObject?): String? {
        if (albumOrSelf == null) return null
        return sequenceOf(
            albumOrSelf.optString("cover_xl"),
            albumOrSelf.optString("cover_big"),
            albumOrSelf.optString("cover_medium"),
            albumOrSelf.optString("cover"),
        ).firstOrNull { it.startsWith("https://") }
            ?: albumOrSelf.optString("md5_image").trim().takeIf { it.isNotBlank() }?.let { md5 ->
                "https://e-cdns-images.dzcdn.net/images/cover/$md5/500x500-000000-80-0-0.jpg"
            }
    }
}
