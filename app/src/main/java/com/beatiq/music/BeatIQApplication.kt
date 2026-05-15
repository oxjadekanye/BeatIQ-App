package com.beatiq.music

import android.app.Application

class BeatIQApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Repositories open after sign-in so each user gets an isolated Room database.
    }
}
