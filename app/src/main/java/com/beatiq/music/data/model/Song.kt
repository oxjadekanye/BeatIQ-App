package com.beatiq.music.data.model

/**
 * Canonical on-device track metadata for offline-first playback.
 *
 * TODO(Phase-2): Map [filePath] / [artworkUri] from MediaStore + ContentResolver when scanning is real.
 * TODO(Phase-2): Persist via Room entity + type converters for dates and URIs.
 */
data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val genre: String,
    val durationMs: Long,
    val filePath: String,
    val artworkUri: String?,
    val dateAdded: Long,
    val playCount: Int,
    val isFavorite: Boolean,
)
