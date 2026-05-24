package com.beatiq.music.data.repository

import android.content.Context
import com.beatiq.music.core.database.SongDao
import com.beatiq.music.core.database.effectiveMediaStoreId
import com.beatiq.music.core.database.toEntity
import com.beatiq.music.core.database.toSong
import com.beatiq.music.data.model.Song
import com.beatiq.music.features.scanner.MusicScanner
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
            val existingByMediaId = dao.getAllOnce().associateBy { it.mediaStoreId }
            val existingByPath = dao.getAllOnce().associateBy { it.filePath }
            val mergedEntities =
                scanned.map { song ->
                    val mediaId = song.effectiveMediaStoreId()
                    val old =
                        existingByMediaId[mediaId]
                            ?: existingByPath[song.filePath]
                    val base = song.toEntity(old?.recentlyPlayedAt)
                    if (old != null) {
                        // Keep stable primary keys so playlist_songs FK rows are not CASCADE-deleted.
                        base.copy(
                            id = old.id,
                            mediaStoreId = old.mediaStoreId,
                            playCount = old.playCount,
                            isFavorite = old.isFavorite,
                            recentlyPlayedAt = old.recentlyPlayedAt,
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
