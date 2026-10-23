package com.beatiq.music.musicme.audiogeneration

import android.content.Context
import com.beatiq.music.core.auth.AccountStorageKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Where finished songs live on the device: app-private storage, one folder per signed-in account
 * (filesDir/musicme_audio/<account key>/<track id>.<format>), excluded from cloud backup like voice notes.
 *
 * Saving to MusicMe creations = [download] into this folder and recording the track id on the draft.
 * Exporting = [exportTo] an OutputStream the user picked with the system file picker
 * (ACTION_CREATE_DOCUMENT), which needs no storage permission on any supported Android version.
 */
class AudioTrackFiles(
    context: Context,
    accountId: String,
    private val client: OkHttpClient = defaultClient,
) {
    private val root: File = File(context.applicationContext.filesDir, "musicme_audio/${AccountStorageKey.fromUserId(accountId)}")

    fun fileFor(track: AudioTrack): File {
        if (!TRACK_ID.matches(track.id) || track.format !in FORMATS) throw IOException("Unsafe track reference")
        val dir = root.also { it.mkdirs() }.canonicalFile
        val target = File(dir, "${track.id}.${track.format}").canonicalFile
        if (target.parentFile != dir) throw IOException("Unsafe track path")
        return target
    }

    fun isSaved(track: AudioTrack): Boolean = runCatching { fileFor(track).let { it.isFile && it.length() == track.sizeBytes } }.getOrDefault(false)

    /**
     * Streams a signed link into app storage. Rejects non-audio responses, unexpected sizes and checksum
     * mismatches; the file only appears (atomic rename) once every check passes.
     */
    suspend fun download(link: AudioDownloadLink, track: AudioTrack): File = withContext(Dispatchers.IO) {
        val target = fileFor(track)
        if (link.sizeBytes != track.sizeBytes || track.sizeBytes <= 0 || track.sizeBytes > MAX_BYTES) throw IOException("Unexpected song size")
        val temp = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.downloading")
        try {
            client.newCall(Request.Builder().url(link.url).get().build()).execute().use { response ->
                val type = response.body?.contentType()?.type.orEmpty()
                if (!response.isSuccessful || type != "audio") throw IOException("Download failed")
                val digest = MessageDigest.getInstance("SHA-256")
                var written = 0L
                response.body?.byteStream()?.use { input ->
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            written += read
                            if (written > track.sizeBytes) throw IOException("Song larger than expected")
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                        }
                    }
                } ?: throw IOException("Empty download")
                val hex = digest.digest().joinToString("") { "%02x".format(it) }
                if (written != track.sizeBytes || hex != track.sha256) throw IOException("Song failed its integrity check")
            }
            if (!temp.renameTo(target)) throw IOException("Couldn't save the song")
            target
        } finally {
            temp.delete()
        }
    }

    fun exportTo(track: AudioTrack, output: OutputStream) {
        fileFor(track).inputStream().use { it.copyTo(output) }
    }

    fun delete(track: AudioTrack): Boolean = runCatching { fileFor(track).delete() }.getOrDefault(false)

    companion object {
        private val TRACK_ID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        private val FORMATS = setOf("wav", "m4a", "mp3")
        private const val MAX_BYTES = 64L * 1024 * 1024

        private val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).followRedirects(false).build()
        }
    }
}
