package com.beatiq.app.data.repository

import com.beatiq.app.data.model.Song

/**
 * Coordinates library indexing / rescans (MediaStore in production).
 */
interface ScannerRepository {
    /** Persists scan results and returns the latest snapshot. */
    suspend fun scanLibrary(): List<Song>
}
