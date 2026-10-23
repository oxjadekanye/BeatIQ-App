package com.beatiq.music

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup

/** Software-renders the composed content (Robolectric native graphics) for review screenshots. */
fun renderRoot(activity: Activity): Bitmap {
    val content = activity.findViewById<ViewGroup>(android.R.id.content)
    val view: View = content.getChildAt(0) ?: content
    val bitmap = Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    view.draw(Canvas(bitmap))
    return bitmap
}
