package com.beatiq.app.ui.screen

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.beatiq.app.R
import com.beatiq.app.ui.components.BeatIQBackButton
import com.beatiq.app.ui.components.FeaturedMusicCard
import com.beatiq.app.ui.components.PremiumScreenBackground
import com.beatiq.app.ui.components.SectionHeader
import com.beatiq.app.ui.components.TrendingMusicCard
import com.beatiq.app.ui.data.MockCatalog
import java.time.LocalTime

@Composable
fun HomeScreen(onBack: () -> Unit) {
    val featured = remember { MockCatalog.featured }
    val trending = remember { MockCatalog.trending }
    val recent = remember { MockCatalog.recentSearches }
    val greeting = greetingLabel()

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
                }
            }
            item {
                Column(
                    Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = greeting,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.home_headline),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.home_sub),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                SectionHeader(
                    title = stringResource(R.string.home_section_featured),
                    actionLabel = stringResource(R.string.action_see_all),
                    onActionClick = { /* wire navigation later */ },
                )
            }
            item {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                ) {
                    items(featured, key = { it.id }) { item ->
                        FeaturedMusicCard(item = item)
                    }
                }
            }
            item {
                SectionHeader(
                    title = stringResource(R.string.home_section_trending),
                    actionLabel = stringResource(R.string.action_see_all),
                    onActionClick = { /* wire navigation later */ },
                )
            }
            item {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                ) {
                    items(trending, key = { it.id }) { item ->
                        TrendingMusicCard(item = item)
                    }
                }
            }
            item {
                SectionHeader(title = stringResource(R.string.home_section_recent))
            }
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    recent.forEach { label ->
                        SuggestionChip(
                            onClick = { /* no-op: search not wired */ },
                            label = {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                                labelColor = MaterialTheme.colorScheme.onSurface,
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun greetingLabel(): String {
    val hour = LocalTime.now().hour
    return when (hour) {
        in 5..11 -> stringResource(R.string.home_greet_morning)
        in 12..16 -> stringResource(R.string.home_greet_afternoon)
        in 17..21 -> stringResource(R.string.home_greet_evening)
        else -> stringResource(R.string.home_greet_night)
    }
}
