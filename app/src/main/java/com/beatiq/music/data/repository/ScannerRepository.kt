package com.beatiq.music.data.repository

import com.beatiq.music.data.model.Song

/**
 * Coordinates library indexing / rescans (MediaStore in production).
 */
interface ScannerRepository {
    /** Persists scan results and returns the latest snapshot. */
    suspend fun scanLibrary(): List<Song>
}
