package com.beatiq.music.musicme.audiogeneration

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.beatiq.music.BuildConfig
import com.beatiq.music.core.auth.AuthPreferences
import kotlinx.coroutines.delay

/** Where MusicMe will get its audio generation service; tests swap in fakes. */
object AudioGenerationDependencies {
    var serviceFactory: (Context) -> AudioGenerationService = ::defaultService
        @VisibleForTesting set

    var filesFactory: (Context, String) -> AudioTrackFiles = { context, accountId -> AudioTrackFiles(context, accountId) }
        @VisibleForTesting set

    var sleep: suspend (Long) -> Unit = { delay(it) }
        @VisibleForTesting set

    @VisibleForTesting
    fun reset() {
        serviceFactory = ::defaultService
        filesFactory = { context, accountId -> AudioTrackFiles(context, accountId) }
        sleep = { delay(it) }
    }

    /** Off unless BuildConfig carries a valid HTTPS origin (loopback is accepted for local development). */
    fun defaultService(context: Context): AudioGenerationService = serviceFor(BuildConfig.MUSICME_AUDIO_URL, context)

    @VisibleForTesting
    internal fun serviceFor(configuredUrl: String, context: Context): AudioGenerationService {
        val url = RemoteAudioGenerationService.parseBaseUrl(configuredUrl) ?: return UnconfiguredAudioGenerationService
        val auth = AuthPreferences(context.applicationContext)
        return RemoteAudioGenerationService(url, accessToken = { auth.accessToken })
    }
}
