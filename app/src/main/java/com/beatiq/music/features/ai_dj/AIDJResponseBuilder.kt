package com.beatiq.music.features.ai_dj

import android.content.Context
import com.beatiq.music.core.recommendations.OnlineRecommendationEngine
import com.beatiq.music.data.model.Song
import com.beatiq.music.features.home.HomeFeedEnricher
import com.beatiq.music.features.mood.MoodCatalog
import com.beatiq.music.features.mood.MoodEngine
import com.beatiq.music.features.mood.MoodId
import com.beatiq.music.features.mood.MoodStore
import com.beatiq.music.features.recommendations.intelligence.UserMusicPreferenceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AIDJResponseBuilder {
    suspend fun respond(
        context: Context,
        command: AIDJCommand,
        library: List<Song>,
    ): AIDJResponse =
        withContext(Dispatchers.IO) {
            val moodId =
                when (command.intent) {
                    AIDJIntent.LATE_NIGHT -> MoodId.LATE_NIGHT_DRIVE
                    AIDJIntent.GYM_ENERGY -> MoodId.GYM_MODE
                    AIDJIntent.CALM_FOCUS -> MoodId.DEEP_FOCUS
                    AIDJIntent.NIGERIAN_HEAT, AIDJIntent.TRENDING_AFROBEATS -> MoodId.AFROBEATS_ENERGY
                    else -> MoodStore(context).getSelectedMoodId()
                }
            MoodStore(context).setSelectedMoodId(moodId)
            UserMusicPreferenceStore(context).record(
                com.beatiq.music.features.recommendations.intelligence.SignalEventType.MOOD_SELECT,
            )
            val mood = MoodCatalog.byId(moodId)
            val genre = MoodEngine.genreTabForMood(mood)
            val term = command.searchTerms.firstOrNull() ?: mood.searchTerms.first()
            val feed =
                OnlineRecommendationEngine.loadHomeFeed(
                    genreTab = genre,
                    library = library,
                )
            val enriched = HomeFeedEnricher.enrich(context, feed)
            val signals = UserMusicPreferenceStore(context).loadSignals()
            val queue = AIDJQueueBuilder.buildQueue(enriched, feed, signals, mood)
            val message =
                when {
                    queue.isEmpty() -> AIDJQueueBuilder.emptyStateMessage(command.intent)
                    command.intent == AIDJIntent.LATE_NIGHT ->
                        "I found a smooth late-night mix based on your recent vibe."
                    command.intent == AIDJIntent.GYM_ENERGY ->
                        "Gym energy loaded — punchy picks to keep you moving."
                    command.intent == AIDJIntent.UNDERGROUND ->
                        "Digging underground gems with fresh rhythm."
                    command.intent == AIDJIntent.LIKE_SAVED ->
                        "These picks echo what you've been saving lately."
                    command.intent == AIDJIntent.CALM_FOCUS ->
                        "Calm focus queue — steady grooves, low distraction."
                    command.intent == AIDJIntent.NIGERIAN_HEAT ->
                        "Nigerian heat on deck — vibrant Afrobeats energy."
                    command.intent == AIDJIntent.TRENDING_AFROBEATS ->
                        "Trending Afrobeats selections are ready."
                    command.intent == AIDJIntent.SURPRISE ->
                        "Surprise mix unlocked — explore these fresh picks."
                    else ->
                        "Here's a smart mix based on \"$term\" and your BeatIQ taste."
                }
            AIDJResponse(
                message = message,
                queue = queue,
                appliedMoodLabel = mood.displayName,
            )
        }
}
