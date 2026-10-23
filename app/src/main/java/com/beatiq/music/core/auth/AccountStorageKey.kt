package com.beatiq.music.core.auth

import java.security.MessageDigest

/** Non-reversible, filesystem/key-safe namespace for an immutable backend account identifier. */
object AccountStorageKey {
    fun fromUserId(userId: String): String {
        require(userId.isNotBlank()) { "A signed-in account is required" }
        val digest = MessageDigest.getInstance("SHA-256").digest(userId.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}
