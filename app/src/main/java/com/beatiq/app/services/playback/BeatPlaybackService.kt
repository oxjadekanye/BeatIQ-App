@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.beatiq.app.services.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.beatiq.app.MainActivity
import com.beatiq.app.R
import com.beatiq.app.core.player.PlaybackBridge
import com.beatiq.app.core.storage.PlaybackPreferences

/**
 * Hosts the process-wide [ExoPlayer] + [MediaSession] for OS integrations (notification, BT, lock screen).
 *
 * TODO(AI): Attach on-device recommendation pipeline to auto-queue similar tracks.
 */
class BeatPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ensurePlaybackNotificationChannel(this)
        }
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider(
                this,
                { DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID },
                PLAYBACK_NOTIFICATION_CHANNEL_ID,
                R.string.playback_notification_channel_name,
            ),
        )
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        val exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus */ true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        exoPlayer.addListener(
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    PlaybackBridge.onPlaybackStateChanged(playbackState)
                }
            },
        )

        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        mediaSession = MediaSession.Builder(this, exoPlayer)
            .setId("BeatIQSession")
            .setSessionActivity(sessionActivityPendingIntent)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.run {
            PlaybackPreferences.get(this@BeatPlaybackService).saveFromPlayer(player)
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
