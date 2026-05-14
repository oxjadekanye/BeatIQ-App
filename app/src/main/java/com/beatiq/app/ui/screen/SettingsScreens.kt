package com.beatiq.app.ui.screen

import android.content.Intent
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.beatiq.app.R
import com.beatiq.app.core.prefs.BeatIQPreferences
import com.beatiq.app.ui.components.BeatIQBackButton
import com.beatiq.app.ui.components.PremiumScreenBackground

@Composable
fun SettingsNotificationsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    PremiumScreenBackground {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BeatIQBackButton(onBack = onBack)
                Text(
                    text = stringResource(R.string.profile_setting_notifications),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            Text(
                text = stringResource(R.string.settings_notifications_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp, bottom = 16.dp),
            )
            Button(
                onClick = {
                    val intent =
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        }
                    context.startActivity(intent)
                },
            ) {
                Text(stringResource(R.string.settings_open_notification_settings))
            }
        }
    }
}

@Composable
fun SettingsPlaybackScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { BeatIQPreferences(context) }
    var quality by remember { mutableStateOf(prefs.streamQuality) }
    var wifiOnly by remember { mutableStateOf(prefs.wifiOnlyDownloads) }
    var loudness by remember { mutableStateOf(prefs.loudnessNormalization) }
    var showLyrics by remember { mutableStateOf(prefs.showLyricsWhilePlaying) }

    PremiumScreenBackground {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BeatIQBackButton(onBack = onBack)
                Text(
                    text = stringResource(R.string.profile_setting_playback),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            Text(
                text = stringResource(R.string.settings_playback_quality_label),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = quality == BeatIQPreferences.VALUE_HIGH,
                    onClick = {
                        quality = BeatIQPreferences.VALUE_HIGH
                        prefs.streamQuality = quality
                    },
                    label = { Text(stringResource(R.string.settings_quality_high)) },
                )
                FilterChip(
                    selected = quality == BeatIQPreferences.VALUE_STANDARD,
                    onClick = {
                        quality = BeatIQPreferences.VALUE_STANDARD
                        prefs.streamQuality = quality
                    },
                    label = { Text(stringResource(R.string.settings_quality_standard)) },
                )
                FilterChip(
                    selected = quality == BeatIQPreferences.VALUE_DATA_SAVER,
                    onClick = {
                        quality = BeatIQPreferences.VALUE_DATA_SAVER
                        prefs.streamQuality = quality
                    },
                    label = { Text(stringResource(R.string.settings_quality_data_saver)) },
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.settings_wifi_only_downloads),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = wifiOnly,
                    onCheckedChange = {
                        wifiOnly = it
                        prefs.wifiOnlyDownloads = it
                    },
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.settings_loudness_normalization),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = loudness,
                    onCheckedChange = {
                        loudness = it
                        prefs.loudnessNormalization = it
                    },
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.settings_show_lyrics),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = showLyrics,
                    onCheckedChange = {
                        showLyrics = it
                        prefs.showLyricsWhilePlaying = it
                    },
                )
            }
            Text(
                text = stringResource(R.string.settings_equalizer_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = {
                    val intent =
                        Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
                            putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                            putExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0)
                        }
                    runCatching { context.startActivity(intent) }
                },
            ) {
                Text(stringResource(R.string.settings_open_system_equalizer))
            }
        }
    }
}

@Composable
fun SettingsStorageScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    PremiumScreenBackground {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BeatIQBackButton(onBack = onBack)
                Text(
                    text = stringResource(R.string.profile_setting_storage),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            Text(
                text = stringResource(R.string.settings_storage_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp, bottom = 16.dp),
            )
            Button(
                onClick = {
                    val intent =
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                    context.startActivity(intent)
                },
            ) {
                Text(stringResource(R.string.settings_open_app_storage))
            }
        }
    }
}

@Composable
fun SettingsPrivacyScreen(onBack: () -> Unit) {
    PremiumScreenBackground {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BeatIQBackButton(onBack = onBack)
                Text(
                    text = stringResource(R.string.profile_setting_privacy),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            Text(
                text = stringResource(R.string.settings_privacy_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}
