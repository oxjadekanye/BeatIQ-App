package com.beatiq.app.features.library

import android.app.Application
import com.beatiq.app.core.database.BeatIQDatabase
import com.beatiq.app.core.player.PlaybackController
import com.beatiq.app.data.repository.Media3PlayerRepository
import com.beatiq.app.data.repository.MusicRepository
import com.beatiq.app.data.repository.PlayerRepository
import com.beatiq.app.data.repository.RoomMusicRepository
import com.beatiq.app.data.repository.RoomScannerRepository
import com.beatiq.app.data.repository.ScannerRepository
import com.beatiq.app.features.scanner.MusicScanner

/**
 * Phase 1 wiring entry point. Production builds use Room + MediaStore + Media3.
 *
 * TODO: Replace with DI (Hilt/Koin) when graph grows.
 */
object RepositoryProvider {

    @Volatile
    private var initialized = false

    lateinit var musicRepository: MusicRepository
        private set

    lateinit var scannerRepository: ScannerRepository
        private set

    lateinit var playerRepository: PlayerRepository
        private set

    fun init(app: Application) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val db = BeatIQDatabase.get(app)
            val dao = db.songDao()
            musicRepository = RoomMusicRepository(dao)
            scannerRepository = RoomScannerRepository(app.applicationContext, dao, MusicScanner())
            val playbackController = PlaybackController.get(app, musicRepository)
            playerRepository = Media3PlayerRepository(playbackController, musicRepository)
            initialized = true
        }
    }
}
