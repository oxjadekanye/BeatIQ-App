package com.beatiq.music.core.auth

import android.content.Context

/**
 * Persists JWT session. Each signed-in user maps to a separate Room DB file via [userId].
 */
class AuthPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var accessToken: String?
        get() = prefs.getString(KEY_ACCESS, null)
        set(value) {
            prefs.edit().putString(KEY_ACCESS, value).apply()
        }

    var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH, null)
        set(value) {
            prefs.edit().putString(KEY_REFRESH, value).apply()
        }

    var userId: String?
        get() = prefs.getString(KEY_USER_ID, null)
        set(value) {
            prefs.edit().putString(KEY_USER_ID, value).apply()
        }

    var userEmail: String?
        get() = prefs.getString(KEY_EMAIL, null)
        set(value) {
            prefs.edit().putString(KEY_EMAIL, value).apply()
        }

    fun hasSession(): Boolean = !accessToken.isNullOrBlank() && !userId.isNullOrBlank()

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS = "beatiq_auth"
        private const val KEY_ACCESS = "access"
        private const val KEY_REFRESH = "refresh"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_EMAIL = "email"
    }
}
