package com.beatiq.music.musicme.audiogeneration

import android.content.Context
import com.beatiq.music.core.auth.AccountStorageKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** One finished song kept on this device. [saved] is false while the user is still deciding whether to keep it. */
data class MusicMeCreation(
    val track: AudioTrack,
    val generation: AudioGenerationInfo,
    val draftId: String,
    val title: String,
    val createdAt: Long,
    val saved: Boolean,
    val favorite: Boolean = false,
) {
    val id: String get() = track.id
}

/**
 * MusicMe song creations, separate from the BeatIQ Library and from MusicMe drafts: an index file plus the
 * audio files, all in filesDir/musicme_audio/<account key>/ (excluded from cloud backup and from the
 * account backup export, so the Phase 1.5 draft schema and backup format are unchanged).
 */
class MusicMeCreationStore(context: Context, accountId: String) {
    private val root = File(context.applicationContext.filesDir, "musicme_audio/${AccountStorageKey.fromUserId(accountId)}")
    private val index = File(root, INDEX)
    private val lock = Mutex()

    private data class Index(val consent: Boolean, val pending: PendingAudioJob?, val creations: List<MusicMeCreation>)

    suspend fun creations(): List<MusicMeCreation> = read().creations.sortedByDescending { it.createdAt }
    suspend fun hasConsent(): Boolean = read().consent
    suspend fun setConsent(granted: Boolean) = edit { it.copy(consent = granted) }
    suspend fun pending(): PendingAudioJob? = read().pending
    suspend fun setPending(job: PendingAudioJob?) = edit { it.copy(pending = job) }
    suspend fun add(creation: MusicMeCreation) = edit { current -> current.copy(creations = current.creations.filterNot { it.id == creation.id } + creation) }
    suspend fun markSaved(id: String) = edit { current -> current.copy(creations = current.creations.map { if (it.id == id) it.copy(saved = true) else it }) }
    suspend fun setFavorite(id: String, favorite: Boolean) = edit { current -> current.copy(creations = current.creations.map { if (it.id == id) it.copy(favorite = favorite) else it }) }
    suspend fun rename(id: String, title: String) = edit { current -> current.copy(creations = current.creations.map { if (it.id == id) it.copy(title = title) else it }) }
    suspend fun remove(id: String) = edit { current -> current.copy(creations = current.creations.filterNot { it.id == id }) }

    private suspend fun read(): Index = lock.withLock { load() }

    private suspend fun edit(transform: (Index) -> Index) = lock.withLock {
        val next = transform(load())
        withContext(Dispatchers.IO) {
            root.mkdirs()
            val temp = File(root, ".$INDEX.${UUID.randomUUID()}.tmp")
            temp.writeText(next.toJson().toString())
            if (!temp.renameTo(index)) {
                temp.delete()
                throw java.io.IOException("Couldn't save your song list")
            }
        }
    }

    private suspend fun load(): Index = withContext(Dispatchers.IO) {
        if (!index.isFile) return@withContext Index(false, null, emptyList())
        // An unreadable index is a hard error: never silently replace (and so lose) the user's song list.
        val json = runCatching { JSONObject(index.readText()) }.getOrElse { throw java.io.IOException("Your song list couldn't be read") }
        Index(
            consent = json.optBoolean("consent", false),
            pending = json.optJSONObject("pending")?.let {
                PendingAudioJob(it.getString("draftId"), it.getString("idempotencyKey"), it.optString("jobId").ifBlank { null }, it.optLong("createdAt"))
            },
            creations = json.optJSONArray("creations")?.let { array -> (0 until array.length()).mapNotNull { runCatching { array.getJSONObject(it).toCreation() }.getOrNull() } }.orEmpty(),
        )
    }

    private fun Index.toJson(): JSONObject = JSONObject()
        .put("version", 1)
        .put("consent", consent)
        .put("pending", pending?.let { JSONObject().put("draftId", it.draftId).put("idempotencyKey", it.idempotencyKey).put("jobId", it.jobId ?: "").put("createdAt", it.createdAt) } ?: JSONObject.NULL)
        .put("creations", JSONArray(creations.map { it.toJson() }))

    private fun MusicMeCreation.toJson(): JSONObject = JSONObject()
        .put("draftId", draftId).put("title", title).put("createdAt", createdAt).put("saved", saved).put("favorite", favorite)
        .put("track", JSONObject().put("id", track.id).put("take", track.take).put("format", track.format).put("contentType", track.contentType)
            .put("sizeBytes", track.sizeBytes).put("durationMs", track.durationMs).put("sha256", track.sha256).put("seed", track.seed))
        .put("generation", JSONObject().put("engine", generation.engine).put("modelId", generation.modelId).put("modelVersion", generation.modelVersion)
            .put("mock", generation.mock).put("realAudio", generation.realAudio).put("seed", generation.seed))

    private fun JSONObject.toCreation(): MusicMeCreation {
        val t = getJSONObject("track")
        val g = getJSONObject("generation")
        return MusicMeCreation(
            track = AudioTrack(t.getString("id"), t.getInt("take"), t.getString("format"), t.getString("contentType"), t.getLong("sizeBytes"),
                t.getLong("durationMs"), t.getString("sha256"), t.getLong("seed"), available = true, expiresAt = null),
            // Fail safe like the network contract: missing flags read as mock, never as real audio.
            generation = AudioGenerationInfo(g.getString("engine"), g.getString("modelId"), g.optString("modelVersion"), g.optBoolean("mock", true),
                g.optBoolean("realAudio", false), g.optLong("seed")),
            draftId = getString("draftId"),
            title = optString("title"),
            createdAt = getLong("createdAt"),
            saved = optBoolean("saved", false),
            favorite = optBoolean("favorite", false),
        )
    }

    companion object {
        const val INDEX = "creations.json"
    }
}
