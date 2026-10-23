package com.beatiq.music.musicme.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.beatiq.music.core.auth.AccountStorageKey
import com.beatiq.music.musicme.model.*
import android.util.Base64
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private val Context.musicMeStore by preferencesDataStore("musicme_phase1")

class MusicMeRepository(context: Context, accountId: String) {
    private val context = context.applicationContext
    val accountKey: String = AccountStorageKey.fromUserId(accountId)
    private val draftsKey = stringPreferencesKey("account_${accountKey}_drafts_v2")
    private val presetsKey = stringPreferencesKey("account_${accountKey}_presets_v2")
    private val migrationCompleteKey = booleanPreferencesKey("account_${accountKey}_migration_v2_complete")
    private val songwritingConsentKey = booleanPreferencesKey("account_${accountKey}_songwriting_consent_v1")
    private val migrationMutex = Mutex()

    /** Whether this account agreed to send draft text to the songwriting service (asked before the first request). */
    suspend fun hasSongwritingConsent(): Boolean = context.musicMeStore.data.first()[songwritingConsentKey] == true

    suspend fun setSongwritingConsent(granted: Boolean) {
        context.musicMeStore.edit { prefs -> prefs[songwritingConsentKey] = granted }
    }

    val drafts: Flow<List<MusicMeDraft>> = flow {
        ensureAccountMigrated()
        emitAll(context.musicMeStore.data.map { prefs -> decodeDraftsTolerant(prefs[draftsKey].orEmpty()) })
    }
    val presets: Flow<List<MusicMePreset>> = flow {
        ensureAccountMigrated()
        emitAll(context.musicMeStore.data.map { prefs -> decodePresetsTolerant(prefs[presetsKey].orEmpty()) })
    }

    suspend fun upsertDraft(draft: MusicMeDraft) {
        ensureAccountMigrated()
        context.musicMeStore.edit { prefs ->
            prefs[draftsKey] = upsertJsonRecord(prefs[draftsKey].orEmpty(), draft.id, draft.toJson(), JSONObject::toDraft)
        }
    }

    suspend fun deleteDraft(id: String) {
        ensureAccountMigrated()
        var recordingToDelete: String? = null
        context.musicMeStore.edit { prefs ->
            val raw = requireJsonArray(prefs[draftsKey].orEmpty())
            val out = JSONArray()
            for (index in 0 until raw.length()) {
                val value = raw.get(index)
                val decoded = (value as? JSONObject)?.let { runCatching { it.toDraft() }.getOrNull() }
                if (decoded?.id == id) {
                    recordingToDelete = decoded.recording?.localFileName
                } else {
                    out.put(value)
                }
            }
            prefs[draftsKey] = out.toString()
        }
        recordingToDelete?.let(::deleteRecording)
    }

    suspend fun duplicateDraft(id: String) {
        ensureAccountMigrated()
        context.musicMeStore.edit { prefs ->
            val raw = requireJsonArray(prefs[draftsKey].orEmpty())
            val source = decodedDrafts(raw).firstOrNull { it.id == id } ?: return@edit
            val now = System.currentTimeMillis()
            val copy = source.copy(
                id = java.util.UUID.randomUUID().toString(),
                name = "${source.name} copy",
                createdAt = now,
                modifiedAt = now,
                recording = null,
            )
            prefs[draftsKey] = appendJsonRecord(raw, copy.toJson())
        }
    }

    suspend fun savePreset(preset: MusicMePreset) {
        ensureAccountMigrated()
        context.musicMeStore.edit { prefs ->
            prefs[presetsKey] = upsertJsonRecord(prefs[presetsKey].orEmpty(), preset.id, preset.toJson(), JSONObject::toPreset)
        }
    }

    fun recordingFile(id: String): File = recordingRoot().resolve(
        "recording_${id.filter { ch -> ch.isLetterOrDigit() || ch == '-' }}.m4a",
    )

    /** Read-only check that a draft's voice note still has non-empty audio in this account's folder. */
    fun hasRecordingFile(recording: MusicMeVoiceRecording): Boolean =
        runCatching { safeRecordingFile(recording.localFileName).let { it.isFile && it.length() > 0 } }.getOrDefault(false)

