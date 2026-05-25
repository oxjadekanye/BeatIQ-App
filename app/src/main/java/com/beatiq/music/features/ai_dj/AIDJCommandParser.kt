package com.beatiq.music.features.ai_dj

object AIDJCommandParser {
    fun parse(input: String): AIDJCommand {
        val raw = input.trim()
        val lower = raw.lowercase()
        val intent =
            when {
                lower.contains("trending") && lower.contains("afro") -> AIDJIntent.TRENDING_AFROBEATS
                lower.contains("late night") -> AIDJIntent.LATE_NIGHT
                lower.contains("gym") -> AIDJIntent.GYM_ENERGY
                lower.contains("underground") -> AIDJIntent.UNDERGROUND
                lower.contains("saved") || lower.contains("like my") -> AIDJIntent.LIKE_SAVED
                lower.contains("calm") || lower.contains("focus") -> AIDJIntent.CALM_FOCUS
                lower.contains("nigerian") || lower.contains("naija") -> AIDJIntent.NIGERIAN_HEAT
                lower.contains("surprise") -> AIDJIntent.SURPRISE
                else -> AIDJIntent.UNKNOWN
            }
        val terms =
            when (intent) {
                AIDJIntent.TRENDING_AFROBEATS -> listOf("trending afrobeats 2024")
                AIDJIntent.LATE_NIGHT -> listOf("late night r&b drive")
                AIDJIntent.GYM_ENERGY -> listOf("gym workout hip hop")
                AIDJIntent.UNDERGROUND -> listOf("underground afrobeats")
                AIDJIntent.LIKE_SAVED -> listOf("afrobeats soul")
                AIDJIntent.CALM_FOCUS -> listOf("calm focus instrumental")
                AIDJIntent.NIGERIAN_HEAT -> listOf("nigerian hits afrobeats")
                AIDJIntent.SURPRISE -> listOf("afrobeats amapiano mix")
                AIDJIntent.UNKNOWN -> listOf(raw.ifBlank { "afrobeats mix" })
            }
        return AIDJCommand(raw = raw, intent = intent, searchTerms = terms)
    }
}
