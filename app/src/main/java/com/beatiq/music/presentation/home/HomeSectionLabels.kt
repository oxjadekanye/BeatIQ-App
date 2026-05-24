package com.beatiq.music.presentation.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.beatiq.music.R
import java.time.LocalTime

/** Smart Home section titles from genre, time of day, and taste context. */
object HomeSectionLabels {

    @Composable
    fun welcomeSubline(): String {
        val hour = LocalTime.now().hour
        return when (hour) {
            in 5..11 -> stringResource(R.string.home_welcome_subline_morning)
            in 12..16 -> stringResource(R.string.home_welcome_subline_afternoon)
            in 17..21 -> stringResource(R.string.home_welcome_subline_evening)
            else -> stringResource(R.string.home_welcome_subline_night)
        }
    }

    @Composable
    fun featured(genre: HomeGenreTab): String =
        stringResource(R.string.home_label_made_for_you, genre.label)

    @Composable
    fun grid(genre: HomeGenreTab): String =
        stringResource(R.string.home_label_more_picks, genre.label)

    @Composable
    fun trending(genre: HomeGenreTab): String =
        stringResource(R.string.home_label_trending, genre.label)

    @Composable
    fun albums(genre: HomeGenreTab): String =
        stringResource(R.string.home_label_albums_mood, genre.label)

    @Composable
    fun djMixes(genre: HomeGenreTab): String =
        stringResource(R.string.home_label_dj_mixes, genre.label)

    @Composable
    fun popularPlaylists(genre: HomeGenreTab): String =
        stringResource(R.string.home_label_popular_playlists, genre.label)
}
