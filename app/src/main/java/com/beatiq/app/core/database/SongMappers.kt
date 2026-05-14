package com.beatiq.app.core.database

import com.beatiq.app.data.model.Song

internal fun SongEntity.toSong(): Song = Song(
    id = id,
    title = title,
    artist = artist,
    album = album,
    genre = genre,
    durationMs = durationMs,
    filePath = filePath,
    artworkUri = artworkUri,
    dateAdded = dateAdded,
    playCount = playCount,
    isFavorite = isFavorite,
)

internal fun Song.effectiveMediaStoreId(): Long =
    when {
        id.startsWith("media-") -> id.removePrefix("media-").toLongOrNull() ?: 0L
        else ->
            ((filePath.hashCode().toLong() and 0x7FFF_FFFFL) xor (id.hashCode().toLong() shl 32)) and Long.MAX_VALUE
    }.let { if (it == 0L) 1L else it }

internal fun Song.toEntity(recentlyPlayedAt: Long? = null): SongEntity {
    val mediaId = effectiveMediaStoreId()
    return SongEntity(
        id = id,
        mediaStoreId = mediaId,
        title = title,
        artist = artist,
        album = album,
        genre = genre,
        durationMs = durationMs,
        filePath = filePath,
        artworkUri = artworkUri,
        dateAdded = dateAdded,
        playCount = playCount,
        isFavorite = isFavorite,
        recentlyPlayedAt = recentlyPlayedAt,
    )
}