    suspend fun exportAccountBackup(): MusicMeAccountBackup {
        ensureAccountMigrated()
        val prefs = context.musicMeStore.data.first()
        val draftsRaw = requireJsonArray(prefs[draftsKey].orEmpty()).toString()
        val presetsRaw = requireJsonArray(prefs[presetsKey].orEmpty()).toString()
        val recordings =
            decodeDraftsTolerant(draftsRaw).mapNotNull { draft ->
                val fileName = draft.recording?.localFileName ?: return@mapNotNull null
                val file = safeRecordingFile(fileName)
                if (!file.isFile) return@mapNotNull null
                val bytes = file.readBytes()
                MusicMeRecordingBackup(
                    fileName = file.name,
                    sha256 = sha256(bytes),
                    base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
                )
            }.distinctBy(MusicMeRecordingBackup::fileName)
        return MusicMeAccountBackup(draftsRaw, presetsRaw, recordings)
    }

    suspend fun mergeAccountBackup(backup: MusicMeAccountBackup): MusicMeMergeResult {
        ensureAccountMigrated()
        val incomingDrafts = requireJsonArray(backup.draftsJson)
        val incomingPresets = requireJsonArray(backup.presetsJson)
        var draftsAdded = 0
        var presetsAdded = 0
        var unreadableSkipped = 0
        context.musicMeStore.edit { prefs ->
            val draftMerge = mergeJsonCollections(
                existing = requireJsonArray(prefs[draftsKey].orEmpty()),
                incoming = incomingDrafts,
                decoder = JSONObject::toDraft,
                id = MusicMeDraft::id,
            )
            val presetMerge = mergeJsonCollections(
                existing = requireJsonArray(prefs[presetsKey].orEmpty()),
                incoming = incomingPresets,
                decoder = JSONObject::toPreset,
                id = MusicMePreset::id,
            )
            prefs[draftsKey] = draftMerge.json
            prefs[presetsKey] = presetMerge.json
            draftsAdded = draftMerge.added
            presetsAdded = presetMerge.added
            unreadableSkipped = draftMerge.unreadableSkipped + presetMerge.unreadableSkipped
        }
        var recordingsAdded = 0
        var recordingConflicts = 0
        backup.recordings.forEach { recording ->
            val decoded = runCatching { Base64.decode(recording.base64, Base64.DEFAULT) }.getOrNull()
            if (decoded == null || sha256(decoded) != recording.sha256) {
                recordingConflicts++
            } else {
                when (restoreRecording(recording.fileName, decoded)) {
                    RecordingRestore.ADDED -> recordingsAdded++
                    RecordingRestore.CONFLICT -> recordingConflicts++
                    RecordingRestore.ALREADY_PRESENT -> Unit
                }
            }
        }
        return MusicMeMergeResult(draftsAdded, presetsAdded, recordingsAdded, unreadableSkipped, recordingConflicts)
    }

    /** Test-only seam for validating the one-time Phase 1 legacy adoption path. */
    internal suspend fun seedLegacyForTest(
        drafts: List<MusicMeDraft>,
        presets: List<MusicMePreset> = emptyList(),
        includeUnreadableDraft: Boolean = false,
    ) {
        context.musicMeStore.edit { prefs ->
            val array = JSONArray(encodeDrafts(drafts))
            if (includeUnreadableDraft) array.put(JSONObject().put("unreadable", true))
            prefs[LEGACY_DRAFTS_KEY] = array.toString()
            prefs[LEGACY_PRESETS_KEY] = encodePresets(presets)
            prefs.remove(LEGACY_OWNER_KEY)
            prefs.remove(migrationCompleteKey)
            prefs.remove(draftsKey)
            prefs.remove(presetsKey)
        }
    }

    internal suspend fun rawDraftsForTest(): String {
        ensureAccountMigrated()
        return context.musicMeStore.data.first()[draftsKey].orEmpty()
    }

