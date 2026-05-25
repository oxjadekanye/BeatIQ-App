package com.beatiq.music.presentation.home

import com.beatiq.music.data.model.OnlineRecommendation
import com.beatiq.music.data.model.Song

object HomeRecentMapper {
    fun fromSongs(songs: List<Song>): List<OnlineRecommendation> =
        songs
            .filter { it.recentlyPlayedAt != null }
            .sortedByDescending { it.recentlyPlayedAt }
            .map { song ->
                OnlineRecommendation(
                    id = "local_${song.id}",
                    title = song.title,
                    artist = song.artist,
                    album = song.album,
                    artworkUrl = song.artworkUri.orEmpty(),
                    genreHint = song.genre,
                    trackViewUrl = "",
                    previewUrl = null,
                    releaseDate = null,
                    recommendationReason = "Recently played on your device",
                )
            }
}
