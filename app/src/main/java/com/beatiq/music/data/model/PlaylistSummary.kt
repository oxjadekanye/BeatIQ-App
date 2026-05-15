package com.beatiq.music.data.model

data class PlaylistSummary(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val trackCount: Int,
)
