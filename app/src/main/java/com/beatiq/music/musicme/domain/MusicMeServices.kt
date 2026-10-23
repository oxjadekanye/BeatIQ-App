package com.beatiq.music.musicme.domain

import com.beatiq.music.musicme.data.MusicMeCatalog
import com.beatiq.music.musicme.model.*
import kotlin.random.Random

interface MusicMeComposerService { suspend fun compose(request: MusicMeGenerationRequest): Result<String> }
interface MusicMeGenerationService { suspend fun enqueue(request: MusicMeGenerationRequest): Result<String> }
interface MusicMeLyricsService { suspend fun createLyrics(input: MusicMeInput, configuration: MusicMeConfiguration): Result<MusicMeLyricsDraft> }
interface MusicMeAudioService { suspend fun resolveAudio(requestId: String): Result<String> }
interface MusicMeStorageService { suspend fun saveDraft(draft: MusicMeDraft); suspend fun deleteDraft(id: String) }
interface MusicMeTranscriptionService { suspend fun transcribe(recording: MusicMeVoiceRecording): Result<String> }
interface MusicMeRecommendationService {
    fun recommend(input: MusicMeInput, current: MusicMeConfiguration = MusicMeConfiguration()): Recommendation
    fun surprise(seed: Long = System.nanoTime()): MusicMeConfiguration
}

object MusicMeConfigurationValidator {
    fun normalize(configuration: MusicMeConfiguration): MusicMeConfiguration {
        val selections = configuration.selections.toMutableMap()
        if ("instrumental_only" in selections[OptionCategory.VOCAL_ARRANGEMENT].orEmpty()) {
            selections[OptionCategory.VOCAL_CHARACTER] = emptySet()
            selections[OptionCategory.VOCAL_DELIVERY] = emptySet()
        }
        val primary = configuration.genres.primaryGenreId
        val secondary = configuration.genres.secondaryGenreIds.filterNot { it == primary }.take(3).toSet()
        val duration = configuration.controls.durationSeconds.coerceIn(30, 600)
        return configuration.copy(
            genres = configuration.genres.copy(secondaryGenreIds = secondary),
            selections = selections,
            bpm = configuration.bpm.coerceIn(40, 220),
            controls = configuration.controls.copy(durationSeconds = duration),
        )
    }

    fun isValid(configuration: MusicMeConfiguration): Boolean {
        val selections = configuration.selections
        return configuration.bpm in 40..220 && configuration.controls.durationSeconds in 30..600 &&
            configuration.genres.primaryGenreId !in configuration.genres.secondaryGenreIds &&
            !("instrumental_only" in selections[OptionCategory.VOCAL_ARRANGEMENT].orEmpty() &&
                (selections[OptionCategory.VOCAL_CHARACTER].orEmpty().isNotEmpty() || selections[OptionCategory.VOCAL_DELIVERY].orEmpty().isNotEmpty()))
    }
}

data class Recommendation(val configuration: MusicMeConfiguration, val explanation: String)

