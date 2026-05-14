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

internal fun Song.toEntity(recentlyPlayedAt: Long? = null): SongEntity {
    val mediaId = id.removePrefix("media-").toLongOrNull() ?: 0L
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
