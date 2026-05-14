package com.beatiq.app.navigation

/**
 * Outer [NavHost] routes. Main tabs use [MAIN_PATTERN] with an inner tab id (`home`, `discover`, …).
 */
object BeatIQRoutes {
    const val BRAND_SPLASH = "brand_splash"
    const val LANDING = "landing"
    const val REGISTER = "register"

    /** Outer route; argument `startTab` is the initial inner tab route. */
    const val MAIN_PATTERN = "main/{startTab}"

    fun mainRoute(startTab: String): String = "main/$startTab"
}
