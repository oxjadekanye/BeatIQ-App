package com.beatiq.music.ui.screen

import android.app.DatePickerDialog
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.beatiq.music.R
import com.beatiq.music.core.auth.BeatIQAuthRepository
import com.beatiq.music.core.auth.RegisterOutcome
import com.beatiq.music.ui.components.BeatIQBackButton
import com.beatiq.music.ui.components.PremiumScreenBackground
import kotlinx.coroutines.launch
import java.text.DateFormatSymbols

@Composable
fun RegisterAccountScreen(
    onBack: () -> Unit,
    onRegistered: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var fullName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordConfirm by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var passwordConfirmVisible by remember { mutableStateOf(false) }
    var birthYear by remember { mutableIntStateOf(1995) }
    var birthMonth by remember { mutableIntStateOf(1) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val monthLabel =
        remember(birthYear, birthMonth) {
            val sym = DateFormatSymbols.getInstance().months
            "${sym.getOrNull(birthMonth - 1) ?: birthMonth} $birthYear"
        }

    PremiumScreenBackground {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BeatIQBackButton(onBack = onBack)
            Text(
                text = stringResource(R.string.auth_register_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(R.string.auth_register_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = fullName,
                onValueChange = { fullName = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.auth_full_name)) },
            )
            OutlinedButton(
                onClick = {
                    DatePickerDialog(
                        context,
                        { _, y, m, _ ->
                            birthYear = y
                            birthMonth = m + 1
                        },
                        birthYear,
                        birthMonth - 1,
                        1,
                    ).show()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.auth_birth_month_year_label, monthLabel))
            }
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.auth_email)) },
            )
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
            OutlinedTextField(
                value = passwordConfirm,
                onValueChange = { passwordConfirm = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.auth_password_confirm)) },
                visualTransformation =
                    if (passwordConfirmVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                trailingIcon = {
                    IconButton(
                        onClick = { passwordConfirmVisible = !passwordConfirmVisible },
                        enabled = !busy,
                    ) {
                        Icon(
                            imageVector =
                                if (passwordConfirmVisible) {
                                    Icons.Outlined.VisibilityOff
                                } else {
                                    Icons.Outlined.Visibility
                                },
                            contentDescription =
                                stringResource(
                                    if (passwordConfirmVisible) {
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
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    error = null
                    if (fullName.isBlank()) {
                        error = context.getString(R.string.auth_error_full_name)
                        return@Button
                    }
                    if (password.length < 6) {
                        error = context.getString(R.string.auth_error_password_short)
                        return@Button
                    }
                    if (password != passwordConfirm) {
                        error = context.getString(R.string.auth_error_password_mismatch)
                        return@Button
                    }
                    val trimmedEmail = email.trim()
                    if (trimmedEmail.isBlank() || !trimmedEmail.contains('@') || trimmedEmail.length < 5) {
                        error = context.getString(R.string.auth_error_email_format)
                        return@Button
                    }
                    busy = true
                    scope.launch {
                        when (
                            val r =
                                BeatIQAuthRepository.register(
                                    email = trimmedEmail,
                                    password = password,
                                    passwordConfirm = passwordConfirm,
                                    fullName = fullName,
                                    birthYear = birthYear,
                                    birthMonth = birthMonth,
                                )
                        ) {
                            is RegisterOutcome.Created -> {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.auth_register_done_hint),
                                    Toast.LENGTH_LONG,
                                ).show()
                                onRegistered()
                            }
                            is RegisterOutcome.Error -> error = r.message
                        }
                        busy = false
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.auth_create_account_submit))
            }
        }
    }
}
