package com.beatiq.music.core.network

import android.util.Log
import com.beatiq.music.BuildConfig
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Logs non-success HTTP responses without consuming the response body for the caller
 * ([Response.peekBody]).
 */
internal object BeatIQApiFailureLogger : Interceptor {
    private const val TAG = "BeatIQHttp"
    private const val BODY_SNIPPET_MAX = 400

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (BuildConfig.DEBUG && !response.isSuccessful) {
            val snippet =
                runCatching {
                    response.peekBody(512).string().take(BODY_SNIPPET_MAX)
                }.getOrElse { e ->
                    (e.message ?: "peekBody failed").take(120)
                }
            Log.w(
                TAG,
                "HTTP ${response.code} ${request.method} ${request.url} :: $snippet",
            )
        }
        return response
    }
}
