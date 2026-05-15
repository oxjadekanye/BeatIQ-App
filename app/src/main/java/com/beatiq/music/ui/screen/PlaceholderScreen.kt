package com.beatiq.music.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.beatiq.music.R
import com.beatiq.music.ui.theme.BeatIQAccent
import com.beatiq.music.ui.theme.BeatIQDeepViolet
import com.beatiq.music.ui.theme.BeatIQMidnight

/**
 * Shared layout for feature placeholders: BeatIQ dark gradient, top back control, centered title.
 */
@Composable
fun PlaceholderScreen(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        BeatIQMidnight,
                        BeatIQDeepViolet,
                        BeatIQMidnight,
                    ),
                ),
            ),
    ) {
        TextButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(horizontal = 4.dp, vertical = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.action_back),
                color = BeatIQAccent,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.Center)
                .padding(horizontal = 24.dp),
        )
    }
}
