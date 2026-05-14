package com.beatiq.app

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beatiq.app.R
import com.beatiq.app.core.auth.AuthOutcome
import com.beatiq.app.core.auth.AuthPreferences
import com.beatiq.app.core.auth.BeatIQAuthRepository
import com.beatiq.app.features.library.RepositoryProvider
import com.beatiq.app.navigation.BeatIQInnerRoutes
import com.beatiq.app.ui.theme.BeatIQAccent
import com.beatiq.app.ui.theme.BeatIQDeepViolet
import com.beatiq.app.ui.theme.BeatIQMidnight
import com.beatiq.app.ui.theme.BeatIQOnDarkMuted
import com.beatiq.app.ui.theme.BeatIQTheme
import com.beatiq.app.ui.theme.BeatIQWordmarkBrush
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun BeatIQLandingScreen(
    modifier: Modifier = Modifier,
    onRegister: () -> Unit = {},
    onSignedIn: (startTab: String) -> Unit = {},
) {
    val scroll = rememberScrollState()
    val context = LocalContext.current
    val app = context.applicationContext as android.app.Application
    val scope = rememberCoroutineScope()
    val authPrefs = remember { AuthPreferences(context) }

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun signInThenNavigate(startTab: String) {
        error = null
        if (email.isBlank() || password.isBlank()) {
            error = context.getString(R.string.auth_error_missing_credentials)
            Toast.makeText(context, error, Toast.LENGTH_LONG).show()
            return
        }
        val trimmedEmail = email.trim()
        if (!trimmedEmail.contains('@') || trimmedEmail.length < 5) {
            error = context.getString(R.string.auth_error_email_format)
            Toast.makeText(context, error, Toast.LENGTH_LONG).show()
            return
        }
        busy = true
        scope.launch {
            try {
                when (val out = BeatIQAuthRepository.login(trimmedEmail.lowercase(), password)) {
                    is AuthOutcome.Error -> {
                        val msg = out.message.ifBlank { context.getString(R.string.auth_error_generic) }
                        error = msg
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }
                    is AuthOutcome.Success -> {
                        val r = out.result
                        authPrefs.accessToken = r.accessToken
                        authPrefs.refreshToken = r.refreshToken
                        authPrefs.userId = r.userId
                        authPrefs.userEmail = trimmedEmail.lowercase()
                        runCatching {
                            withContext(Dispatchers.IO) {
                                RepositoryProvider.ensureForUser(app, r.userId)
                            }
                        }.onFailure { e ->
                            val msg =
                                e.message?.take(200)
                                    ?: context.getString(R.string.auth_error_library_init)
                            error = msg
                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            return@launch
                        }
                        onSignedIn(startTab)
                    }
                }
            } catch (e: Exception) {
                val msg = e.message?.take(200) ?: context.getString(R.string.auth_error_generic)
                error = msg
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            } finally {
                busy = false
            }
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors =
                        listOf(
                            BeatIQMidnight,
                            BeatIQDeepViolet,
                            BeatIQMidnight,
                        ),
                ),
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .padding(horizontal = 28.dp)
                    .padding(top = 48.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(R.drawable.beatiq_brand_logo),
                contentDescription = stringResource(R.string.app_name),
                modifier =
                    Modifier
                        .widthIn(max = 200.dp)
                        .height(100.dp)
                        .padding(bottom = 8.dp),
                contentScale = ContentScale.Fit,
            )
            Text(
                text = stringResource(R.string.app_name),
                style =
                    MaterialTheme.typography.displayMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 44.sp,
                        letterSpacing = (-1).sp,
                        brush = BeatIQWordmarkBrush,
                    ),
                color = Color.Unspecified,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.tagline),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.sp,
                ),
                color = BeatIQAccent,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.landing_welcome),
                style = MaterialTheme.typography.bodyLarge,
                color = BeatIQOnDarkMuted,
                textAlign = TextAlign.Center,
                lineHeight = 24.sp,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.auth_per_user_notice),
                style = MaterialTheme.typography.bodySmall,
                color = BeatIQOnDarkMuted.copy(alpha = 0.9f),
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(28.dp))
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.auth_email)) },
                supportingText = { Text(stringResource(R.string.auth_email_hint)) },
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.auth_password)) },
                visualTransformation =
                    if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                trailingIcon = {
                    IconButton(
                        onClick = { passwordVisible = !passwordVisible },
                        enabled = !busy,
                    ) {
                        Icon(
                            imageVector =
                                if (passwordVisible) {
                                    Icons.Outlined.VisibilityOff
                                } else {
                                    Icons.Outlined.Visibility
                                },
                            contentDescription =
                                stringResource(
                                    if (passwordVisible) {
                                        R.string.auth_hide_password
                                    } else {
                                        R.string.auth_show_password
                                    },
                                ),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
            error?.let { msg ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = msg,
                    color = Color(0xFFFFAB91),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = { signInThenNavigate(BeatIQInnerRoutes.HOME) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = BeatIQAccent,
                        contentColor = BeatIQMidnight,
                    ),
            ) {
                Text(
                    text = stringResource(R.string.auth_sign_in),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedButton(
                onClick = { signInThenNavigate(BeatIQInnerRoutes.DISCOVER) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                colors =
                    ButtonDefaults.outlinedButtonColors(
                        contentColor = Color.White,
                    ),
            ) {
                Text(
                    text = stringResource(R.string.landing_explore),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = onRegister,
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                colors =
                    ButtonDefaults.outlinedButtonColors(
                        contentColor = BeatIQAccent,
                    ),
            ) {
                Text(
                    text = stringResource(R.string.auth_create_account),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(modifier = Modifier.height(40.dp))
            Text(
                text = stringResource(R.string.landing_footer),
                style = MaterialTheme.typography.bodySmall,
                color = BeatIQOnDarkMuted.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp),
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
