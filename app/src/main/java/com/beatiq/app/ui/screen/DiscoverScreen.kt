package com.beatiq.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
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
import com.beatiq.app.R
import com.beatiq.app.ui.components.BeatIQBackButton
import com.beatiq.app.ui.components.DiscoverSearchBar
import com.beatiq.app.ui.components.GenreFilterChip
import com.beatiq.app.ui.components.PremiumScreenBackground
import com.beatiq.app.ui.components.SectionHeader
import com.beatiq.app.ui.components.TrendingMusicCard
import com.beatiq.app.ui.data.MockCatalog

@Composable
fun DiscoverScreen(onBack: () -> Unit) {
    val genres = remember { MockCatalog.genres }
    val playlists = remember { MockCatalog.trendingPlaylists }
    var selectedGenreIndex by remember { mutableIntStateOf(0) }

    PremiumScreenBackground {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
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
                        text = stringResource(R.string.screen_discover),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
            item {
                DiscoverSearchBar(placeholder = stringResource(R.string.discover_search_placeholder))
            }
            item {
                Text(
                    text = stringResource(R.string.discover_section_genres),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
            item {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                ) {
                    itemsIndexed(genres) { index, label ->
                        GenreFilterChip(
                            label = label,
                            selected = index == selectedGenreIndex,
                            onClick = { selectedGenreIndex = index },
                        )
                    }
                }
            }
            item {
                SectionHeader(
                    title = stringResource(R.string.discover_section_playlists),
                    actionLabel = stringResource(R.string.action_see_all),
                    onActionClick = { /* wire navigation later */ },
                )
            }
            item {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                ) {
                    itemsIndexed(playlists, key = { _, p -> p.id }) { _, item ->
                        TrendingMusicCard(item = item)
                    }
                }
            }
        }
    }
}
