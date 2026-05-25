package com.beatiq.music.core.ui.premium

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * True when the composable is on screen and the app is in the foreground (STARTED+).
 * Use to pause infinite animations and save battery.
 */
@Composable
fun rememberAnimationsEnabled(): Boolean {
    val lifecycleOwner = LocalLifecycleOwner.current
    var enabled by remember(lifecycleOwner) {
        mutableStateOf(
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
        )
    }
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                enabled =
                    when (event) {
                        Lifecycle.Event.ON_START,
                        Lifecycle.Event.ON_RESUME,
                        -> true
                        Lifecycle.Event.ON_PAUSE,
                        Lifecycle.Event.ON_STOP,
                        -> false
                        else -> enabled
                    }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return enabled
}
