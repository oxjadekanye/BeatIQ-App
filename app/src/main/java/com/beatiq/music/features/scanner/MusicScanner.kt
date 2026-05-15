package com.beatiq.music.features.scanner

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.beatiq.music.core.permissions.AudioReadPermission
import com.beatiq.music.data.model.Song
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Indexes on-device audio via MediaStore.
 *
 * TODO(AI): Enrich metadata with embeddings / mood classifiers once cloud opt-in exists.
 */
class MusicScanner {

    /**
     * Legacy mock hook retained for deterministic tests — returns an empty catalog in app source.
     *
     * TODO(test): Move deterministic fixtures into androidTest resources instead of APK assets.
     */
    fun scanMockSongs(): List<Song> = emptyList()

    suspend fun scanAudioLibrary(context: Context): List<Song> =
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            if (!AudioReadPermission.hasAudioReadAccess(appContext)) {
                return@withContext emptyList()
            }

            val resolver = appContext.contentResolver

            val collection =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                } else {
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                }

            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.MIME_TYPE,
                MediaStore.Audio.Media.ALBUM_ID,
            )

            val minDuration = MIN_DURATION_MS.toString()
            val selection = buildString {
                append("${MediaStore.Audio.Media.IS_MUSIC}=1")
                append(" AND ${MediaStore.Audio.Media.DURATION}>=?")
            }
            val selectionArgs = arrayOf(minDuration)
            val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"

            val results = LinkedHashMap<Long, Song>()

            resolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durationIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dateAddedIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val mimeIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                val albumIdIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIdx)
                    if (results.containsKey(id)) continue

                    val mime = cursor.getString(mimeIdx) ?: continue
                    if (!ALLOWED_MIME_TYPES.contains(mime.lowercase())) continue

                    val duration = cursor.getLong(durationIdx)
                    if (duration < MIN_DURATION_MS) continue

                    val title = cursor.getString(titleIdx).orEmpty().ifBlank { "Unknown title" }
                    val artist = cursor.getString(artistIdx).orEmpty().ifBlank { "Unknown artist" }
                    val album = cursor.getString(albumIdx).orEmpty().ifBlank { "Unknown album" }
                    val dateAddedSec = cursor.getLong(dateAddedIdx)
                    val albumId = cursor.getLong(albumIdIdx)

                    val trackUri = ContentUris.withAppendedId(collection, id).toString()
                    val artworkUri = if (albumId > 0) {
                        ContentUris.withAppendedId(
                            Uri.parse("content://media/external/audio/albumart"),
                            albumId,
                        ).toString()
                    } else {
                        null
                    }

                    val song = Song(
                        id = "media-$id",
                        title = title,
                        artist = artist,
                        album = album,
                        genre = "",
                        durationMs = duration,
                        filePath = trackUri,
                        artworkUri = artworkUri,
                        dateAdded = dateAddedSec * 1000L,
                        playCount = 0,
                        isFavorite = false,
                    )
                    results[id] = song
                }
            }

            results.values.toList()
        }

    /**
     * Picks up audio saved under the app's external Music directory (e.g. DownloadManager targets)
     * so completed downloads appear in the BeatIQ library even when they are not yet in MediaStore.
     */
    suspend fun scanAppDownloadsFolder(context: Context): List<Song> =
        withContext(Dispatchers.IO) {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: return@withContext emptyList()
            val files =
                dir.listFiles()?.filter { f ->
                    f.isFile && f.length() > 512L && AUDIO_EXTENSIONS.any { ext -> f.name.endsWith(ext, ignoreCase = true) }
                }.orEmpty()
            val out = ArrayList<Song>()
            for (file in files) {
                val retriever = MediaMetadataRetriever()
                try {
                    runCatching {
                        retriever.setDataSource(file.absolutePath)
                        val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        if (durMs < APP_DOWNLOAD_MIN_DURATION_MS) return@runCatching
                        val title =
                            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty()
                                .ifBlank { file.nameWithoutExtension }
                        val artist =
                            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty()
                                .ifBlank { "Unknown artist" }
                        val album =
                            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty()
                                .ifBlank { "Unknown album" }
                        val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE).orEmpty()
                        if (mime.isNotBlank() && !ALLOWED_MIME_TYPES.contains(mime.lowercase())) return@runCatching
                        val id = "file-${file.absolutePath.hashCode()}"
                        out.add(
                            Song(
                                id = id,
                                title = title,
                                artist = artist,
                                album = album,
                                genre = "",
                                durationMs = durMs,
                                filePath = file.absolutePath,
                                artworkUri = null,
                                dateAdded = file.lastModified(),
                                playCount = 0,
                                isFavorite = false,
                            ),
                        )
                    }
                } finally {
                    runCatching { retriever.release() }
                }
            }
            out
        }

    companion object {
        private const val APP_DOWNLOAD_MIN_DURATION_MS = 3_000L
        private val AUDIO_EXTENSIONS = listOf(".mp3", ".m4a", ".aac", ".flac", ".ogg", ".opus", ".wav", ".bin")

        private const val MIN_DURATION_MS = 30_000L
        private val ALLOWED_MIME_TYPES = setOf(
            "audio/mpeg",
            "audio/mp3",
            "audio/x-mpeg",
            "audio/wav",
            "audio/x-wav",
            "audio/flac",
            "audio/x-flac",
            "audio/aac",
            "audio/mp4",
            "audio/m4a",
            "audio/x-m4a",
            "audio/ogg",
            "application/ogg",
            "audio/opus",
        )
    }
}
