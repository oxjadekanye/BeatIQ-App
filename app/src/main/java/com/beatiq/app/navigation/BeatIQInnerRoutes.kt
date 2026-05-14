package com.beatiq.app.navigation

/**
 * Routes for the inner [androidx.navigation.compose.NavHost] inside the main shell
 * (home, discover, library, … plus stacked library/settings flows).
 */
object BeatIQInnerRoutes {
    const val HOME = "home"
    const val DISCOVER = "discover"
    const val LIBRARY = "library"
    const val DOWNLOADS = "downloads"
    const val PROFILE = "profile"
    const val ONLINE_BROWSER = "online_browser"
    const val IDENTIFY_MUSIC = "identify_music"

    const val ARTIST_TRACKS_PATTERN = "artist_tracks/{artistKey}"
    const val ALBUM_TRACKS_PATTERN = "album_tracks/{albumKey}"
    const val LIBRARY_ALL_SONGS = "library_all_songs"
    const val LIBRARY_GENRE_PATTERN = "library_genre_songs/{genreKey}"
    const val PLAYLIST_DETAIL_PATTERN = "playlist_detail/{playlistId}"
    const val PLAYLIST_PICK_PATTERN = "playlist_pick/{playlistId}"

    const val SETTINGS_NOTIFICATIONS = "settings_notifications"
    const val SETTINGS_PLAYBACK = "settings_playback"
    const val SETTINGS_STORAGE = "settings_storage"
    const val SETTINGS_PRIVACY = "settings_privacy"

    val bottomTabRoutes =
        setOf(HOME, DISCOVER, LIBRARY, DOWNLOADS, PROFILE)

    fun artistTracks(encodedArtistKey: String): String = "artist_tracks/$encodedArtistKey"

    fun albumTracks(encodedAlbumKey: String): String = "album_tracks/$encodedAlbumKey"

    fun libraryGenre(encodedGenreKey: String): String = "library_genre_songs/$encodedGenreKey"

    fun playlistDetail(playlistId: String): String = "playlist_detail/$playlistId"

    fun playlistPick(playlistId: String): String = "playlist_pick/$playlistId"
}

fun isLibraryStackRoute(route: String?): Boolean =
    route == BeatIQInnerRoutes.LIBRARY ||
        route?.startsWith("artist_tracks/") == true ||
        route?.startsWith("album_tracks/") == true ||
        route == BeatIQInnerRoutes.LIBRARY_ALL_SONGS ||
        route?.startsWith("library_genre_songs/") == true ||
        route?.startsWith("playlist_detail/") == true ||
        route?.startsWith("playlist_pick/") == true

fun isProfileStackRoute(route: String?): Boolean =
    route == BeatIQInnerRoutes.PROFILE ||
        route == BeatIQInnerRoutes.SETTINGS_NOTIFICATIONS ||
        route == BeatIQInnerRoutes.SETTINGS_PLAYBACK ||
        route == BeatIQInnerRoutes.SETTINGS_STORAGE ||
        route == BeatIQInnerRoutes.SETTINGS_PRIVACY

fun isDiscoverStackRoute(route: String?): Boolean =
    route == BeatIQInnerRoutes.DISCOVER ||
        route == BeatIQInnerRoutes.ONLINE_BROWSER ||
        route == BeatIQInnerRoutes.IDENTIFY_MUSIC
