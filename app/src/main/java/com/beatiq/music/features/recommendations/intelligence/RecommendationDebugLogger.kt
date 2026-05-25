package com.beatiq.music.features.recommendations.intelligence

import android.util.Log
import com.beatiq.music.BuildConfig

object RecommendationDebugLogger {
    private const val TAG = "BeatIQ.RecScore"

    fun log(message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, message)
        }
    }
}
