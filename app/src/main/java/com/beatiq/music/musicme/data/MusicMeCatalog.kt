package com.beatiq.music.musicme.data

import com.beatiq.music.musicme.model.MusicMeOption
import com.beatiq.music.musicme.model.OptionCategory

object MusicMeCatalog {
    private fun id(label: String) = label.lowercase()
        .replace("&", " and ").replace("/", "_").replace("-", "_")
        .replace(Regex("[^a-z0-9]+"), "_").trim('_')

    private fun options(category: OptionCategory, labels: String, multi: Boolean = true): List<MusicMeOption> =
        labels.split('|').distinct().mapIndexed { index, raw ->
            val parts = raw.split("::", limit = 2)
            MusicMeOption(
                id = id(parts[0]), label = parts[0],
                description = parts.getOrElse(1) { descriptionFor(category, parts[0]) },
                category = category, multiSelect = multi,
                recommended = index == 0,
                generationParameter = "${category.name.lowercase()}.${id(parts[0])}",
            )
        }

    private fun descriptionFor(category: OptionCategory, label: String) = when (category) {
        OptionCategory.GENRE -> "$label musical language and arrangement character"
        OptionCategory.ERA -> "$label production references and period character"
        OptionCategory.VOCAL_ARRANGEMENT -> "$label voice arrangement"
        OptionCategory.VOCAL_CHARACTER -> "$label vocal tone and colour"
        OptionCategory.VOCAL_DELIVERY -> "$label phrasing and performance approach"
        OptionCategory.STRUCTURE -> "$label section for the song arrangement"
        OptionCategory.TEMPO -> "$label pacing with a sensible BPM range"
        OptionCategory.RHYTHM -> "$label rhythmic feel and meter"
        OptionCategory.MOOD -> "$label emotional direction"
        OptionCategory.BEATS -> "$label percussion foundation"
        OptionCategory.INSTRUMENT -> "$label in the instrumental palette"
        OptionCategory.LANGUAGE -> "$label lyric language"
        OptionCategory.LYRICAL_STYLE -> "$label writing approach"
        OptionCategory.PRODUCTION_STYLE -> "$label mix and production aesthetic"
        OptionCategory.OCCASION -> "$label song context"
    }

