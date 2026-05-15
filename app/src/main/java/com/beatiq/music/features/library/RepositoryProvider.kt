package com.beatiq.music.features.library

import android.app.Application
import com.beatiq.music.core.database.BeatIQDatabase
import com.beatiq.music.core.player.PlaybackController
import com.beatiq.music.data.repository.DownloadsRepository
import com.beatiq.music.data.repository.Media3PlayerRepository
import com.beatiq.music.data.repository.MusicRepository
import com.beatiq.music.data.repository.PlayerRepository
import com.beatiq.music.data.repository.PlaylistRepository
import com.beatiq.music.data.repository.RoomDownloadsRepository
import com.beatiq.music.data.repository.RoomMusicRepository
import com.beatiq.music.data.repository.RoomPlaylistRepository
import com.beatiq.music.data.repository.RoomScannerRepository
import com.beatiq.music.data.repository.ScannerRepository
import com.beatiq.music.features.scanner.MusicScanner

/**
 * Repositories scoped to the signed-in user. [ensureForUser] switches Room DB files per account.
 */
object RepositoryProvider {

    @Volatile
    private var initialized = false

    @Volatile
    private var activeUserId: String? = null

    private var musicRepositoryImpl: MusicRepository? = null
    private var scannerRepositoryImpl: ScannerRepository? = null
    private var playerRepositoryImpl: PlayerRepository? = null
    private var downloadsRepositoryImpl: DownloadsRepository? = null
    private var playlistRepositoryImpl: PlaylistRepository? = null

    val isInitialized: Boolean get() = initialized

    val musicRepository: MusicRepository
        get() = musicRepositoryImpl ?: error("BeatIQ repositories not initialized")

    val scannerRepository: ScannerRepository
        get() = scannerRepositoryImpl ?: error("BeatIQ repositories not initialized")

    val playerRepository: PlayerRepository
        get() = playerRepositoryImpl ?: error("BeatIQ repositories not initialized")

    val downloadsRepository: DownloadsRepository
        get() = downloadsRepositoryImpl ?: error("BeatIQ repositories not initialized")

    val playlistRepository: PlaylistRepository
        get() = playlistRepositoryImpl ?: error("BeatIQ repositories not initialized")

    /**
     * Opens the per-user Room database and wires repositories. Call from a background dispatcher
     * if you want to avoid main-thread disk hit on cold start.
     */
    fun ensureForUser(app: Application, userId: String) {
        synchronized(this) {
            if (initialized && activeUserId == userId) return
            if (initialized) {
                shutdownLocked(app)
            }
            val db = BeatIQDatabase.get(app, userId)
            val dao = db.songDao()
            val downloadDao = db.userDownloadDao()
            val playlistDao = db.playlistDao()
            val musicRepo = RoomMusicRepository(dao)
            val downloadsRepo = RoomDownloadsRepository(app, downloadDao)
            val playlistRepo = RoomPlaylistRepository(playlistDao)
            val scannerRepo = RoomScannerRepository(app.applicationContext, dao, MusicScanner())
            val playbackController = PlaybackController.get(app, musicRepo)
            val playerRepo = Media3PlayerRepository(playbackController, musicRepo)
            musicRepositoryImpl = musicRepo
            downloadsRepositoryImpl = downloadsRepo
            playlistRepositoryImpl = playlistRepo
            scannerRepositoryImpl = scannerRepo
            playerRepositoryImpl = playerRepo
            activeUserId = userId
            initialized = true
        }
    }

    fun shutdown(app: Application) {
        synchronized(this) {
            if (!initialized) return
            shutdownLocked(app)
        }
    }

    private fun shutdownLocked(app: Application) {
        runCatching { PlaybackController.reset() }
        musicRepositoryImpl = null
        scannerRepositoryImpl = null
        playerRepositoryImpl = null
        downloadsRepositoryImpl = null
        playlistRepositoryImpl = null
        BeatIQDatabase.closeCurrent()
        activeUserId = null
        initialized = false
    }
}
