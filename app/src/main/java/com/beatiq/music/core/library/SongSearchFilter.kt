package com.beatiq.music.core.library

import com.beatiq.music.data.model.Song

/** Matches [query] against title, artist, album, and genre (case-insensitive). */
fun filterSongsByQuery(songs: List<Song>, query: String): List<Song> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return songs
  return songs.filter { song ->
        song.title.lowercase().contains(q) ||
            song.artist.lowercase().contains(q) ||
            song.album.lowercase().contains(q) ||
            song.genre.lowercase().contains(q)
    }
}