    private suspend fun ensureAccountMigrated() {
        migrationMutex.withLock {
            if (context.musicMeStore.data.first()[migrationCompleteKey] == true) return
            var adoptedLegacy = false
            var legacyDrafts = ""
            context.musicMeStore.edit { prefs ->
                if (prefs[migrationCompleteKey] == true) return@edit
                legacyDrafts = prefs[LEGACY_DRAFTS_KEY].orEmpty()
                val legacyPresets = prefs[LEGACY_PRESETS_KEY].orEmpty()
                val legacyOwner = prefs[LEGACY_OWNER_KEY]
                val mayAdopt = legacyOwner == null && (legacyDrafts.isNotBlank() || legacyPresets.isNotBlank())
                if (mayAdopt) {
                    if (prefs[draftsKey] == null) prefs[draftsKey] = legacyDrafts.ifBlank { "[]" }
                    if (prefs[presetsKey] == null) prefs[presetsKey] = legacyPresets.ifBlank { "[]" }
                    prefs[LEGACY_OWNER_KEY] = accountKey
                    adoptedLegacy = true
                } else {
                    if (prefs[draftsKey] == null) prefs[draftsKey] = "[]"
                    if (prefs[presetsKey] == null) prefs[presetsKey] = "[]"
                }
                prefs[migrationCompleteKey] = true
            }
            if (adoptedLegacy) copyLegacyRecordings(legacyDrafts)
        }
    }

    private fun copyLegacyRecordings(raw: String) {
        decodeDraftsTolerant(raw).forEach { draft ->
            val name = draft.recording?.localFileName ?: return@forEach
            val source = File(File(context.filesDir, "musicme_recordings"), File(name).name)
            val target = safeRecordingFile(name)
            if (source.isFile && !target.exists()) runCatching { source.copyTo(target, overwrite = false) }
        }
    }

    private fun recordingRoot(): File =
        File(context.filesDir, "musicme_recordings/$accountKey").also { it.mkdirs() }.canonicalFile

    private fun safeRecordingFile(fileName: String): File {
        val root = recordingRoot()
        val target = File(root, File(fileName).name).canonicalFile
        require(target.parentFile == root) { "Unsafe recording path" }
        return target
    }

    private fun deleteRecording(fileName: String) {
        safeRecordingFile(fileName).delete()
    }

