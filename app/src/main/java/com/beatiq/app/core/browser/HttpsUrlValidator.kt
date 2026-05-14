package com.beatiq.app.core.browser

import android.net.Uri
import java.util.Locale

/**
 * Validates navigation targets for the in-app browser. Only HTTPS is allowed for security.
 */
object HttpsUrlValidator {
    private val blockedHosts: Set<String> = emptySet()

    fun isAllowedHttpsUrl(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return false
        val uri = try {
            Uri.parse(trimmed)
        } catch (_: Throwable) {
            return false
        }
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return false
        if (scheme != "https") return false
        val host = uri.host?.lowercase(Locale.US) ?: return false
        if (host in blockedHosts) return false
        return true
    }

    fun normalizedHttpsUrl(raw: String): String? {
        if (!isAllowedHttpsUrl(raw)) return null
        return raw.trim()
    }
}
