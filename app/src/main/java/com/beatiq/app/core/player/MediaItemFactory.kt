package com.beatiq.app.core.player

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.beatiq.app.data.model.Song

internal fun Song.asMediaItem(): MediaItem {
    val metadata =
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setGenre(genre)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .build()

    return MediaItem.Builder()
        .setMediaId(id)
        .setUri(Uri.parse(filePath))
        .setMediaMetadata(metadata)
        .build()
}