    private fun restoreRecording(fileName: String, bytes: ByteArray): RecordingRestore {
        val target = safeRecordingFile(fileName)
        if (target.exists()) {
            return if (sha256(target.readBytes()) == sha256(bytes)) {
                RecordingRestore.ALREADY_PRESENT
            } else {
                RecordingRestore.CONFLICT
            }
        }
        val temp = File(target.parentFile, ".${target.name}.${java.util.UUID.randomUUID()}.importing")
        return try {
            FileOutputStream(temp).use { stream ->
                stream.write(bytes)
                stream.fd.sync()
            }
            if (!temp.renameTo(target)) throw IllegalStateException("Could not finalize recording restore")
            RecordingRestore.ADDED
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    companion object {
        private val LEGACY_DRAFTS_KEY = stringPreferencesKey("drafts_v1")
        private val LEGACY_PRESETS_KEY = stringPreferencesKey("presets_v1")
        private val LEGACY_OWNER_KEY = stringPreferencesKey("legacy_v1_owner_account_key")
    }
}

data class MusicMeRecordingBackup(val fileName: String, val sha256: String, val base64: String)
data class MusicMeAccountBackup(
    val draftsJson: String,
    val presetsJson: String,
    val recordings: List<MusicMeRecordingBackup>,
)
data class MusicMeMergeResult(
    val draftsAdded: Int,
    val presetsAdded: Int,
    val recordingsAdded: Int,
    val unreadableSkipped: Int,
    val recordingConflicts: Int,
)

class MusicMePersistenceException(message: String, cause: Throwable? = null) : Exception(message, cause)

private enum class RecordingRestore { ADDED, ALREADY_PRESENT, CONFLICT }
private data class JsonMergeResult(val json: String, val added: Int, val unreadableSkipped: Int)

private fun requireJsonArray(raw: String): JSONArray {
    if (raw.isBlank()) return JSONArray()
    return try {
        JSONArray(raw)
    } catch (error: Throwable) {
        throw MusicMePersistenceException(
            "MusicMe data is unreadable. It was left unchanged so it can be recovered safely.",
            error,
        )
    }
}

private fun decodedDrafts(array: JSONArray): List<MusicMeDraft> =
    (0 until array.length()).mapNotNull { index ->
        (array.opt(index) as? JSONObject)?.let { runCatching { it.toDraft() }.getOrNull() }
    }

private fun decodeDraftsTolerant(raw: String): List<MusicMeDraft> =
    runCatching { decodedDrafts(JSONArray(raw.ifBlank { "[]" })) }.getOrDefault(emptyList())

private fun decodePresetsTolerant(raw: String): List<MusicMePreset> =
    runCatching {
        val array = JSONArray(raw.ifBlank { "[]" })
        (0 until array.length()).mapNotNull { index ->
            (array.opt(index) as? JSONObject)?.let { runCatching { it.toPreset() }.getOrNull() }
        }
    }.getOrDefault(emptyList())

private fun appendJsonRecord(array: JSONArray, record: JSONObject): String {
    val out = JSONArray()
    for (index in 0 until array.length()) out.put(array.get(index))
    out.put(record)
    return out.toString()
}

private fun <T> upsertJsonRecord(
    raw: String,
    recordId: String,
    record: JSONObject,
    decoder: (JSONObject) -> T,
): String {
    val array = requireJsonArray(raw)
    val out = JSONArray()
    for (index in 0 until array.length()) {
        val value = array.get(index)
        val decoded = (value as? JSONObject)?.let { runCatching { decoder(it) }.getOrNull() }
        val existingId =
            when (decoded) {
                is MusicMeDraft -> decoded.id
                is MusicMePreset -> decoded.id
                else -> null
            }
        if (existingId != recordId) out.put(value)
    }
    out.put(record)
    return out.toString()
}

private fun <T> mergeJsonCollections(
    existing: JSONArray,
    incoming: JSONArray,
    decoder: (JSONObject) -> T,
    id: (T) -> String,
): JsonMergeResult {
    val out = JSONArray()
    val existingIds = mutableSetOf<String>()
    for (index in 0 until existing.length()) {
        val value = existing.get(index)
        out.put(value)
        (value as? JSONObject)
            ?.let { runCatching { decoder(it) }.getOrNull() }
            ?.let { existingIds += id(it) }
    }
    var added = 0
    var unreadable = 0
    for (index in 0 until incoming.length()) {
        val value = incoming.get(index)
        val decoded = (value as? JSONObject)?.let { runCatching { decoder(it) }.getOrNull() }
        if (decoded == null) {
            unreadable++
            continue
        }
        if (existingIds.add(id(decoded))) {
            out.put(value)
            added++
        }
    }
    return JsonMergeResult(out.toString(), added, unreadable)
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private fun encodeDrafts(items: List<MusicMeDraft>) = JSONArray().apply { items.forEach { put(it.toJson()) } }.toString()
private fun decodeDrafts(raw: String): List<MusicMeDraft> {
    if (raw.isBlank()) return emptyList()
    val array = JSONArray(raw)
    return List(array.length()) { array.getJSONObject(it).toDraft() }
}

private fun encodePresets(items: List<MusicMePreset>) = JSONArray().apply {
    items.forEach { put(it.toJson()) }
}.toString()
private fun decodePresets(raw: String): List<MusicMePreset> {
    if (raw.isBlank()) return emptyList()
    val array = JSONArray(raw)
    return List(array.length()) {
        array.getJSONObject(it).toPreset()
    }
}

private fun MusicMePreset.toJson() = JSONObject()
    .put("schema", 1)
    .put("id", id)
    .put("name", name)
    .put("createdAt", createdAt)
    .put("configuration", configuration.toJson())

private fun JSONObject.toPreset() = MusicMePreset(
    id = getString("id"),
    name = getString("name"),
    createdAt = getLong("createdAt"),
    configuration = getJSONObject("configuration").toConfiguration(),
)

private fun MusicMeDraft.toJson() = JSONObject()
    .put("schema", 1).put("id", id).put("name", name).put("createdAt", createdAt).put("modifiedAt", modifiedAt)
    .put("status", status.name).put("input", input.toJson()).put("configuration", configuration.toJson())
    .put("lyrics", lyrics.toJson()).put("recording", recording?.toJson() ?: JSONObject.NULL)
    .put("songwriting", songwriting?.toJson() ?: JSONObject.NULL)

private fun JSONObject.toDraft() = MusicMeDraft(
    id = getString("id"), name = optString("name", "Untitled creation"), createdAt = getLong("createdAt"),
    modifiedAt = getLong("modifiedAt"), status = runCatching { MusicMeGenerationStatus.valueOf(getString("status")) }.getOrDefault(MusicMeGenerationStatus.DRAFT),
    input = getJSONObject("input").toInput(), recording = optJSONObject("recording")?.toRecording(),
    configuration = getJSONObject("configuration").toConfiguration(), lyrics = getJSONObject("lyrics").toLyrics(),
    // Optional and tolerant: drafts saved before Phase 2.1 have no songwriting block.
    songwriting = optJSONObject("songwriting")?.let { runCatching { it.toSongwriting() }.getOrNull() },
)

private fun MusicMeInput.toJson() = JSONObject().put("title", title).put("story", story).put("recipient", recipient)
    .put("occasionId", occasionId).put("desiredMessage", desiredMessage).put("emotionalContext", emotionalContext).put("pronunciationNotes", pronunciationNotes)
private fun JSONObject.toInput() = MusicMeInput(optString("title"), optString("story"), optString("recipient"), optString("occasionId", "personal_story"), optString("desiredMessage"), optString("emotionalContext"), optString("pronunciationNotes"))

private fun MusicMeVoiceRecording.toJson() = JSONObject().put("id", id).put("localFileName", localFileName).put("durationMs", durationMs).put("createdAt", createdAt)
private fun JSONObject.toRecording() = MusicMeVoiceRecording(getString("id"), getString("localFileName"), getLong("durationMs"), getLong("createdAt"))

private fun MusicMeConfiguration.toJson() = JSONObject().put("mode", mode.name).put("genres", genres.toJson())
    .put("selections", JSONObject().apply { selections.forEach { (key, values) -> put(key.name, JSONArray(values.toList())) } })
    .put("bpm", bpm).put("emotionalProgression", emotionalProgression).put("arrangementName", arrangementName)
    .put("sections", JSONArray(sections)).put("controls", controls.toJson())
private fun JSONObject.toConfiguration(): MusicMeConfiguration {
    val rawSelections = optJSONObject("selections")
    val selections = buildMap {
        rawSelections?.keys()?.forEach { rawCategory ->
            runCatching { OptionCategory.valueOf(rawCategory) }.getOrNull()?.let { category ->
                put(category, rawSelections.optJSONArray(rawCategory)?.strings()?.toSet().orEmpty())
            }
        }
    }
    return MusicMeConfiguration(
        mode = runCatching { CreationMode.valueOf(optString("mode", CreationMode.QUICK.name)) }.getOrDefault(CreationMode.QUICK),
        genres = getJSONObject("genres").toGenreSelection(), selections = selections,
        bpm = optInt("bpm", 88), emotionalProgression = optString("emotionalProgression"),
        arrangementName = optString("arrangementName", "R&B Ballad"), sections = optJSONArray("sections")?.strings() ?: emptyList(),
        controls = optJSONObject("controls")?.toControls() ?: AdvancedControls(),
    )
}

private fun MusicMeGenreSelection.toJson() = JSONObject().put("primary", primaryGenreId)
    .put("secondary", JSONArray(secondaryGenreIds.toList())).put("weights", JSONObject(weights))
private fun JSONObject.toGenreSelection(): MusicMeGenreSelection {
    val weightObject = optJSONObject("weights") ?: JSONObject()
    val weights = weightObject.keys().asSequence().associateWith { weightObject.optInt(it) }
    return MusicMeGenreSelection(optString("primary", "contemporary_r_and_b"), optJSONArray("secondary")?.strings()?.toSet().orEmpty(), weights)
}

private fun AdvancedControls.toJson() = JSONObject().put("durationSeconds", durationSeconds).put("vocalIntensity", vocalIntensity)
    .put("emotionalIntensity", emotionalIntensity).put("instrumentalComplexity", instrumentalComplexity).put("bassIntensity", bassIntensity)
    .put("chorusRepetition", chorusRepetition).put("harmonyRichness", harmonyRichness).put("lyricDensity", lyricDensity)
    .put("introLength", introLength).put("outroLength", outroLength).put("energyProgression", energyProgression)
    .put("vocalBalance", vocalBalance).put("electronicBalance", electronicBalance).put("syntheticBalance", syntheticBalance)
    .put("fadeIn", fadeIn).put("fadeOut", fadeOut).put("allowExplicit", allowExplicit).put("aiAutomatic", aiAutomatic)
private fun JSONObject.toControls() = AdvancedControls(
    optInt("durationSeconds", 150), optInt("vocalIntensity", 55), optInt("emotionalIntensity", 65),
    optInt("instrumentalComplexity", 50), optInt("bassIntensity", 50), optInt("chorusRepetition", 50),
    optInt("harmonyRichness", 55), optInt("lyricDensity", 50), optInt("introLength", 20), optInt("outroLength", 20),
    optInt("energyProgression", 60), optInt("vocalBalance", 60), optInt("electronicBalance", 50), optInt("syntheticBalance", 45),
    optBoolean("fadeIn"), optBoolean("fadeOut", true), optBoolean("allowExplicit"), optBoolean("aiAutomatic", true),
)

private fun MusicMeLyricsDraft.toJson() = JSONObject().put("title", title).put("sections", JSONArray().apply { sections.forEach { put(it.toJson()) } })
private fun JSONObject.toLyrics() = MusicMeLyricsDraft(optString("title"), optJSONArray("sections")?.let { arr -> List(arr.length()) { arr.getJSONObject(it).toSongSection() } } ?: emptyList())
private fun MusicMeSongSection.toJson() = JSONObject().put("id", id).put("type", type).put("label", label).put("lyrics", lyrics).put("order", order)
    .put("origin", origin.name).put("performanceNotes", performanceNotes)
private fun JSONObject.toSongSection() = MusicMeSongSection(
    getString("id"), getString("type"), getString("label"), optString("lyrics"), getInt("order"),
    origin = runCatching { SectionOrigin.valueOf(optString("origin", SectionOrigin.USER.name)) }.getOrDefault(SectionOrigin.USER),
    performanceNotes = optString("performanceNotes"),
)

private fun MusicMeSongwritingState.toJson() = JSONObject()
    .put("lastPlan", lastPlan?.toJson() ?: JSONObject.NULL)
    .put("pendingJob", pendingJob?.let { job ->
        JSONObject().put("jobId", job.jobId ?: JSONObject.NULL).put("idempotencyKey", job.idempotencyKey)
            .put("targetSectionId", job.targetSectionId ?: JSONObject.NULL).put("submittedAt", job.submittedAt)
    } ?: JSONObject.NULL)
private fun JSONObject.toSongwriting() = MusicMeSongwritingState(
    lastPlan = optJSONObject("lastPlan")?.toSongwritingSummary(),
    pendingJob = optJSONObject("pendingJob")?.let { job ->
        MusicMePendingSongwritingJob(
            jobId = if (job.isNull("jobId")) null else job.getString("jobId"),
            idempotencyKey = job.getString("idempotencyKey"),
            targetSectionId = if (job.isNull("targetSectionId")) null else job.getString("targetSectionId"),
            submittedAt = job.getLong("submittedAt"),
        )
    },
)
private fun MusicMeSongwritingSummary.toJson() = JSONObject().put("contractVersion", contractVersion).put("title", title)
    .put("summary", summary).put("styleGuidance", styleGuidance).put("arrangementGuidance", arrangementGuidance)
    .put("tempoBpm", tempoBpm).put("estimatedDurationSeconds", estimatedDurationSeconds).put("vocalInstructions", vocalInstructions)
    .put("pronunciationNotes", pronunciationNotes).put("language", language).put("modelId", modelId).put("modelVersion", modelVersion)
    .put("engine", engine).put("requestId", requestId).put("generatedAt", generatedAt)
private fun JSONObject.toSongwritingSummary() = MusicMeSongwritingSummary(
    getString("contractVersion"), optString("title"), optString("summary"), optString("styleGuidance"), optString("arrangementGuidance"),
    optInt("tempoBpm"), optInt("estimatedDurationSeconds"), optString("vocalInstructions"), optString("pronunciationNotes"),
    optString("language"), optString("modelId"), optString("modelVersion"), optString("engine"), optString("requestId"), optLong("generatedAt"),
)
private fun JSONArray.strings() = List(length()) { getString(it) }
