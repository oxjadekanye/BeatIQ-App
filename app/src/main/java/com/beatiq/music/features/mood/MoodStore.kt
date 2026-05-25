package com.beatiq.music.features.mood

import android.content.Context

class MoodStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getSelectedMoodId(): MoodId {
        val raw = prefs.getString(KEY_MOOD, MoodId.AFROBEATS_ENERGY.name)
        return runCatching { MoodId.valueOf(raw!!) }.getOrDefault(MoodId.AFROBEATS_ENERGY)
    }

    fun setSelectedMoodId(id: MoodId) {
        prefs.edit().putString(KEY_MOOD, id.name).apply()
    }

    companion object {
        private const val PREFS = "beatiq_mood"
        private const val KEY_MOOD = "selected_mood_id"
    }
}
