package com.beatiq.music.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.beatiq.music.R
import com.beatiq.music.data.model.UserDownloadStatus
import com.beatiq.music.presentation.downloads.DownloadsViewModel
import com.beatiq.music.ui.components.BeatIQBackButton
import com.beatiq.music.ui.components.DownloadStatusRow
import com.beatiq.music.ui.components.EmptyHighlightCard
import com.beatiq.music.ui.components.PremiumScreenBackground

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(onBack: () -> Unit) {
    val vm: DownloadsViewModel = viewModel(factory = DownloadsViewModel.Factory)
    val downloads by vm.downloads.collectAsStateWithLifecycle()

    val active =
        downloads.filter {
            it.status == UserDownloadStatus.RUNNING || it.status == UserDownloadStatus.PENDING
        }
    val completed =
        downloads.filter {
            it.status == UserDownloadStatus.COMPLETED || it.status == UserDownloadStatus.FAILED
        }

    var selectedTab by remember { mutableIntStateOf(0) }

    PremiumScreenBackground {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, top = 8.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BeatIQBackButton(onBack = onBack)
                Text(
                    text = stringResource(R.string.screen_downloads),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(stringResource(R.string.downloads_tab_active)) },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(stringResource(R.string.downloads_tab_completed)) },
                )
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (selectedTab == 0) {
                    if (active.isEmpty()) {
                        item {
                            EmptyHighlightCard(
                                title = stringResource(R.string.downloads_empty_active_title),
                                body = stringResource(R.string.downloads_empty_active_body),
                            )
                        }
                    } else {
                        items(active, key = { it.id }) { row ->
                            DownloadStatusRow(
                                title = row.displayTitle,
                                subtitle = row.status.subtitleLabel(),
                                progress = if (row.status == UserDownloadStatus.RUNNING) null else null,
                            )
                        }
                    }
                } else {
                    if (completed.isEmpty()) {
                        item {
                            EmptyHighlightCard(
                                title = stringResource(R.string.downloads_empty_completed_title),
                                body = stringResource(R.string.downloads_empty_completed_body),
                                icon = Icons.Outlined.CloudDone,
                            )
                        }
                    } else {
                        items(completed, key = { it.id }) { row ->
                            DownloadStatusRow(
                                title = row.displayTitle,
                                subtitle = row.status.subtitleLabel(),
                                progress = if (row.status == UserDownloadStatus.COMPLETED) 1f else null,
                            )
                        }
                    }
                    item {
                        Text(
                            text = stringResource(R.string.downloads_empty_completed_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UserDownloadStatus.subtitleLabel(): String =
    when (this) {
        UserDownloadStatus.PENDING -> stringResource(R.string.downloads_status_pending)
        UserDownloadStatus.RUNNING -> stringResource(R.string.downloads_status_running)
        UserDownloadStatus.COMPLETED -> stringResource(R.string.downloads_status_completed)
        UserDownloadStatus.FAILED -> stringResource(R.string.downloads_status_failed)
    }
