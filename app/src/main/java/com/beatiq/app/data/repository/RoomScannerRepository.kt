package com.beatiq.app.data.repository

import android.content.Context
import com.beatiq.app.core.database.SongDao
import com.beatiq.app.core.database.toEntity
import com.beatiq.app.core.database.toSong
import com.beatiq.app.data.model.Song
import com.beatiq.app.features.scanner.MusicScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RoomScannerRepository(
    private val appContext: Context,
    private val dao: SongDao,
    private val scanner: MusicScanner,
) : ScannerRepository {

    override suspend fun scanLibrary(): List<Song> = withContext(Dispatchers.IO) {
        val scanned = scanner.scanAudioLibrary(appContext)
        if (scanned.isNotEmpty()) {
            val existing = dao.getAllOnce().associateBy { it.mediaStoreId }
            val merged = scanned.map { song ->
                val mediaId = song.id.removePrefix("media-").toLongOrNull() ?: 0L
                val old = existing[mediaId]
                val base = song.toEntity(old?.recentlyPlayedAt)
                if (old != null) {
                    base.copy(
                        playCount = old.playCount,
                        isFavorite = old.isFavorite,
                    )
                } else {
                    base
                }
            }
            dao.upsertAll(merged)
        }
        dao.getAllOnce().map { it.toSong() }
    }
}
