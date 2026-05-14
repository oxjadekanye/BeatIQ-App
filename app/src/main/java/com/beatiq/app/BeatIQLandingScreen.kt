package com.beatiq.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beatiq.app.ui.theme.BeatIQAccent
import com.beatiq.app.ui.theme.BeatIQDeepViolet
import com.beatiq.app.ui.theme.BeatIQMidnight
import com.beatiq.app.ui.theme.BeatIQOnDarkMuted
import com.beatiq.app.ui.theme.BeatIQTheme

@Composable
fun BeatIQLandingScreen(
    modifier: Modifier = Modifier,
    onGetStarted: () -> Unit = {},
    onExploreMusic: () -> Unit = {},
) {
    val scroll = rememberScrollState()
    Box(
        modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        BeatIQMidnight,
                        BeatIQDeepViolet,
                        BeatIQMidnight,
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = 28.dp)
                .padding(top = 56.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.displayMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 48.sp,
                    letterSpacing = (-1).sp,
                ),
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.tagline),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.sp,
                ),
                color = BeatIQAccent,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(32.dp))
            Text(
                text = stringResource(R.string.landing_welcome),
                style = MaterialTheme.typography.bodyLarge,
                color = BeatIQOnDarkMuted,
                textAlign = TextAlign.Center,
                lineHeight = 24.sp,
            )
            Spacer(modifier = Modifier.height(40.dp))
            Button(
                onClick = onGetStarted,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = BeatIQAccent,
                    contentColor = BeatIQMidnight,
                ),
            ) {
                Text(
                    text = stringResource(R.string.landing_get_started),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = onExploreMusic,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Color.White,
                ),
            ) {
                Text(
                    text = stringResource(R.string.landing_explore),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(modifier = Modifier.height(48.dp))
            Text(
                text = stringResource(R.string.landing_footer),
                style = MaterialTheme.typography.bodySmall,
                color = BeatIQOnDarkMuted.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 32.dp),
            )
        }
    }
}

@Preview(showBackground = true, heightDp = 800)
@Composable
private fun BeatIQLandingPreview() {
    BeatIQTheme(darkTheme = true, dynamicColor = false) {
        BeatIQLandingScreen()
    }
}
