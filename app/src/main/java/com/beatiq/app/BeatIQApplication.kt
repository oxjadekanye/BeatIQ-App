package com.beatiq.app

import android.app.Application
import com.beatiq.app.features.library.RepositoryProvider

class BeatIQApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        RepositoryProvider.init(this)
    }
}
