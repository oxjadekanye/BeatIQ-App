package com.beatiq.app.ui.screen

import androidx.compose.runtime.Composable
import com.beatiq.app.features.library.BeatIQLibraryScreen

@Composable
fun LibraryScreen(onBack: () -> Unit) {
    BeatIQLibraryScreen(onBack = onBack)
}
