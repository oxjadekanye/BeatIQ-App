package com.beatiq.app.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.beatiq.app.data.model.Song

/** When non-null, long-pressing a track row invokes this with the [Song]. */
val LocalSongLongPress = staticCompositionLocalOf<((Song) -> Unit)?> { null }
