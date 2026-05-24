package com.beatiq.music.data.model

/** Online track suggestion surfaced on Home (metadata + artwork from public search APIs). */
data class OnlineRecommendation(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val artworkUrl: String,
    val genreHint: String,
)
