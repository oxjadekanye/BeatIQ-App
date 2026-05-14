package com.beatiq.app.services.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import com.beatiq.app.R

internal const val PLAYBACK_NOTIFICATION_CHANNEL_ID = "com.beatiq.app.playback"

@RequiresApi(Build.VERSION_CODES.O)
internal fun ensurePlaybackNotificationChannel(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    val existing = manager.getNotificationChannel(PLAYBACK_NOTIFICATION_CHANNEL_ID)
    if (existing != null) return
    val channel = NotificationChannel(
        PLAYBACK_NOTIFICATION_CHANNEL_ID,
        context.getString(R.string.playback_notification_channel_name),
        NotificationManager.IMPORTANCE_LOW,
    ).apply {
        description = context.getString(R.string.playback_notification_channel_description)
        setShowBadge(false)
    }
    manager.createNotificationChannel(channel)
}
