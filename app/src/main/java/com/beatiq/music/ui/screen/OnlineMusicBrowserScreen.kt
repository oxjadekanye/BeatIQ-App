package com.beatiq.music.ui.screen

import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.beatiq.music.R
import com.beatiq.music.core.browser.DirectAudioDownloadDetector
import com.beatiq.music.core.browser.HttpsUrlValidator
import com.beatiq.music.features.library.RepositoryProvider
import kotlinx.coroutines.launch

private object BrowserDestinations {
    const val GOOGLE = "https://www.google.com"
    const val FREE_MUSIC_ARCHIVE = "https://freemusicarchive.org"
    const val JAMENDO = "https://www.jamendo.com"
    const val INTERNET_ARCHIVE_AUDIO = "https://archive.org/details/audio"
    const val AUDIUS = "https://audius.co"
    const val SOUNDCLOUD = "https://soundcloud.com"
    const val ARTIST_SEARCH = "https://www.google.com/search?q=official+artist+music+website"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlineMusicBrowserScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var urlBarText by remember { mutableStateOf(BrowserDestinations.GOOGLE) }
    var pendingDownloadUrl by remember { mutableStateOf<String?>(null) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var browserHistoryCanGoBack by remember { mutableStateOf(false) }

    val quickLinks =
        remember {
            listOf(
                Triple(R.string.browser_link_google, BrowserDestinations.GOOGLE, BrowserDestinations.GOOGLE),
                Triple(R.string.browser_link_fma, BrowserDestinations.FREE_MUSIC_ARCHIVE, BrowserDestinations.FREE_MUSIC_ARCHIVE),
                Triple(R.string.browser_link_jamendo, BrowserDestinations.JAMENDO, BrowserDestinations.JAMENDO),
                Triple(R.string.browser_link_archive, BrowserDestinations.INTERNET_ARCHIVE_AUDIO, BrowserDestinations.INTERNET_ARCHIVE_AUDIO),
                Triple(R.string.browser_link_audius, BrowserDestinations.AUDIUS, BrowserDestinations.AUDIUS),
                Triple(R.string.browser_link_soundcloud, BrowserDestinations.SOUNDCLOUD, BrowserDestinations.SOUNDCLOUD),
                Triple(R.string.browser_link_artists, BrowserDestinations.ARTIST_SEARCH, BrowserDestinations.ARTIST_SEARCH),
            )
        }

    fun loadInWebView(raw: String) {
        val normalized = HttpsUrlValidator.normalizedHttpsUrl(raw) ?: return
        webViewRef?.loadUrl(normalized)
    }

    fun openInCustomTabs(url: String) {
        val normalized = HttpsUrlValidator.normalizedHttpsUrl(url) ?: return
        val schemeParams =
            CustomTabColorSchemeParams.Builder()
                .setToolbarColor(android.graphics.Color.parseColor("#1A1028"))
                .build()
        val tabsIntent =
            CustomTabsIntent.Builder()
                .setDefaultColorSchemeParams(schemeParams)
                .setShowTitle(true)
                .build()
        tabsIntent.launchUrl(context, Uri.parse(normalized))
    }

    pendingDownloadUrl?.let { url ->
        AlertDialog(
            onDismissRequest = { pendingDownloadUrl = null },
            title = { Text(stringResource(R.string.browser_download_dialog_title)) },
            text = {
                Text(
                    stringResource(R.string.browser_disclaimer),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val u = url
                        pendingDownloadUrl = null
                        scope.launch {
                            val title = Uri.parse(u).lastPathSegment?.take(80) ?: "download"
                            RepositoryProvider.downloadsRepository.enqueueLegalFileDownload(u, title)
                        }
                    },
                ) {
                    Text(stringResource(R.string.browser_download_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDownloadUrl = null }) {
                    Text(stringResource(R.string.browser_download_cancel))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.browser_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            val w = webViewRef
                            if (w != null && w.canGoBack()) {
                                w.goBack()
                                browserHistoryCanGoBack = w.canGoBack()
                            } else {
                                onBack()
                            }
                        },
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription =
                                stringResource(
                                    if (browserHistoryCanGoBack) {
                                        R.string.browser_cd_page_back
                                    } else {
                                        R.string.browser_cd_close_browser
                                    },
                                ),
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            val current = webViewRef?.url ?: urlBarText
                            openInCustomTabs(current)
                        },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Launch, contentDescription = stringResource(R.string.browser_open_external))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = urlBarText,
                    onValueChange = { urlBarText = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text(stringResource(R.string.browser_url_hint)) },
                )
                Button(
                    onClick = {
                        loadInWebView(urlBarText)
                    },
                ) {
                    Text(stringResource(R.string.browser_go))
                }
            }
            Text(
                text = stringResource(R.string.browser_quick_links),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                quickLinks.forEach { (labelRes, loadUrl, _) ->
                    FilterChip(
                        selected = false,
                        onClick = {
                            urlBarText = loadUrl
                            loadInWebView(loadUrl)
                        },
                        label = { Text(stringResource(labelRes)) },
                    )
                }
            }
            Text(
                text = stringResource(R.string.browser_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.mediaPlaybackRequiresUserGesture = true
                            webChromeClient = WebChromeClient()
                            webViewClient =
                                object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                    ): Boolean {
                                        val u = request?.url?.toString() ?: return false
                                        if (!HttpsUrlValidator.isAllowedHttpsUrl(u)) {
                                            return true
                                        }
                                        if (DirectAudioDownloadDetector.looksLikeDirectAudioFileUrl(u)) {
                                            pendingDownloadUrl = u
                                            return true
                                        }
                                        return false
                                    }

                                    override fun doUpdateVisitedHistory(
                                        view: WebView?,
                                        url: String?,
                                        isReload: Boolean,
                                    ) {
                                        browserHistoryCanGoBack = view?.canGoBack() == true
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        browserHistoryCanGoBack = view?.canGoBack() == true
                                    }
                                }
                            setDownloadListener { url, _, _, mimeType, _ ->
                                val u = url ?: return@setDownloadListener
                                if (!HttpsUrlValidator.isAllowedHttpsUrl(u)) return@setDownloadListener
                                val mimeAudio = mimeType?.startsWith("audio/", ignoreCase = true) == true
                                val looksAudio = DirectAudioDownloadDetector.looksLikeDirectAudioFileUrl(u)
                                val octetOk =
                                    mimeType.equals("application/octet-stream", ignoreCase = true) && looksAudio
                                if (mimeAudio || octetOk || looksAudio) {
                                    post { pendingDownloadUrl = u }
                                }
                            }
                            webViewRef = this
                            loadUrl(BrowserDestinations.GOOGLE)
                        }
                    },
                    onRelease = { view ->
                        (view as? WebView)?.destroy()
                        webViewRef = null
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
