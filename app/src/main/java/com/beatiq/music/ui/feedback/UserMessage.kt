package com.beatiq.music.ui.feedback

import android.content.Context
import android.widget.Toast

fun showBeatIQMessage(context: Context, message: String) {
    Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
}
