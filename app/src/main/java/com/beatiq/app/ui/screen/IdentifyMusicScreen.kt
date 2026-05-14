package com.beatiq.app.ui.screen

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.beatiq.app.R
import com.beatiq.app.core.browser.DirectAudioDownloadDetector
import com.beatiq.app.core.identify.AuddIdentifyClient
import com.beatiq.app.core.identify.IdentifyMatch
import com.beatiq.app.features.library.RepositoryProvider
import com.beatiq.app.ui.components.BeatIQBackButton
import com.beatiq.app.ui.components.PremiumScreenBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun IdentifyMusicScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var recording by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var match by remember { mutableStateOf<IdentifyMatch?>(null) }
    val recordFile = remember { File(context.cacheDir, "beatiq_identify_sample.m4a") }

    fun beginCapture() {
        if (!AuddIdentifyClient.isConfigured()) {
            error = context.getString(R.string.identify_no_token)
            return
        }
        runCatching {
            if (recordFile.exists()) recordFile.delete()
            val mr =
                MediaRecorder().apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioEncodingBitRate(128_000)
                    setAudioSamplingRate(44_100)
                    setOutputFile(recordFile.absolutePath)
                    prepare()
                    start()
                }
            recording = true
            busy = true
            error = null
            match = null
            scope.launch {
                delay(7_200)
                withContext(Dispatchers.IO) {
                    runCatching {
                        mr.stop()
                        mr.release()
                    }
                }
                recording = false
                val result = withContext(Dispatchers.IO) { AuddIdentifyClient.identify(recordFile) }
                result.onSuccess { match = it }.onFailure { e -> error = e.message ?: "Error" }
                busy = false
            }
        }.onFailure { e ->
            error = e.message
            recording = false
            busy = false
        }
    }

    val micPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                beginCapture()
            } else {
                error = context.getString(R.string.identify_mic_denied)
            }
        }

    fun onListenClick() {
        val ok =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        if (ok) {
            beginCapture()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    PremiumScreenBackground {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, top = 8.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BeatIQBackButton(onBack = onBack)
                Text(
                    text = stringResource(R.string.identify_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            Text(
                text = stringResource(R.string.identify_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Button(
                onClick = { onListenClick() },
                enabled = !busy,
                modifier = Modifier.padding(horizontal = 20.dp),
            ) {
                Text(
                    if (recording) {
                        stringResource(R.string.identify_listening)
                    } else {
                        stringResource(R.string.identify_listen_cta)
                    },
                )
            }
            if (busy) {
                CircularProgressIndicator(Modifier.padding(horizontal = 20.dp))
            }
            error?.let { msg ->
                Text(
                    text = msg,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            match?.let { m ->
                Column(Modifier.padding(horizontal = 20.dp)) {
                    AsyncImage(
                        model = m.artworkUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .size(200.dp)
                            .padding(vertical = 8.dp),
                        contentScale = ContentScale.Fit,
                    )
                    Text(m.title, style = MaterialTheme.typography.headlineSmall)
                    Text(m.artist, style = MaterialTheme.typography.titleMedium)
                    m.album?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    val preview = m.previewDownloadUrl
                    if (preview != null && DirectAudioDownloadDetector.looksLikeDirectAudioFileUrl(preview)) {
                        Button(
                            onClick = {
                                scope.launch {
                                    val title = "${m.artist} - ${m.title}".take(80)
                                    RepositoryProvider.downloadsRepository.enqueueLegalFileDownload(preview, title)
                                    RepositoryProvider.scannerRepository.scanLibrary()
                                }
                            },
                        ) {
                            Text(stringResource(R.string.identify_download_preview))
                        }
                    }
                }
            }
        }
    }
}
