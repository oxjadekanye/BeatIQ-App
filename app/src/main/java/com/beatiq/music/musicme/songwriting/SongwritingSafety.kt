package com.beatiq.music.musicme.songwriting

import java.security.MessageDigest

/**
 * Screens user-provided text before anything is sent for generation. Patterns are deliberately
 * specific: genre and era influences ("90s R&B", "Afrobeats") are fine; reproducing an existing
 * song's lyrics or imitating an identifiable artist's voice is not. The service applies the same
 * rules server-side; this check gives the user an immediate, specific explanation.
 */
object SongwritingSafety {
    private val copyrightPatterns = listOf(
        Regex("""\b(copy|reproduce|use|paste|quote|write out|give me|include)\b[^.\n]{0,40}\b(exact|original|actual|real|full|same)\b[^.\n]{0,20}\b(lyrics|words|verses?|chorus)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(the|its|their)\s+(exact|original|actual|real|full)\s+lyrics\b""", RegexOption.IGNORE_CASE),
        Regex("""\blyrics\s+(to|of|from)\s+["“'‘]""", RegexOption.IGNORE_CASE),
        Regex("""\bsame\s+lyrics\s+as\b""", RegexOption.IGNORE_CASE),
        Regex("""\bword[\s-]for[\s-]word\b""", RegexOption.IGNORE_CASE),
    )

    // "Name" = one or more capitalised words, e.g. "Burna Boy", "Whitney Houston".
    private const val NAME = """(?:[A-Z][\p{L}'’.-]+(?:\s+[A-Z][\p{L}'’.-]+){0,3})"""
    private val voicePatterns = listOf(
        Regex("""(?i:\b(?:in|with|using)\s+(?:the\s+)?(?:exact\s+)?voice\s+of)\s+$NAME"""),
        Regex("""(?i:\b(?:sound|sing|sings|sounding|singing)\s+(?:exactly\s+|just\s+)?like)\s+$NAME"""),
        Regex("""(?i:\b(?:clone|copy|imitate|mimic|replicate|impersonate)\s+(?:the\s+)?(?:voice|vocals|singing)\s+of)\s+$NAME"""),
        Regex("""(?i:\b(?:clone|copy|imitate|mimic|replicate|impersonate))\s+$NAME(?i:'s|’s)\s+(?i:voice|vocals)"""),
        Regex("""\b(?:voice[\s-]?clone|deepfake)\b""", RegexOption.IGNORE_CASE),
    )

    fun screen(fields: Map<String, String>): List<SpecIssue> {
        val issues = mutableListOf<SpecIssue>()
        for ((field, value) in fields) {
            if (value.isBlank()) continue
            if (copyrightPatterns.any { it.containsMatchIn(value) }) {
                issues += SpecIssue(
                    SpecIssueCode.COPYRIGHTED_LYRICS, Severity.BLOCKING, field,
                    "MusicMe writes original lyrics and can't reproduce an existing song's words. " +
                        "Describe the feeling or message you want instead.",
                )
            }
            if (voicePatterns.any { it.containsMatchIn(value) }) {
                issues += SpecIssue(
                    SpecIssueCode.ARTIST_VOICE_IMITATION, Severity.BLOCKING, field,
                    "MusicMe can't imitate a specific artist's voice. Describe the vocal qualities instead, " +
                        "for example \"warm, raspy baritone\".",
                )
            }
        }
        return issues.distinctBy { it.code to it.field }
    }
}

/**
 * Structured, content-free logging for songwriting. Only whitelisted keys are emitted, ids are
 * hashed, and values are restricted to short codes and numbers, so stories, messages, names and
 * lyrics can never reach logcat or crash reports through this path.
 */
class SongwritingLog(private val sink: (String) -> Unit) {
    fun event(name: String, vararg fields: Pair<String, Any?>) {
        val safe = fields.filter { (key, value) -> key in ALLOWED_KEYS && value != null }.joinToString(" ") { (key, value) ->
            val rendered = when {
                key.endsWith("_id") || key == "idempotency_key" -> hash(value.toString())
                value is Number || value is Boolean || value is Enum<*> -> value.toString()
                else -> value.toString().take(40).filter { it.isLetterOrDigit() || it == '_' || it == '.' }
            }
            "$key=$rendered"
        }
        sink("musicme.songwriting $name${if (safe.isEmpty()) "" else " $safe"}")
    }

    companion object {
        private val ALLOWED_KEYS = setOf(
            "draft_id", "job_id", "idempotency_key", "state", "code", "attempt", "sections", "issues",
            "duration_ms", "engine", "contract",
        )

        fun hash(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).take(6).joinToString("") { "%02x".format(it) }

        val androidLog = SongwritingLog { message -> android.util.Log.i("MusicMeSongwriting", message) }
    }
}
