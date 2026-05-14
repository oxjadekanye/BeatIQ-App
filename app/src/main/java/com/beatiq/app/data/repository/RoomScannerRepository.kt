package com.beatiq.app.data.repository

import android.content.Context
import com.beatiq.app.core.database.SongDao
import com.beatiq.app.core.database.effectiveMediaStoreId
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
        val fromStore = scanner.scanAudioLibrary(appContext)
        val fromApp = scanner.scanAppDownloadsFolder(appContext)
        val merged = LinkedHashMap<String, Song>()
        for (s in fromStore) merged[s.filePath] = s
        for (s in fromApp) merged[s.filePath] = s
        val scanned = merged.values.toList()
        if (scanned.isNotEmpty()) {
            val existing = dao.getAllOnce().associateBy { it.mediaStoreId }
            val mergedEntities = scanned.map { song ->
                val mediaId = song.effectiveMediaStoreId()
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
            dao.upsertAll(mergedEntities)
        }
        dao.getAllOnce().map { it.toSong() }
    }
}
