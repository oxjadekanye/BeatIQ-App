package com.beatiq.music.data.model

data class UserDownload(
    val id: String,
    val sourceUrl: String,
    val displayTitle: String,
    val status: UserDownloadStatus,
    val downloadManagerId: Long?,
    val createdAt: Long,
    val completedAt: Long?,
    val localFilePath: String?,
)

enum class UserDownloadStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
}
