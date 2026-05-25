package com.beatiq.music.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.beatiq.music.core.ui.premium.PremiumMusicCard
import com.beatiq.music.features.library.RepositoryProvider
import com.beatiq.music.core.ui.premium.RecommendationReasonText
import com.beatiq.music.data.model.OnlineRecommendation
import com.beatiq.music.features.ai_dj.AIDJCommandParser
import com.beatiq.music.features.ai_dj.AIDJResponseBuilder
import com.beatiq.music.ui.components.BeatIQBackButton
import com.beatiq.music.ui.components.OnlineTrackDetailSheet
import com.beatiq.music.ui.components.PremiumScreenBackground
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AIDJScreen(
    onBack: () -> Unit,
    onOpenInternalBrowser: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val library by RepositoryProvider.musicRepository
        .observeAllSongs()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var input by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("BeatIQ Smart DJ — ask for a vibe.") }
    var queue by remember { mutableStateOf<List<OnlineRecommendation>>(emptyList()) }
    var selectedTrack by remember { mutableStateOf<OnlineRecommendation?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    selectedTrack?.let { track ->
        ModalBottomSheet(onDismissRequest = { selectedTrack = null }, sheetState = sheetState) {
            OnlineTrackDetailSheet(
                track = track,
                onDismiss = { selectedTrack = null },
                onOpenInternalBrowser = onOpenInternalBrowser,
            )
        }
    }

    PremiumScreenBackground {
        Column(Modifier.fillMaxSize()) {
            BeatIQBackButton(onBack = onBack, modifier = Modifier.padding(8.dp))
            Text(
                "BeatIQ Smart DJ",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Text(
                "Local rules power this assistant — no cloud AI required.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("Try: Play trending Afrobeats") },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
            )
            Button(
                onClick = {
                    scope.launch {
                        loading = true
                        val cmd = AIDJCommandParser.parse(input)
                        val response =
                            AIDJResponseBuilder.respond(
                                context,
                                cmd,
                                library = library,
                            )
                        message = response.message
                        queue = response.queue
                        loading = false
                    }
                },
                modifier = Modifier.padding(horizontal = 20.dp),
                enabled = !loading,
            ) {
                Text("Ask Smart DJ")
            }
            if (loading) {
                CircularProgressIndicator(Modifier.padding(20.dp))
            }
            Text(message, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            LazyColumn(
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(queue, key = { it.id }) { item ->
                    Column {
                        PremiumMusicCard(
                            item = item,
                            onClick = {
                                if (item.opensInInternalBrowser) {
                                    onOpenInternalBrowser(item.browserUrl)
                                } else {
                                    selectedTrack = item
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        RecommendationReasonText(
                            item.recommendationReason,
                            Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}
