package com.beatiq.music.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Immutable
data class BottomNavEntry(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val selected: Boolean,
)

internal fun navTabTestTag(route: String) = "bottom_nav_$route"

internal fun navLabelTestTag(route: String) = "bottom_nav_label_$route"

internal const val NAV_CAPTION_TEST_TAG = "bottom_nav_selected_caption"

/** Labels may shrink to this share of the user's requested text size before the bar changes layout. */
internal const val NAV_LABEL_MIN_SCALE_RATIO = 0.8f

/** Absolute floor for any visible tab label, in dp. */
internal const val NAV_LABEL_MIN_PHYSICAL = 10f

private val NAV_LABEL_BASE_SIZE = 12.sp
private const val NAV_LABEL_STEP = 0.25f
private val BAR_HORIZONTAL_PADDING = 2.dp
private val CELL_HORIZONTAL_PADDING = 1.dp

/**
 * Six-destination bar that stays readable at every width and font size.
 *
 * Labelled layout: every tab shows its label on one line at one shared size that keeps at least
 * [NAV_LABEL_MIN_SCALE_RATIO] of the user's font scale. When the screen is too narrow for that (e.g.
 * 320dp at 1.15x, 360dp at 1.3x, any phone at 2x) the bar switches to an icon layout rather than
 * shrinking text further: six icons named for screen readers, with long-press tooltips, plus the
 * current destination's name at the user's full text size.
 */
@Composable
fun BeatIQBottomNavBar(
    entries: List<BottomNavEntry>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(NavigationBarDefaults.windowInsets),
        ) {
            val density = LocalDensity.current
            val labelStyle = MaterialTheme.typography.labelMedium.copy(
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.sp,
            )
            val measurer = rememberTextMeasurer()
            val cellWidthPx = with(density) {
                ((maxWidth - BAR_HORIZONTAL_PADDING * 2) / entries.size.coerceAtLeast(1) - CELL_HORIZONTAL_PADDING * 2).roundToPx()
            }
            val labels = entries.map { it.label }
            val labelSize = remember(labels, cellWidthPx, density, labelStyle) {
                val minSp = maxOf(
                    NAV_LABEL_BASE_SIZE.value * NAV_LABEL_MIN_SCALE_RATIO,
                    NAV_LABEL_MIN_PHYSICAL / density.fontScale,
                )
                val fitted = labels.map { label ->
                    fittingFontSize(label, labelStyle, cellWidthPx, minSp.sp) { text, style ->
                        measurer.measure(text, style, maxLines = 1, softWrap = false, density = density).size.width
                    }
                }
                if (fitted.isEmpty() || fitted.any { it == null }) null else fitted.minOf { it!!.value }.sp
            }
            if (labelSize != null) {
                LabelledBar(entries, labelStyle.copy(fontSize = labelSize, lineHeight = labelSize * 1.25f), onSelect)
            } else {
                IconBar(entries, onSelect)
            }
        }
    }
}

@Composable
private fun LabelledBar(entries: List<BottomNavEntry>, labelStyle: TextStyle, onSelect: (String) -> Unit) {
    TabRow {
        entries.forEach { entry ->
            val tint = tabTint(entry.selected)
            Column(
                Modifier
                    .weight(1f)
                    .heightIn(min = 64.dp)
                    .selectable(selected = entry.selected, onClick = { onSelect(entry.route) }, role = Role.Tab)
                    .testTag(navTabTestTag(entry.route))
                    .padding(horizontal = CELL_HORIZONTAL_PADDING, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                TabIcon(entry, tint, contentDescription = null)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = entry.label,
                    modifier = Modifier.fillMaxWidth().testTag(navLabelTestTag(entry.route)),
                    style = labelStyle.copy(
                        color = tint,
                        fontWeight = if (entry.selected) FontWeight.SemiBold else FontWeight.Medium,
                    ),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
private fun IconBar(entries: List<BottomNavEntry>, onSelect: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        TabRow {
            entries.forEach { entry ->
                // TooltipBox does not forward RowScope.weight, so the share is applied by this wrapper.
                Box(Modifier.weight(1f)) {
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = { PlainTooltip { Text(entry.label) } },
                        state = rememberTooltipState(),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .selectable(selected = entry.selected, onClick = { onSelect(entry.route) }, role = Role.Tab)
                                .testTag(navTabTestTag(entry.route)),
                            contentAlignment = Alignment.Center,
                        ) {
                            // In this layout the icon carries the destination name for screen readers.
                            TabIcon(entry, tabTint(entry.selected), contentDescription = entry.label)
                        }
                    }
                }
            }
        }
        entries.firstOrNull { it.selected }?.let { selected ->
            Text(
                text = selected.label,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, bottom = 6.dp)
                    // Visual caption only; the selected tab already announces its name.
                    .semantics { invisibleToUser() }
                    .testTag(NAV_CAPTION_TEST_TAG),
                style = MaterialTheme.typography.labelLarge.copy(textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TabRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = BAR_HORIZONTAL_PADDING)
            .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun tabTint(selected: Boolean): Color =
    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant

@Composable
private fun TabIcon(entry: BottomNavEntry, tint: Color, contentDescription: String?) {
    Box(
        Modifier
            .height(28.dp)
            .widthIn(max = 56.dp)
            .fillMaxWidth(0.86f)
            .clip(CircleShape)
            .background(if (entry.selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f) else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        Icon(entry.icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/**
 * Largest size from 12sp down to [minSize] at which [label] fits on one line in [availablePx],
 * or null when it does not fit even at [minSize].
 */
internal fun fittingFontSize(
    label: String,
    style: TextStyle,
    availablePx: Int,
    minSize: TextUnit,
    measureWidth: (String, TextStyle) -> Int,
): TextUnit? {
    var size = NAV_LABEL_BASE_SIZE.value
    while (size >= minSize.value) {
        if (measureWidth(label, style.copy(fontSize = size.sp)) <= availablePx) return size.sp
        size -= NAV_LABEL_STEP
    }
    return null
}
