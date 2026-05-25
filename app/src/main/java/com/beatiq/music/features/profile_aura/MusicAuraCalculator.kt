package com.beatiq.music.features.profile_aura

import com.beatiq.music.features.recommendations.intelligence.RecommendationSignals

data class MusicAuraProfile(
    val name: String,
    val description: String,
    val topMoodTags: List<String>,
    val gradientStartHex: Long,
    val gradientEndHex: Long,
    val shareText: String,
)

object MusicAuraCalculator {
    fun compute(signals: RecommendationSignals): MusicAuraProfile {
        val saves = signals.saves
        val plays = signals.plays
        val searches = signals.searches.joinToString(" ").lowercase()
        val genres = signals.recentGenres.joinToString(" ").lowercase()
        val blob = "$searches $genres ${signals.tasteArtist.orEmpty()}".lowercase()

        return when {
            blob.contains("afro") && plays > 5 ->
                aura(
                    "Afrobeats Loyalist",
                    "You chase rhythm and diaspora heat.",
                    listOf("Afrobeats", "Street", "Party"),
                    0xFFFF6B35,
                    0xFFFFD23F,
                )
            saves > plays && saves > 3 ->
                aura(
                    "Calm Curator",
                    "You collect previews and listen with intention.",
                    listOf("Calm", "Curator", "Focus"),
                    0xFF264653,
                    0xFF2A9D8F,
                )
            blob.contains("underground") ->
                aura(
                    "Underground Hunter",
                    "You dig deeper than the charts.",
                    listOf("Underground", "Discovery", "Fresh"),
                    0xFF212121,
                    0xFF7B2CBF,
                )
            plays > 8 && (blob.contains("night") || blob.contains("r&b")) ->
                aura(
                    "Midnight Dreamer",
                    "Your peak hours lean moody and smooth.",
                    listOf("Late Night", "R&B", "Soul"),
                    0xFF1A1A2E,
                    0xFF4A4E8A,
                )
            blob.contains("gym") || blob.contains("workout") ->
                aura(
                    "Weekend Mover",
                    "High energy drives your listening.",
                    listOf("Gym", "Energy", "Hip-Hop"),
                    0xFFE63946,
                    0xFFFF006E,
                )
            blob.contains("romantic") || blob.contains("love") ->
                aura(
                    "Romantic Listener",
                    "Warm, intimate tracks find you often.",
                    listOf("Romantic", "Soul", "R&B"),
                    0xFF9D4EDD,
                    0xFFFFC8DD,
                )
            blob.contains("soft") ->
                aura(
                    "Soft Life Explorer",
                    "Luxury calm shapes your discoveries.",
                    listOf("Soft Life", "Chill", "Soul"),
                    0xFF81B29A,
                    0xFFF2E9E4,
                )
            else ->
                aura(
                    "Street Energy",
                    "Urban pulse and bold discovery define your sound.",
                    listOf("Urban", "Discovery", "Heat"),
                    0xFF212121,
                    0xFFFF5722,
                )
        }
    }

    private fun aura(
        name: String,
        desc: String,
        tags: List<String>,
        start: Long,
        end: Long,
    ): MusicAuraProfile =
        MusicAuraProfile(
            name = name,
            description = desc,
            topMoodTags = tags,
            gradientStartHex = start,
            gradientEndHex = end,
            shareText = "My BeatIQ Music Aura is $name — $desc",
        )
}
