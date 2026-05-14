package com.beatiq.app.core.auth

import android.util.Base64
import org.json.JSONObject

internal object JwtPayload {
    fun userIdFromAccessToken(jwt: String): String? =
        runCatching {
            val parts = jwt.split(".")
            if (parts.size < 2) return null
            val payload = parts[1]
            val padded =
                when (payload.length % 4) {
                    2 -> "$payload=="
                    3 -> "$payload="
                    else -> payload
                }
            val decoded = Base64.decode(padded, Base64.URL_SAFE)
            val json = JSONObject(String(decoded, Charsets.UTF_8))
            json.optString("user_id").takeIf { it.isNotBlank() }
        }.getOrNull()
}
