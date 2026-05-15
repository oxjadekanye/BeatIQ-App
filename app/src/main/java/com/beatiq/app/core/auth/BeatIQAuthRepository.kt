package com.beatiq.app.core.auth

import com.beatiq.app.BuildConfig
import com.beatiq.app.core.network.BeatIQApiFailureLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class AuthResult(
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
)

sealed class AuthOutcome {
    data class Success(val result: AuthResult) : AuthOutcome()

    data class Error(val message: String) : AuthOutcome()
}

sealed class RegisterOutcome {
    data object Created : RegisterOutcome()

    data class Error(val message: String) : RegisterOutcome()
}

object BeatIQAuthRepository {
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val client =
        OkHttpClient.Builder()
            .addInterceptor(BeatIQApiFailureLogger)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()

    private fun baseUrl(): String = BuildConfig.API_BASE_URL.trimEnd('/') + "/"

    suspend fun login(email: String, password: String): AuthOutcome =
        withContext(Dispatchers.IO) {
            val body =
                JSONObject()
                    .put("email", email.trim().lowercase())
                    .put("password", password)
                    .toString()
                    .toRequestBody(jsonType)
            val req =
                Request.Builder()
                    .url(baseUrl() + "auth/token/")
                    .post(body)
                    .build()
            runCatching { client.newCall(req).execute() }
                .fold(
                    onSuccess = { resp ->
                        val text = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            return@withContext AuthOutcome.Error(humanizeErrorResponse(resp, text))
                        }
                        val json = JSONObject(text)
                        val access = json.optString("access").takeIf { it.isNotBlank() } ?: return@withContext AuthOutcome.Error("Missing access token")
                        val refresh = json.optString("refresh").takeIf { it.isNotBlank() } ?: return@withContext AuthOutcome.Error("Missing refresh token")
                        val uid = JwtPayload.userIdFromAccessToken(access) ?: return@withContext AuthOutcome.Error("Invalid token")
                        AuthOutcome.Success(AuthResult(access, refresh, uid))
                    },
                    onFailure = { e -> AuthOutcome.Error(describeNetworkFailure(e)) },
                )
        }

    suspend fun register(
        email: String,
        password: String,
        passwordConfirm: String,
        fullName: String,
        birthYear: Int,
        birthMonth: Int,
    ): RegisterOutcome =
        withContext(Dispatchers.IO) {
            val body =
                JSONObject()
                    .put("email", email.trim().lowercase())
                    .put("password", password)
                    .put("password_confirm", passwordConfirm)
                    .put("full_name", fullName.trim())
                    .put("birth_year", birthYear)
                    .put("birth_month", birthMonth)
                    .toString()
                    .toRequestBody(jsonType)
            val req =
                Request.Builder()
                    .url(baseUrl() + "accounts/register/")
                    .post(body)
                    .build()
            runCatching { client.newCall(req).execute() }
                .fold(
                    onSuccess = { resp ->
                        val text = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            return@withContext RegisterOutcome.Error(humanizeErrorResponse(resp, text))
                        }
                        RegisterOutcome.Created
                    },
                    onFailure = { e -> RegisterOutcome.Error(describeNetworkFailure(e)) },
                )
        }

    private fun describeNetworkFailure(e: Throwable): String {
        val msg = e.message?.trim().orEmpty()
        if (msg.isNotBlank()) return msg
        return "Network error (${e.javaClass.simpleName})"
    }

    /** User-visible message for failed HTTP responses (JSON API errors or HTML 5xx pages). */
    private fun humanizeErrorResponse(resp: Response, body: String): String {
        val parsed = parseDetail(body)?.trim()?.takeIf { it.isNotEmpty() }
        if (parsed != null) return parsed
        val code = resp.code
        val phrase = resp.message.trim()
        val base =
            buildString {
                append("Request failed")
                if (phrase.isNotEmpty()) append(": $phrase")
                append(" (HTTP $code)")
            }
        val trimmed = body.trim()
        val looksLikeJson = trimmed.startsWith("{")
        if (code in 500..599) {
            return if (looksLikeJson) {
                "$base. The BeatIQ server returned an error. Try again later."
            } else {
                "$base. The BeatIQ server is having trouble right now. Try again later, or contact support if this continues."
            }
        }
        if (code == 401 || code == 403) {
            return "$base. Check your email and password."
        }
        return base
    }

    private fun parseDetail(json: String): String? =
        runCatching {
            val o = JSONObject(json)
            readDetailValue(o, "detail")
                ?: readStringArray(o, "non_field_errors")
                ?: flattenFieldErrors(o)
        }.getOrNull()

    /** DRF validation errors: one line per field so users see every problem, not an arbitrary first key. */
    private fun flattenFieldErrors(o: JSONObject): String? {
        val parts = mutableListOf<String>()
        for (k in o.keys().asSequence().filter { it != "code" }) {
            when (val v = o.get(k)) {
                is JSONArray -> {
                    val joined =
                        (0 until v.length()).joinToString(" ") { i ->
                            when (val item = v.get(i)) {
                                is String -> item
                                else -> item.toString()
                            }
                        }.trim()
                    if (joined.isNotBlank()) parts.add("$k: $joined")
                }
                is String -> if (v.isNotBlank()) parts.add("$k: $v")
                else -> Unit
            }
        }
        return parts.joinToString("\n").takeIf { it.isNotBlank() }
    }

    private fun readDetailValue(o: JSONObject, key: String): String? {
        if (!o.has(key)) return null
        return when (val v = o.get(key)) {
            is String -> v.takeIf { it.isNotBlank() }
            is JSONArray ->
                (0 until v.length()).joinToString("\n") { i ->
                    when (val item = v.get(i)) {
                        is String -> item
                        else -> item.toString()
                    }
                }.takeIf { it.isNotBlank() }
            else -> null
        }
    }

    private fun readStringArray(o: JSONObject, key: String): String? {
        if (!o.has(key)) return null
        val v = o.get(key)
        if (v !is JSONArray) return null
        return (0 until v.length()).joinToString("\n") { i -> v.getString(i) }.takeIf { it.isNotBlank() }
    }
}
