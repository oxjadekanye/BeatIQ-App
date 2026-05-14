package com.beatiq.app.core.auth

import com.beatiq.app.BuildConfig
import com.beatiq.app.core.network.BeatIQApiFailureLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
                            return@withContext AuthOutcome.Error(parseDetail(text) ?: resp.message)
                        }
                        val json = JSONObject(text)
                        val access = json.optString("access").takeIf { it.isNotBlank() } ?: return@withContext AuthOutcome.Error("Missing access token")
                        val refresh = json.optString("refresh").takeIf { it.isNotBlank() } ?: return@withContext AuthOutcome.Error("Missing refresh token")
                        val uid = JwtPayload.userIdFromAccessToken(access) ?: return@withContext AuthOutcome.Error("Invalid token")
                        AuthOutcome.Success(AuthResult(access, refresh, uid))
                    },
                    onFailure = { e -> AuthOutcome.Error(e.message ?: "Network error") },
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
                            return@withContext RegisterOutcome.Error(parseDetail(text) ?: resp.message)
                        }
                        RegisterOutcome.Created
                    },
                    onFailure = { e -> RegisterOutcome.Error(e.message ?: "Network error") },
                )
        }

    private fun parseDetail(json: String): String? =
        runCatching {
            val o = JSONObject(json)
            o.optString("detail").takeIf { it.isNotBlank() }
                ?: o.keys().asSequence().firstOrNull()?.let { k ->
                    when (val v = o.get(k)) {
                        is JSONArray ->
                            (0 until v.length()).joinToString { idx ->
                                v.get(idx).toString()
                            }
                        else -> v.toString()
                    }
                }
        }.getOrNull()
}
