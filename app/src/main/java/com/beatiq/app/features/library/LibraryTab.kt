package com.beatiq.app.features.library

import androidx.annotation.StringRes
import com.beatiq.app.R

internal enum class LibraryTab(@param:StringRes val labelRes: Int) {
    SONGS(R.string.library_tab_songs),
    ALBUMS(R.string.library_tab_albums),
    ARTISTS(R.string.library_tab_artists),
    PLAYLISTS(R.string.library_tab_playlists),
    FOLDERS(R.string.library_tab_folders),
    DOWNLOADS(R.string.library_tab_downloads),
}
