package com.beatiq.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.beatiq.app.R
import com.beatiq.app.ui.components.BeatIQBackButton
import com.beatiq.app.ui.components.DownloadStatusRow
import com.beatiq.app.ui.components.EmptyHighlightCard
import com.beatiq.app.ui.components.PremiumScreenBackground
import com.beatiq.app.ui.components.SectionHeader
import com.beatiq.app.ui.data.MockCatalog

@Composable
fun DownloadsScreen(onBack: () -> Unit) {
    val active = remember { MockCatalog.activeDownloads }

    PremiumScreenBackground {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
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
            }
            item {
                SectionHeader(title = stringResource(R.string.downloads_section_active))
            }
            items(active, key = { it.id }) { row ->
                DownloadStatusRow(
                    title = row.title,
                    subtitle = row.subtitle,
                    progress = row.progress,
                )
            }
            item {
                Spacer(Modifier.height(12.dp))
                SectionHeader(title = stringResource(R.string.downloads_section_completed))
            }
            item {
                EmptyHighlightCard(
                    title = stringResource(R.string.downloads_empty_completed_title),
                    body = stringResource(R.string.downloads_empty_completed_body),
                    icon = Icons.Outlined.CloudDone,
                )
            }
        }
    }
}
