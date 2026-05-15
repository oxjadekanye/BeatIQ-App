package com.beatiq.music.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)

val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)

/** BeatIQ brand — dark music UI */
val BeatIQMidnight = Color(0xFF0A0610)
val BeatIQDeepViolet = Color(0xFF151022)
val BeatIQVioletElevated = Color(0xFF1E1630)
val BeatIQAccent = Color(0xFFB794FF)
val BeatIQAccentDim = Color(0xFF7C5CE0)
val BeatIQMint = Color(0xFF3EE8C4)
val BeatIQOnDark = Color(0xFFF4F1FA)
val BeatIQOnDarkMuted = Color(0xFFA89FC0)
val BeatIQCardStroke = Color(0x26FFFFFF)
val BeatIQGlowPink = Color(0x33FF6B9D)
val BeatIQGlowBlue = Color(0x3348C6FF)

/** Wordmark gradient aligned with the neon B logo (magenta → violet → cyan). */
val BeatIQWordmarkPink = Color(0xFFFF4FD8)
val BeatIQWordmarkViolet = Color(0xFFC855FF)
val BeatIQWordmarkCyan = Color(0xFF00D4FF)

val BeatIQWordmarkBrush: Brush =
    Brush.horizontalGradient(
        listOf(BeatIQWordmarkPink, BeatIQWordmarkViolet, BeatIQWordmarkCyan),
    )