    val genres = options(OptionCategory.GENRE,
        "R&B|Contemporary R&B|Classic R&B|Alternative R&B|Neo-Soul|Soul|Motown|Funk|Hip-Hop|Old-School Hip-Hop|Boom Bap|Trap|Drill|Melodic Rap|Conscious Rap|Jazz Rap|Pop|Dance Pop|Synth Pop|Indie Pop|Electropop|Afrobeats|Afro-Pop|Afro-Fusion|Afro-Soul|Afroswing|Amapiano|Afro-House|Afro-Tech|Highlife|Juju|Fuji|Apala|Makossa|Soukous|Kizomba|Bongo Flava|Reggae|Roots Reggae|Dancehall|Lovers Rock|Ska|Gospel|Contemporary Gospel|Traditional Gospel|Gospel Choir|Worship|Praise|Jazz|Smooth Jazz|Acid Jazz|Blues|Country|Country Pop|Folk|Rock|Soft Rock|Alternative Rock|Indie Rock|EDM|House|Deep House|Tech House|Progressive House|Trance|Drum & Bass|Lo-fi|Classical|Orchestral|Cinematic|Latin Pop|Reggaeton|Salsa|Bachata|Bossa Nova|K-Pop|J-Pop|Ambient|Chillout|Disco|UK Garage|Grime|Soulful House|Acoustic Pop|World Fusion"
    )
    val eras = options(OptionCategory.ERA, "1950s|1960s|1970s|1980s|1990s|2000s|2010s|2020s|Old School|Vintage|Retro|Classic|Millennial|Gen Z|Contemporary|New Generation|Futuristic|Timeless", false)
    val vocalArrangements = options(OptionCategory.VOCAL_ARRANGEMENT, "Male Solo|Female Solo|Male/Female Duet|Two Male Voices|Two Female Voices|Mixed Group|Male Choir|Female Choir|Mixed Choir|Lead and Backing Vocals|Call-and-Response|Rap and Singing|Spoken Word and Singing|Instrumental Only", false)
    val vocalCharacters = options(OptionCategory.VOCAL_CHARACTER, "Bass|Baritone|Tenor|Countertenor|Alto|Mezzo-Soprano|Soprano|Falsetto|Deep|Warm|Silky|Smooth|Soulful|Husky|Raspy|Airy|Breathy|Powerful|Delicate|Bright|Dark|Intimate|Expressive|Resonant")
    val vocalDeliveries = options(OptionCategory.VOCAL_DELIVERY, "Melodic|Conversational|Emotional|Romantic|Energetic|Gentle|Dramatic|Rhythmic|Gospel-Inspired|Melismatic|Rap|Harmonised|Whispered|Spoken Word|Powerful Belting|Soft Ballad|Intimate Storytelling")
    val structures = options(OptionCategory.STRUCTURE, "AI Automatic|2 Verses|3 Verses|4 Verses|Intro|Verse|Pre-Chorus|Chorus|Post-Chorus|Hook|Repeated Hook|Bridge|Breakdown|Instrumental Break|Rap Verse|Spoken Interlude|Solo|Final Chorus|Outro|Fade-Out|Duet Exchange|Call-and-Response")
    val tempos = options(OptionCategory.TEMPO, "Very Slow|Slow|Medium-Slow|Medium|Medium-Fast|Fast|Very Fast|AI Automatic", false)
    val rhythms = options(OptionCategory.RHYTHM, "Straight|Swing|Shuffle|Syncopated|Half-Time|Double-Time|4/4|3/4|6/8|12/8|Dance Groove|Afro Groove|Reggae Groove|Jazz Swing|AI Automatic", false)
    val moods = options(OptionCategory.MOOD, "Romantic|Passionate|Heartwarming|Emotional|Joyful|Celebratory|Nostalgic|Hopeful|Inspirational|Motivational|Triumphant|Peaceful|Spiritual|Reflective|Melancholic|Heartbroken|Vulnerable|Dreamy|Playful|Confident|Bold|Empowering|Energetic|Relaxed|Intimate|Epic|Cinematic|Mysterious|Uplifting|Grateful|Sentimental|Euphoric|Tender|Bittersweet|Serene")
    val beats = options(OptionCategory.BEATS, "Afrobeats Groove|Amapiano Log Drum|Trap 808|Boom Bap|Classic Hip-Hop|Drill|Dancehall|Reggae One Drop|Highlife Rhythm|Fuji Percussion|Gospel Drums|Funk Groove|Jazz Swing|House Four-on-the-Floor|Deep House|EDM|Acoustic Drums|Live Band|Latin Percussion|Talking Drum|Congas|Bongos|Shakers|Claps|Rimshots|Electronic Percussion|Minimal Beat|Cinematic Percussion|Tribal Percussion|Orchestral Drums")
    val instruments = options(OptionCategory.INSTRUMENT, "Grand Piano|Electric Piano|Rhodes|Organ|Synthesiser|Acoustic Guitar|Electric Guitar|Bass Guitar|Upright Bass|Violin|Viola|Cello|Strings|Saxophone|Trumpet|Trombone|Flute|Clarinet|Harp|Acoustic Drums|Electronic Drums|Talking Drum|Congas|Bongos|Djembe|Kalimba|Marimba|Xylophone|Steel Pan|Accordion|Harmonica|Ukulele|Banjo|Sitar|Kora|Balafon|Brass Section|String Ensemble|Choir Pads|Synth Bass|Sub-Bass|Arpeggiator|Orchestral Percussion|Ambient Pads")
    val languages = options(OptionCategory.LANGUAGE, "English|Yoruba|Igbo|Hausa|Nigerian Pidgin|French|Spanish|Portuguese|Swahili|Arabic|Hindi|German|Italian|Japanese|Korean|Dutch|Turkish|Zulu")
    val lyricalStyles = options(OptionCategory.LYRICAL_STYLE, "Poetic|Conversational|Storytelling|Romantic|Inspirational|Spiritual|Humorous|Emotional|Narrative|Metaphorical|Rhyming|Simple|Sophisticated|Repetitive Hook|Rap Bars|Spoken Word|Clean Lyrics|Explicit Lyrics|Personal Dedication|Motivational Speech|Reflective|Cinematic Storytelling")
    val productionStyles = options(OptionCategory.PRODUCTION_STYLE, "Studio Polished|Radio Ready|Warm Vintage|Analog|Modern Digital|Live Concert|Intimate Acoustic|Orchestral|Cinematic|Club Mix|Festival Mix|Lo-fi|Minimalist|Atmospheric|Bass Heavy|Vocal Forward|Instrumental Forward|Wide Stereo|Retro Recording|Clean Contemporary|Soft and Warm|Bright and Punchy|Ambient and Experimental")
    val occasions = options(OptionCategory.OCCASION, "Birthday|Anniversary|Wedding|Proposal|Love Declaration|Friendship|Appreciation|Graduation|New Baby|Family Tribute|Mother's Day|Father's Day|Retirement|Congratulations|Motivation|Encouragement|Memorial|Remembrance|Apology|Reconciliation|Celebration|Worship|Personal Story|Business Jingle|Children's Song|Holiday|Christmas|New Year|Custom Occasion", false)

