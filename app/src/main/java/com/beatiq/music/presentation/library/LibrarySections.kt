package com.beatiq.music.presentation.library

import com.beatiq.music.data.model.Song

data class AlbumSection(
    val album: String,
    val artist: String,
    val artworkUri: String?,
    val representativePath: String?,
    val songs: List<Song>,
)

data class ArtistSection(
    val artist: String,
    val artworkUri: String?,
    val representativePath: String?,
    val songCount: Int,
    val songs: List<Song>,
)

fun buildAlbumSections(all: List<Song>, maxSections: Int = Int.MAX_VALUE): List<AlbumSection> {
    if (all.isEmpty()) return emptyList()
    return all
        .groupBy { "${it.album.trim().lowercase()}|||${it.artist.trim().lowercase()}" }
        .values
        .map { songs ->
            val sorted = songs.sortedBy { it.title.lowercase() }
            val first = sorted.first()
            AlbumSection(
                album = first.album.ifBlank { "Unknown album" },
                artist = first.artist.ifBlank { "Unknown artist" },
                artworkUri = sorted.firstOrNull { !it.artworkUri.isNullOrBlank() }?.artworkUri,
                representativePath = first.filePath,
                songs = sorted,
            )
        }
        .sortedBy { it.album.lowercase() }
        .let { if (maxSections < Int.MAX_VALUE) it.take(maxSections) else it }
}

fun buildArtistSections(all: List<Song>, maxSections: Int = Int.MAX_VALUE): List<ArtistSection> {
    if (all.isEmpty()) return emptyList()
    return all
        .groupBy { it.artist.trim().lowercase() }
        .values
        .map { songs ->
            val sorted = songs.sortedBy { it.title.lowercase() }
            val first = sorted.first()
            ArtistSection(
                artist = first.artist.ifBlank { "Unknown artist" },
                artworkUri = sorted.firstOrNull { !it.artworkUri.isNullOrBlank() }?.artworkUri,
                representativePath = first.filePath,
                songCount = sorted.size,
                songs = sorted,
            )
        }
        .sortedBy { it.artist.lowercase() }
        .let { if (maxSections < Int.MAX_VALUE) it.take(maxSections) else it }
}
