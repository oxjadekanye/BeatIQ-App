package com.beatiq.music.navigation

import android.util.Base64
import java.nio.charset.StandardCharsets

object NavEncoding {
    fun encode(text: String): String =
        Base64.encodeToString(
            text.toByteArray(StandardCharsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )

    fun decode(encoded: String): String =
        String(
            Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP),
            StandardCharsets.UTF_8,
        )

    fun encodeAlbumArtist(album: String, artist: String): String =
        encode("$album\u0001$artist")

    fun decodeAlbumArtist(encoded: String): Pair<String, String> {
        val raw = decode(encoded)
        val idx = raw.indexOf('\u0001')
        if (idx < 0) return raw to ""
        return raw.substring(0, idx) to raw.substring(idx + 1)
    }
}
