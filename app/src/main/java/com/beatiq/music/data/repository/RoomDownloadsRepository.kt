package com.beatiq.music.data.repository

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import com.beatiq.music.R
import com.beatiq.music.core.browser.DirectAudioDownloadDetector
import com.beatiq.music.core.database.UserDownloadDao
import com.beatiq.music.core.database.UserDownloadEntity
import com.beatiq.music.data.model.UserDownload
import com.beatiq.music.data.model.UserDownloadStatus
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class RoomDownloadsRepository(
    private val app: Application,
    private val dao: UserDownloadDao,
) : DownloadsRepository {

    private val downloadManager: DownloadManager
        get() = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    override fun observeDownloads(): Flow<List<UserDownload>> =
        dao.observeAll().map { list -> list.map { it.toUserDownload() } }

    override suspend fun enqueueLegalFileDownload(url: String, displayTitle: String): Result<Long> =
        withContext(Dispatchers.IO) {
            if (!DirectAudioDownloadDetector.looksLikeDirectAudioFileUrl(url)) {
                return@withContext Result.failure(
                    IllegalArgumentException("Not a validated HTTPS direct audio file URL"),
                )
            }
            val rowId = UUID.randomUUID().toString()
            val request =
                DownloadManager.Request(Uri.parse(url)).apply {
                    setAllowedOverMetered(true)
                    setAllowedOverRoaming(false)
                    setTitle(displayTitle)
                    setDescription(app.getString(R.string.downloads_manager_description))
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    val fileName = guessFileName(url, displayTitle)
                    setDestinationInExternalFilesDir(app, Environment.DIRECTORY_MUSIC, fileName)
                }
            val dmId =
                runCatching { downloadManager.enqueue(request) }.getOrElse {
                    return@withContext Result.failure(it)
                }
            dao.insert(
                UserDownloadEntity(
                    id = rowId,
                    sourceUrl = url,
                    displayTitle = displayTitle,
                    status = "RUNNING",
                    downloadManagerId = dmId,
                    createdAt = System.currentTimeMillis(),
                    completedAt = null,
                    localFilePath = null,
                ),
            )
            Result.success(dmId)
        }

    override suspend fun syncDownloadManagerResult(downloadManagerId: Long) {
        withContext(Dispatchers.IO) {
            val entity = dao.getByDownloadManagerId(downloadManagerId) ?: return@withContext
            val resolved = queryTerminalStateWithRetry(entity, downloadManagerId)
            val outputFile = expectedOutputFile(entity.sourceUrl, entity.displayTitle)
            val fileLooksDone = outputFile.exists() && outputFile.length() > 512L

            val finalStatus: String
            val localPath: String?
            when {
                resolved == DownloadManager.STATUS_SUCCESSFUL -> {
                    finalStatus = "COMPLETED"
                    localPath = readLocalUri(entity, downloadManagerId) ?: Uri.fromFile(outputFile).toString()
                }
                resolved == DownloadManager.STATUS_FAILED -> {
                    finalStatus = if (fileLooksDone) "COMPLETED" else "FAILED"
                    localPath =
                        if (fileLooksDone) {
                            Uri.fromFile(outputFile).toString()
                        } else {
                            entity.localFilePath
                        }
                }
                fileLooksDone -> {
                    // Broadcast can arrive before DM cursor shows SUCCESS on some devices/OEMs.
                    finalStatus = "COMPLETED"
                    localPath = Uri.fromFile(outputFile).toString()
                }
                resolved == DownloadManager.STATUS_RUNNING || resolved == DownloadManager.STATUS_PENDING -> {
                    // Still in progress — keep RUNNING unless file already finished writing.
                    finalStatus = if (fileLooksDone) "COMPLETED" else "RUNNING"
                    localPath = if (fileLooksDone) Uri.fromFile(outputFile).toString() else entity.localFilePath
                }
                else -> {
                    finalStatus = if (fileLooksDone) "COMPLETED" else entity.status
                    localPath = if (fileLooksDone) Uri.fromFile(outputFile).toString() else entity.localFilePath
                }
            }

            val now = System.currentTimeMillis()
            dao.update(
                entity.copy(
                    status = finalStatus,
                    completedAt =
                        if (finalStatus == "COMPLETED" || finalStatus == "FAILED") {
                            entity.completedAt ?: now
                        } else {
                            entity.completedAt
                        },
                    localFilePath = localPath ?: entity.localFilePath,
                ),
            )

            if (finalStatus == "COMPLETED" && outputFile.exists()) {
                MediaScannerConnection.scanFile(
                    app,
                    arrayOf(outputFile.absolutePath),
                    arrayOf<String?>(null),
                    null,
                )
            }
        }
    }

    private suspend fun queryTerminalStateWithRetry(
        entity: UserDownloadEntity,
        downloadManagerId: Long,
    ): Int {
        repeat(3) { attempt ->
            val status = queryDownloadStatus(downloadManagerId)
            if (status == DownloadManager.STATUS_SUCCESSFUL || status == DownloadManager.STATUS_FAILED) {
                return status
            }
            val file = expectedOutputFile(entity.sourceUrl, entity.displayTitle)
            if (file.exists() && file.length() > 512L) {
                return DownloadManager.STATUS_SUCCESSFUL
            }
            if (attempt < 2) delay(280L * (attempt + 1))
        }
        return queryDownloadStatus(downloadManagerId)
    }

    private fun queryDownloadStatus(downloadManagerId: Long): Int {
        val cursor =
            downloadManager.query(
                DownloadManager.Query().setFilterById(downloadManagerId),
            ) ?: return DownloadManager.STATUS_FAILED
        cursor.use { c ->
            if (!c.moveToFirst()) return DownloadManager.STATUS_FAILED
            val idx = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
            return if (idx >= 0) c.getInt(idx) else DownloadManager.STATUS_FAILED
        }
    }

    private fun readLocalUri(
        entity: UserDownloadEntity,
        downloadManagerId: Long,
    ): String? {
        val cursor =
            downloadManager.query(
                DownloadManager.Query().setFilterById(downloadManagerId),
            ) ?: return null
        cursor.use { c ->
            if (!c.moveToFirst()) return null
            val uriIdx = c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
            if (uriIdx >= 0) {
                val u = c.getString(uriIdx)
                if (!u.isNullOrBlank()) return u
            }
        }
        val f = expectedOutputFile(entity.sourceUrl, entity.displayTitle)
        return if (f.exists()) Uri.fromFile(f).toString() else null
    }

    private fun expectedOutputFile(url: String, displayTitle: String): File {
        val dir = app.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: app.filesDir
        return File(dir, guessFileName(url, displayTitle))
    }

    private fun guessFileName(url: String, title: String): String {
        val tail = url.substringBefore('?').substringAfterLast('/').trim()
        if (tail.isNotBlank() && tail.length < 180 && tail.contains('.')) return tail
        val safe = title.replace(Regex("[^a-zA-Z0-9._-]+"), "_").take(60).trim('_')
        return "${if (safe.isEmpty()) "beatiq_download" else safe}.bin"
    }
}

private fun UserDownloadEntity.toUserDownload(): UserDownload =
    UserDownload(
        id = id,
        sourceUrl = sourceUrl,
        displayTitle = displayTitle,
        status =
            when (status) {
                "PENDING" -> UserDownloadStatus.PENDING
                "RUNNING" -> UserDownloadStatus.RUNNING
                "COMPLETED" -> UserDownloadStatus.COMPLETED
                "FAILED" -> UserDownloadStatus.FAILED
                else -> UserDownloadStatus.PENDING
            },
        downloadManagerId = downloadManagerId,
        createdAt = createdAt,
        completedAt = completedAt,
        localFilePath = localFilePath,
    )
