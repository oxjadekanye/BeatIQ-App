package com.beatiq.music.features.recommendations.intelligence

import com.beatiq.music.data.model.OnlineRecommendation
import com.beatiq.music.features.mood.MoodDefinition
import java.time.LocalTime

object RecommendationReasonGenerator {
    fun reason(
        item: OnlineRecommendation,
        signals: RecommendationSignals,
        mood: MoodDefinition?,
        score: Float,
    ): String {
        val artist = item.artist.trim()
        if (signals.tasteArtist != null && artist.equals(signals.tasteArtist, ignoreCase = true)) {
            return "Similar to your recent discoveries"
        }
        if (signals.saves > 2 && item.hasPreviewAudio) {
            return "Because you save previews you love"
        }
        if (mood != null && mood.preferredGenres.any { item.genreHint.contains(it, ignoreCase = true) }) {
            return "Perfect for ${mood.displayName.lowercase()}"
        }
        val hour = LocalTime.now().hour
        if (hour >= 21 || hour < 5) {
            return "Great for late night listening"
        }
        if (item.hasPreviewAudio) {
            return "Free legal preview ready to play"
        }
        if (item.opensInInternalBrowser) {
            return "Browse safely on ${item.sourceLabel ?: "the web"}"
        }
        if (score > 1.2f) {
            return "Trending energy with a smooth rhythm"
        }
        return "Fresh pick for your current mood"
    }
}
