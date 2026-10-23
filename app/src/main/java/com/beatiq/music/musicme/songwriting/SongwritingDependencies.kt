package com.beatiq.music.musicme.songwriting

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.beatiq.music.BuildConfig
import com.beatiq.music.core.auth.AuthPreferences
import kotlinx.coroutines.delay

/** Where MusicMe gets its songwriting service; tests swap in fakes and instant waits. */
object SongwritingDependencies {
    var serviceFactory: (Context) -> SongwritingService = ::defaultService
        @VisibleForTesting set

    var sleep: suspend (Long) -> Unit = { delay(it) }
        @VisibleForTesting set

    var log: SongwritingLog = SongwritingLog.androidLog
        @VisibleForTesting set

    @VisibleForTesting
    fun reset() {
        serviceFactory = ::defaultService
        sleep = { delay(it) }
        log = SongwritingLog.androidLog
    }

    /**
     * Off unless this is a debug build whose BuildConfig carries a valid HTTPS (or loopback) URL.
     * Release builds always get the unconfigured service, even if a URL were ever set for them.
     */
    fun defaultService(context: Context): SongwritingService {
        if (!BuildConfig.DEBUG) return UnconfiguredSongwritingService
        val url = RemoteSongwritingService.parseBaseUrl(BuildConfig.MUSICME_SONGWRITER_URL) ?: return UnconfiguredSongwritingService
        val auth = AuthPreferences(context.applicationContext)
        return RemoteSongwritingService(url, accessToken = { auth.accessToken })
    }
}
