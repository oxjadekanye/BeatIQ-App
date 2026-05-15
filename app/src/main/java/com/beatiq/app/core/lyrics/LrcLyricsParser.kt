package com.beatiq.app.core.lyrics

/**
 * Parsed LRC line from LRCLIB [mm:ss.xx] or [m:ss.xx] synced lyrics.
 */
data class LrcLine(val timeMs: Long, val text: String)

object LrcLyricsParser {
    private val lineRegex = Regex("""^\[(\d{1,2}):(\d{2})(?:\.(\d{1,3}))?\]\s*(.*)$""")

    fun parse(syncedLyrics: String): List<LrcLine> {
        val out = ArrayList<LrcLine>()
        for (raw in syncedLyrics.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val m = lineRegex.matchEntire(line) ?: continue
            val min = m.groupValues[1].toLongOrNull() ?: continue
            val sec = m.groupValues[2].toLongOrNull() ?: continue
            val frac = m.groupValues[3]
            val text = m.groupValues[4].trim()
            if (text.isEmpty()) continue
            val fracMs =
                when {
                    frac.isEmpty() -> 0L
                    frac.length == 1 -> (frac.toLongOrNull() ?: 0L) * 100L
                    frac.length == 2 -> (frac.toLongOrNull() ?: 0L) * 10L
                    else -> (frac.toLongOrNull() ?: 0L).coerceAtMost(999L)
                }
            val timeMs = (min * 60L + sec) * 1000L + fracMs
            out.add(LrcLine(timeMs = timeMs, text = text))
        }
        out.sortBy { it.timeMs }
        return out
    }

    /** Index of the line that should be highlighted at [positionMs], or -1 if before first line. */
    fun activeLineIndex(lines: List<LrcLine>, positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        var lo = 0
        var hi = lines.lastIndex
        var best = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= positionMs) {
                best = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return best
    }
}
