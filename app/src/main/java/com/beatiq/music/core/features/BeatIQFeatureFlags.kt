package com.beatiq.music.core.features

import com.beatiq.music.BuildConfig

/**
 * Local feature flags for phased premium rollout.
 * Release builds: all OFF unless [BeatIQFeatureFlagOverrides] sets true explicitly.
 * Debug builds: ON by default for internal QA.
 */
object BeatIQFeatureFlags {
    val premiumHomeEnabled: Boolean
        get() = resolve(BeatIQFeatureFlagOverrides.premiumHomeEnabled)

    val moodEngineEnabled: Boolean
        get() = resolve(BeatIQFeatureFlagOverrides.moodEngineEnabled)

    val aiDjEnabled: Boolean
        get() = resolve(BeatIQFeatureFlagOverrides.aiDjEnabled)

    val beatFeedEnabled: Boolean
        get() = resolve(BeatIQFeatureFlagOverrides.beatFeedEnabled)

    val musicAuraEnabled: Boolean
        get() = resolve(BeatIQFeatureFlagOverrides.musicAuraEnabled)

    val globalHeatEnabled: Boolean
        get() = resolve(BeatIQFeatureFlagOverrides.globalHeatEnabled)

    /** Explicit override wins; otherwise debug-only defaults. Never auto-on in release. */
    internal fun resolve(override: Boolean?): Boolean =
        override ?: BuildConfig.DEBUG
}

/** Test/dev overrides; all null = use defaults above. */
object BeatIQFeatureFlagOverrides {
    var premiumHomeEnabled: Boolean? = null
    var moodEngineEnabled: Boolean? = null
    var aiDjEnabled: Boolean? = null
    var beatFeedEnabled: Boolean? = null
    var musicAuraEnabled: Boolean? = null
    var globalHeatEnabled: Boolean? = null
}