    val all: Map<OptionCategory, List<MusicMeOption>> = mapOf(
        OptionCategory.GENRE to genres, OptionCategory.ERA to eras,
        OptionCategory.VOCAL_ARRANGEMENT to vocalArrangements,
        OptionCategory.VOCAL_CHARACTER to vocalCharacters,
        OptionCategory.VOCAL_DELIVERY to vocalDeliveries,
        OptionCategory.STRUCTURE to structures, OptionCategory.TEMPO to tempos,
        OptionCategory.RHYTHM to rhythms, OptionCategory.MOOD to moods,
        OptionCategory.BEATS to beats, OptionCategory.INSTRUMENT to instruments,
        OptionCategory.LANGUAGE to languages, OptionCategory.LYRICAL_STYLE to lyricalStyles,
        OptionCategory.PRODUCTION_STYLE to productionStyles, OptionCategory.OCCASION to occasions,
    )

    val arrangements = mapOf(
        "Classic Pop" to listOf("Intro", "Verse 1", "Pre-Chorus", "Chorus", "Verse 2", "Chorus", "Bridge", "Final Chorus", "Outro"),
        "R&B Ballad" to listOf("Intro", "Verse 1", "Chorus", "Verse 2", "Chorus", "Bridge", "Final Chorus", "Outro"),
        "Afrobeats Hit" to listOf("Intro", "Hook", "Verse 1", "Chorus", "Verse 2", "Breakdown", "Final Chorus", "Outro"),
        "Hip-Hop Storytelling" to listOf("Intro", "Verse 1", "Hook", "Verse 2", "Hook", "Verse 3", "Outro"),
        "Gospel Worship" to listOf("Intro", "Verse", "Chorus", "Verse", "Chorus", "Bridge", "Call-and-Response", "Final Chorus", "Outro"),
        "Romantic Duet" to listOf("Intro", "Solo A", "Solo B", "Duet Chorus", "Duet Exchange", "Bridge", "Final Chorus", "Outro"),
        "Dance Anthem" to listOf("Intro", "Verse", "Build", "Drop", "Verse", "Build", "Final Drop", "Outro"),
        "Emotional Tribute" to listOf("Intro", "Verse 1", "Chorus", "Verse 2", "Bridge", "Final Chorus", "Outro"),
        "Birthday Celebration" to listOf("Intro", "Verse", "Hook", "Chorus", "Call-and-Response", "Final Chorus", "Outro"),
        "Wedding Song" to listOf("Intro", "Verse 1", "Chorus", "Verse 2", "Bridge", "Final Chorus", "Outro"),
        "Inspirational Anthem" to listOf("Intro", "Verse", "Pre-Chorus", "Chorus", "Verse", "Bridge", "Final Chorus", "Outro"),
        "Cinematic Ballad" to listOf("Orchestral Intro", "Verse", "Chorus", "Instrumental Break", "Bridge", "Final Chorus", "Outro"),
        "Acoustic Storytelling" to listOf("Intro", "Verse 1", "Refrain", "Verse 2", "Refrain", "Verse 3", "Outro"),
    )

    fun search(category: OptionCategory, query: String): List<MusicMeOption> =
        all[category].orEmpty().filter { query.isBlank() || it.label.contains(query, true) || it.description.contains(query, true) }
}
