package com.beatiq.app.data.repository

import com.beatiq.app.data.model.UserDownload
import kotlinx.coroutines.flow.Flow

interface DownloadsRepository {
    fun observeDownloads(): Flow<List<UserDownload>>

    /**
     * Enqueues [url] via Android [android.app.DownloadManager] after user confirmation.
     * Only call for validated HTTPS direct file URLs.
     */
    suspend fun enqueueLegalFileDownload(url: String, displayTitle: String): Result<Long>

    /**
     * Called when [android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE] fires for [downloadManagerId].
     */
    suspend fun syncDownloadManagerResult(downloadManagerId: Long)
}