class LocalMusicMeRecommendationService : MusicMeRecommendationService {
    override fun recommend(input: MusicMeInput, current: MusicMeConfiguration): Recommendation {
        val searchable = "${input.occasionId} ${input.story} ${input.desiredMessage} ${input.emotionalContext}".lowercase()
        val rule = when {
            searchable.containsAny("birthday", "celebrat") -> Rule("afrobeats", "pop", setOf("joyful", "uplifting", "energetic"), "energetic", "medium_fast", 112, "Birthday Celebration", "A bright Afrobeats/Pop celebration matches the occasion.")
            searchable.containsAny("memorial", "remember", "loss", "tribute") -> Rule("acoustic_pop", "soul", setOf("reflective", "tender"), "gentle", "slow", 72, "Emotional Tribute", "Gentle acoustic Soul leaves space for reflection.")
            searchable.containsAny("worship", "gospel", "faith", "prayer") -> Rule("gospel", "worship", setOf("spiritual", "uplifting"), "gospel_inspired", "medium_slow", 82, "Gospel Worship", "Choir-ready Gospel and Worship settings support a spiritual message.")
            searchable.containsAny("wedding", "proposal", "love", "romantic", "anniversary") -> Rule("contemporary_r_and_b", "soul", setOf("romantic", "heartwarming"), "romantic", "medium_slow", 84, "Romantic Duet", "Warm R&B/Soul gives a personal dedication emotional intimacy.")
            searchable.containsAny("motivat", "inspir", "graduation", "encourag") -> Rule("pop", "cinematic", setOf("inspirational", "triumphant"), "powerful_belting", "medium", 100, "Inspirational Anthem", "A building Pop/Cinematic arrangement supports an uplifting message.")
            else -> Rule(current.genres.primaryGenreId, current.genres.secondaryGenreIds.firstOrNull() ?: "soul", setOf("heartwarming", "hopeful"), "emotional", "medium_slow", current.bpm, current.arrangementName, "Balanced studio defaults keep your story central.")
        }
        val selections = current.selections.toMutableMap().apply {
            this[OptionCategory.MOOD] = rule.moods
            this[OptionCategory.VOCAL_DELIVERY] = setOf(rule.delivery)
            this[OptionCategory.TEMPO] = setOf(rule.tempo)
            this[OptionCategory.OCCASION] = setOf(input.occasionId)
        }
        val arrangement = MusicMeCatalog.arrangements[rule.arrangement].orEmpty()
        return Recommendation(current.copy(
            genres = MusicMeGenreSelection(rule.primary, setOf(rule.secondary), mapOf(rule.primary to 70, rule.secondary to 30)),
            selections = selections, bpm = rule.bpm, arrangementName = rule.arrangement, sections = arrangement,
        ), rule.explanation)
    }

    override fun surprise(seed: Long): MusicMeConfiguration {
        val random = Random(seed)
        val primary = MusicMeCatalog.genres.random(random).id
        val secondaries = MusicMeCatalog.genres.filterNot { it.id == primary }.shuffled(random).take(2).map { it.id }.toSet()
        val arrangement = MusicMeCatalog.arrangements.entries.random(random)
        fun picks(category: OptionCategory, count: Int) = MusicMeCatalog.all.getValue(category).shuffled(random).take(count).map { it.id }.toSet()
        val selections = defaultSelections().toMutableMap().apply {
            this[OptionCategory.MOOD] = picks(OptionCategory.MOOD, 2)
            this[OptionCategory.ERA] = picks(OptionCategory.ERA, 1)
            this[OptionCategory.VOCAL_ARRANGEMENT] = picks(OptionCategory.VOCAL_ARRANGEMENT, 1)
            this[OptionCategory.VOCAL_CHARACTER] = picks(OptionCategory.VOCAL_CHARACTER, 2)
            this[OptionCategory.VOCAL_DELIVERY] = picks(OptionCategory.VOCAL_DELIVERY, 1)
            this[OptionCategory.RHYTHM] = picks(OptionCategory.RHYTHM, 1)
            this[OptionCategory.BEATS] = picks(OptionCategory.BEATS, 1)
            this[OptionCategory.INSTRUMENT] = picks(OptionCategory.INSTRUMENT, 3)
            this[OptionCategory.LYRICAL_STYLE] = picks(OptionCategory.LYRICAL_STYLE, 2)
            this[OptionCategory.PRODUCTION_STYLE] = picks(OptionCategory.PRODUCTION_STYLE, 2)
        }
        return MusicMeConfigurationValidator.normalize(MusicMeConfiguration(
            mode = CreationMode.SURPRISE, genres = MusicMeGenreSelection(primary, secondaries, (listOf(primary) + secondaries).associateWith { if (it == primary) 60 else 20 }),
            selections = selections, bpm = random.nextInt(72, 129), emotionalProgression = listOf("Intimate → euphoric", "Reflective → triumphant", "Playful throughout", "Gentle → powerful").random(random),
            arrangementName = arrangement.key, sections = arrangement.value,
        ))
    }

    private data class Rule(val primary: String, val secondary: String, val moods: Set<String>, val delivery: String, val tempo: String, val bpm: Int, val arrangement: String, val explanation: String)
    private fun String.containsAny(vararg values: String) = values.any(::contains)
}
