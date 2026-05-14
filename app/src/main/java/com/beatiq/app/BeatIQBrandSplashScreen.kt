package com.beatiq.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.beatiq.app.core.auth.AuthPreferences
import com.beatiq.app.features.library.RepositoryProvider
import com.beatiq.app.ui.theme.BeatIQMidnight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun BeatIQBrandSplashScreen(
    onGoLanding: () -> Unit,
    onGoMainHome: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as android.app.Application

    LaunchedEffect(Unit) {
        delay(1200)
        val prefs = AuthPreferences(context)
        if (prefs.hasSession()) {
            val uid = prefs.userId
            if (uid == null) {
                onGoLanding()
                return@LaunchedEffect
            }
            withContext(Dispatchers.IO) {
                RepositoryProvider.ensureForUser(app, uid)
            }
            onGoMainHome()
        } else {
            onGoLanding()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(BeatIQMidnight),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.beatiq_brand_logo),
            contentDescription = stringResource(R.string.app_name),
            modifier =
                Modifier
                    .size(160.dp)
                    .padding(24.dp),
            contentScale = ContentScale.Fit,
        )
    }
}
