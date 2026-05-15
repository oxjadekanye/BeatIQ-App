package com.beatiq.music.ui.screen

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.beatiq.music.R
import com.beatiq.music.core.auth.AuthPreferences
import com.beatiq.music.ui.components.BeatIQBackButton
import com.beatiq.music.ui.components.PremiumScreenBackground
import com.beatiq.music.ui.theme.BeatIQAccent
import com.beatiq.music.ui.theme.BeatIQAccentDim
import com.beatiq.music.ui.theme.BeatIQMidnight
import java.io.File

@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    onLogout: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenPlayback: () -> Unit,
    onOpenStorage: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenLegal: () -> Unit,
) {
    val context = LocalContext.current
    val authPrefs = remember { AuthPreferences(context) }
    val photoKey = authPrefs.userId.orEmpty().ifBlank { "anon" }
    val photoFile = remember(photoKey) { File(context.filesDir, "${photoKey}_profile_photo.jpg") }
    var photoVersion by remember { mutableIntStateOf(0) }
    var showLogoutConfirm by remember { mutableStateOf(false) }
    var showRemovePhotoConfirm by remember { mutableStateOf(false) }
    val hasPhoto = remember(photoVersion, photoFile) { photoFile.exists() && photoFile.length() > 0L }

    val avatarImageRequest =
        remember(photoFile, photoVersion, context) {
            ImageRequest.Builder(context)
                .data(photoFile)
                .memoryCacheKey("${photoFile.absolutePath}#v$photoVersion")
                .diskCacheKey("${photoFile.absolutePath}#v$photoVersion")
                .build()
        }

    val pickGallery =
        rememberLauncherForActivityResult(
            ActivityResultContracts.PickVisualMedia(),
        ) { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            runCatching {
                val input = context.contentResolver.openInputStream(uri) ?: return@rememberLauncherForActivityResult
                input.use { stream ->
                    if (photoFile.exists()) photoFile.delete()
                    photoFile.outputStream().use { out -> stream.copyTo(out) }
                }
                photoVersion++
            }
        }

    val takePicture =
        rememberLauncherForActivityResult(
            ActivityResultContracts.TakePicture(),
        ) { ok ->
            if (ok) photoVersion++
        }

    val cameraPermission =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            if (!granted) return@rememberLauncherForActivityResult
            runCatching {
                if (photoFile.exists()) photoFile.delete()
                photoFile.createNewFile()
                val uri =
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        photoFile,
                    )
                takePicture.launch(uri)
            }
        }

    val scroll = rememberScrollState()
    PremiumScreenBackground {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .padding(bottom = 24.dp),
            ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, top = 8.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BeatIQBackButton(onBack = onBack)
                Text(
                    text = stringResource(R.string.screen_profile),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(112.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(BeatIQAccent, BeatIQAccentDim),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (hasPhoto) {
                        AsyncImage(
                            model = avatarImageRequest,
                            contentDescription = stringResource(R.string.profile_avatar_content),
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Text(
                            text = "B",
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold,
                            color = BeatIQMidnight,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.profile_change_photo),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Button(
                        onClick = {
                            cameraPermission.launch(Manifest.permission.CAMERA)
                        },
                    ) {
                        Text(stringResource(R.string.profile_take_selfie))
                    }
                    Button(
                        onClick = {
                            pickGallery.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                    ) {
                        Text(stringResource(R.string.profile_choose_from_library))
                    }
                }
                if (hasPhoto) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { showRemovePhotoConfirm = true },
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.profile_remove_photo))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.profile_name),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = authPrefs.userEmail ?: stringResource(R.string.profile_handle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(20.dp))
            PremiumUpsellCard(
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(20.dp))
            SettingsCard(
                modifier = Modifier.padding(horizontal = 20.dp),
                onOpenNotifications = onOpenNotifications,
                onOpenPlayback = onOpenPlayback,
                onOpenStorage = onOpenStorage,
                onOpenPrivacy = onOpenPrivacy,
                onOpenLegal = onOpenLegal,
            )
            Spacer(Modifier.height(28.dp))
            OutlinedButton(
                onClick = { showLogoutConfirm = true },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                colors =
                    ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Logout,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.profile_sign_out))
                }
            }
            Spacer(Modifier.height(16.dp))
            }
            if (showLogoutConfirm) {
                AlertDialog(
                    onDismissRequest = { showLogoutConfirm = false },
                    title = { Text(stringResource(R.string.profile_sign_out_title)) },
                    text = { Text(stringResource(R.string.profile_sign_out_message)) },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showLogoutConfirm = false
                                onLogout()
                            },
                        ) {
                            Text(
                                stringResource(R.string.profile_sign_out_confirm),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showLogoutConfirm = false }) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    },
                )
            }
            if (showRemovePhotoConfirm) {
                AlertDialog(
                    onDismissRequest = { showRemovePhotoConfirm = false },
                    title = { Text(stringResource(R.string.profile_remove_photo_title)) },
                    text = { Text(stringResource(R.string.profile_remove_photo_message)) },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showRemovePhotoConfirm = false
                                runCatching {
                                    if (photoFile.exists()) photoFile.delete()
                                    photoVersion++
                                }
                            },
                        ) {
                            Text(
                                stringResource(R.string.profile_remove_photo_confirm),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showRemovePhotoConfirm = false }) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun PremiumUpsellCard(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        ),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
                Column(
                    modifier = Modifier.padding(start = 12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.profile_premium_coming_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.profile_premium_coming_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(
    modifier: Modifier = Modifier,
    onOpenNotifications: () -> Unit,
    onOpenPlayback: () -> Unit,
    onOpenStorage: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenLegal: () -> Unit,
) {
    val rows: List<Pair<Int, () -> Unit>> =
        listOf(
            R.string.profile_setting_notifications to onOpenNotifications,
            R.string.profile_setting_playback to onOpenPlayback,
            R.string.profile_setting_storage to onOpenStorage,
            R.string.profile_setting_privacy to onOpenPrivacy,
            R.string.profile_setting_legal to onOpenLegal,
        )
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
    ) {
        Column(Modifier.padding(vertical = 4.dp)) {
            Text(
                text = stringResource(R.string.profile_settings_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            rows.forEachIndexed { index, (labelRes, onClick) ->
                Surface(
                    onClick = onClick,
                    color = Color.Transparent,
                ) {
                    Column {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(labelRes),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (index < rows.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 12.dp),
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                            )
                        }
                    }
                }
            }
        }
    }
}
