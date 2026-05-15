package com.beatiq.music.core.utils

import kotlin.math.roundToInt

/** Formats track length for UI lists (no external dependencies). */
fun formatDurationMs(durationMs: Long): String {
    if (durationMs <= 0L) return "0:00"
    val totalSeconds = (durationMs / 1000.0).roundToInt().coerceAtLeast(1)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
