// Copyright 2025, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package hk.uwu.soundman.miuix.basic

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Indication
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

/**
 * S version of [top.yukonga.miuix.kmp.basic.TabRow].
 *
 * Modification: fastRoundToInt → roundToInt (4 places).
 *
 * @param tabs The text to be displayed in the [STabRow].
 * @param selectedTabIndex The selected tab index of the [STabRow].
 * @param onTabSelected The callback when a tab is selected.
 * @param modifier The modifier to be applied to the [STabRow].
 * @param colors The colors of the [STabRow].
 * @param minWidth The minimum width of the tab in [STabRow].
 * @param maxWidth The maximum width of the tab in [STabRow].
 * @param height The height of the [STabRow].
 * @param cornerRadius The round corner radius of the tab in [STabRow].
 * @param itemSpacing The spacing between tabs in [STabRow].
 * @param contentAlignment The content alignment of the tab in [STabRow].
 * @param listState The [LazyListState] to be used for the [STabRow].
 * @param interactionSource The [MutableInteractionSource] to be used for the [STabRow].
 * @param indication The [Indication] to be used for the [STabRow].
 */
@Composable
fun STabRow(
    tabs: List<String>,
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    colors: STabRowColors = STabRowDefaults.tabRowColors(),
    minWidth: Dp = STabRowDefaults.TabRowMinWidth,
    maxWidth: Dp = STabRowDefaults.TabRowMaxWidth,
    height: Dp = STabRowDefaults.TabRowHeight,
    cornerRadius: Dp = STabRowDefaults.TabRowCornerRadius,
    itemSpacing: Dp = 9.dp,
    contentAlignment: Alignment = Alignment.Center,
    listState: LazyListState? = null,
    interactionSource: MutableInteractionSource? = null,
    indication: Indication? = null,
) {
    val currentOnTabSelected by rememberUpdatedState(onTabSelected)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .then(modifier)
            .height(height)
            .background(color = colors.backgroundColor(false)),
    ) {
        val config = rememberSTabRowConfig(
            tabs = tabs,
            minWidth = minWidth,
            maxWidth = maxWidth,
            cornerRadius = cornerRadius,
            spacing = itemSpacing,
            lazyRowAvailableWidth = this.maxWidth,
            listState = listState,
        )
        val density = LocalDensity.current
        val tabWidthPx = with(density) { config.tabWidth.toPx() }
        val spacingPx = with(density) { itemSpacing.toPx() }
        val availableWidth = this.maxWidth
        var lastSettledSelectedTabIndex by remember(config.listState) { mutableIntStateOf(-1) }

        LaunchedEffect(selectedTabIndex, availableWidth, config.listState, config.tabWidth) {
            val centerOffset = (availableWidth - config.tabWidth) / 2
            val offsetPx = with(density) { -centerOffset.toPx() }.roundToInt()
            if (lastSettledSelectedTabIndex < 0 || lastSettledSelectedTabIndex == selectedTabIndex) {
                config.listState.scrollToItem(selectedTabIndex, offsetPx)
            } else {
                config.listState.animateScrollToItem(selectedTabIndex, offsetPx)
            }
            lastSettledSelectedTabIndex = selectedTabIndex
        }

        val scrollOffset by remember {
            derivedStateOf {
                val state = config.listState
                val firstIndex = state.firstVisibleItemIndex
                val firstOffset = state.firstVisibleItemScrollOffset
                firstIndex * (tabWidthPx + spacingPx) + firstOffset
            }
        }

        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .offset {
                        IntOffset(
                            ((selectedTabIndex * (tabWidthPx + spacingPx)) - scrollOffset).roundToInt(),
                            0
                        )
                    }
                    .width(config.tabWidth)
                    .fillMaxHeight()
                    .squircleBackground(
                        color = colors.backgroundColor(true),
                        cornerRadius = config.cornerRadius
                    ),
            )
            LazyRow(
                state = config.listState,
                modifier = Modifier
                    .fillMaxSize()
                    .semantics {
                        collectionInfo = CollectionInfo(rowCount = 1, columnCount = tabs.size)
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(itemSpacing),
                overscrollEffect = null,
            ) {
                itemsIndexed(tabs) { index, tabText ->
                    STabItem(
                        text = tabText,
                        index = index,
                        isSelected = selectedTabIndex == index,
                        onClick = { currentOnTabSelected.invoke(index) },
                        cornerRadius = config.cornerRadius,
                        width = config.tabWidth,
                        color = colors.contentColor(selectedTabIndex == index),
                        contentAlignment = contentAlignment,
                        interactionSource = interactionSource,
                        indication = indication,
                    )
                }
            }
        }
    }
}

/**
 * S version of [top.yukonga.miuix.kmp.basic.TabRowWithContour].
 *
 * Modification: fastRoundToInt → roundToInt (4 places).
 */
@Composable
fun STabRowWithContour(
    tabs: List<String>,
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    colors: STabRowColors = STabRowDefaults.tabRowColors(),
    minWidth: Dp = STabRowDefaults.TabRowWithContourMinWidth,
    maxWidth: Dp = STabRowDefaults.TabRowWithContourMaxWidth,
    height: Dp = STabRowDefaults.TabRowWithContourHeight,
    cornerRadius: Dp = STabRowDefaults.TabRowWithContourCornerRadius,
    itemSpacing: Dp = 5.dp,
    contentAlignment: Alignment = Alignment.Center,
    listState: LazyListState? = null,
    interactionSource: MutableInteractionSource? = null,
    indication: Indication? = null,
) {
    val currentOnTabSelected by rememberUpdatedState(onTabSelected)
    val contourPadding = 5.dp
    val outerCornerRadius = cornerRadius + contourPadding

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .then(modifier)
            .height(height),
    ) {
        val lazyRowAvailableWidth = this.maxWidth - (contourPadding * 2)
        val config = rememberSTabRowConfig(
            tabs = tabs,
            minWidth = minWidth,
            maxWidth = maxWidth,
            cornerRadius = cornerRadius,
            spacing = itemSpacing,
            lazyRowAvailableWidth = lazyRowAvailableWidth,
            listState = listState,
        )
        val density = LocalDensity.current
        val tabWidthPx = with(density) { config.tabWidth.toPx() }
        val spacingPx = with(density) { itemSpacing.toPx() }
        val indicatorOffset = remember { Animatable(0f) }
        val availableWidth = this.maxWidth
        var lastSettledSelectedTabIndex by remember(config.listState) { mutableIntStateOf(-1) }

        LaunchedEffect(selectedTabIndex, tabWidthPx, spacingPx) {
            val target = selectedTabIndex * (tabWidthPx + spacingPx)
            if (lastSettledSelectedTabIndex < 0 || lastSettledSelectedTabIndex == selectedTabIndex) {
                indicatorOffset.snapTo(target)
            } else {
                indicatorOffset.animateTo(target, tween(200, easing = LinearEasing))
            }
        }

        LaunchedEffect(selectedTabIndex, availableWidth, config.listState, config.tabWidth) {
            val centerOffset = (availableWidth - (contourPadding * 2) - config.tabWidth) / 2
            val offsetPx = with(density) { -centerOffset.toPx() }.roundToInt()
            if (lastSettledSelectedTabIndex < 0 || lastSettledSelectedTabIndex == selectedTabIndex) {
                config.listState.scrollToItem(selectedTabIndex, offsetPx)
            } else {
                config.listState.animateScrollToItem(selectedTabIndex, offsetPx)
            }
            lastSettledSelectedTabIndex = selectedTabIndex
        }

        val scrollOffset by remember {
            derivedStateOf {
                val state = config.listState
                val firstIndex = state.firstVisibleItemIndex
                val firstOffset = state.firstVisibleItemScrollOffset
                firstIndex * (tabWidthPx + spacingPx) + firstOffset
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .squircleBackground(
                    color = colors.backgroundColor(false),
                    cornerRadius = outerCornerRadius
                )
                .padding(contourPadding),
        ) {
            Box(
                Modifier
                    .offset { IntOffset((indicatorOffset.value - scrollOffset).roundToInt(), 0) }
                    .width(config.tabWidth)
                    .fillMaxHeight()
                    .squircleBackground(
                        color = colors.backgroundColor(true),
                        cornerRadius = config.cornerRadius
                    ),
            )
            LazyRow(
                state = config.listState,
                modifier = Modifier
                    .fillMaxSize()
                    .semantics {
                        collectionInfo = CollectionInfo(rowCount = 1, columnCount = tabs.size)
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(itemSpacing),
                overscrollEffect = null,
            ) {
                itemsIndexed(tabs) { index, tabText ->
                    STabItemWithContour(
                        text = tabText,
                        index = index,
                        isSelected = selectedTabIndex == index,
                        onClick = { currentOnTabSelected.invoke(index) },
                        cornerRadius = config.cornerRadius,
                        width = config.tabWidth,
                        color = colors.contentColor(selectedTabIndex == index),
                        contentAlignment = contentAlignment,
                        interactionSource = interactionSource,
                        indication = indication,
                    )
                }
            }
        }
    }
}

@Composable
private fun STabItem(
    text: String,
    index: Int,
    isSelected: Boolean,
    onClick: () -> Unit,
    cornerRadius: Dp,
    width: Dp,
    color: Color = Color.Unspecified,
    contentAlignment: Alignment = Alignment.Center,
    interactionSource: MutableInteractionSource? = null,
    indication: Indication? = null,
) {
    val outlineColor = MiuixTheme.colorScheme.outline
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(width)
            .squircleBorder(
                width = { if (isSelected) 0.dp else 1.dp },
                color = { outlineColor },
                cornerRadius = cornerRadius,
            )
            .then(if (indication != null) Modifier.squircleClip(cornerRadius) else Modifier)
            .selectable(
                selected = isSelected,
                onClick = onClick,
                role = Role.Tab,
                interactionSource = interactionSource,
                indication = indication,
            )
            .semantics {
                collectionItemInfo = CollectionItemInfo(
                    rowIndex = 0,
                    rowSpan = 1,
                    columnIndex = index,
                    columnSpan = 1,
                )
            }
            .padding(horizontal = 12.dp),
        contentAlignment = contentAlignment,
    ) {
        Text(
            text = text,
            color = color,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            fontSize = MiuixTheme.textStyles.body1.fontSize,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun STabItemWithContour(
    text: String,
    index: Int,
    isSelected: Boolean,
    onClick: () -> Unit,
    cornerRadius: Dp,
    width: Dp,
    color: Color = Color.Unspecified,
    contentAlignment: Alignment = Alignment.Center,
    interactionSource: MutableInteractionSource? = null,
    indication: Indication? = null,
) {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(width)
            .then(if (indication != null) Modifier.squircleClip(cornerRadius) else Modifier)
            .selectable(
                selected = isSelected,
                onClick = onClick,
                role = Role.Tab,
                interactionSource = interactionSource,
                indication = indication,
            )
            .semantics {
                collectionItemInfo = CollectionItemInfo(
                    rowIndex = 0,
                    rowSpan = 1,
                    columnIndex = index,
                    columnSpan = 1,
                )
            },
        contentAlignment = contentAlignment,
    ) {
        Text(
            text = text,
            color = color,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            fontSize = MiuixTheme.textStyles.body2.fontSize,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Base configuration for STabRow implementations.
 */
private data class STabRowConfig(
    val tabWidth: Dp,
    val cornerRadius: Dp,
    val listState: LazyListState,
)

/**
 * Prepare common STabRow configuration.
 */
@Composable
private fun rememberSTabRowConfig(
    tabs: List<String>,
    minWidth: Dp,
    maxWidth: Dp,
    cornerRadius: Dp,
    spacing: Dp,
    lazyRowAvailableWidth: Dp,
    listState: LazyListState? = null,
): STabRowConfig {
    val resolvedListState = listState ?: rememberLazyListState()
    val tabWidth = remember(tabs.size, minWidth, maxWidth, lazyRowAvailableWidth, spacing) {
        calculateSTabWidth(tabs.size, minWidth, maxWidth, spacing, lazyRowAvailableWidth)
    }
    return STabRowConfig(tabWidth, cornerRadius, resolvedListState)
}

private fun calculateSTabWidth(
    tabCount: Int,
    minWidth: Dp,
    maxWidth: Dp,
    spacing: Dp,
    availableWidth: Dp,
): Dp {
    if (tabCount == 0) return minWidth

    val totalSpacing = if (tabCount > 1) (tabCount - 1) * spacing else 0.dp
    val contentWidth = availableWidth - totalSpacing

    return if (contentWidth <= 0.dp) {
        minWidth
    } else {
        val idealWidth = contentWidth / tabCount
        when {
            idealWidth < minWidth -> minWidth

            idealWidth > maxWidth -> {
                val totalMaxWidth = maxWidth * tabCount + totalSpacing
                if (totalMaxWidth < availableWidth) {
                    idealWidth
                } else {
                    maxWidth
                }
            }

            else -> idealWidth
        }
    }
}

object STabRowDefaults {

    val TabRowHeight = 42.dp
    val TabRowWithContourHeight = 45.dp
    val TabRowCornerRadius = 12.dp
    val TabRowWithContourCornerRadius = 8.dp
    val TabRowMinWidth = 76.dp
    val TabRowWithContourMinWidth = 62.dp
    val TabRowMaxWidth = 98.dp
    val TabRowWithContourMaxWidth = 84.dp

    @Composable
    fun tabRowColors(
        backgroundColor: Color = MiuixTheme.colorScheme.surface,
        contentColor: Color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        selectedBackgroundColor: Color = MiuixTheme.colorScheme.surfaceContainer,
        selectedContentColor: Color = MiuixTheme.colorScheme.onBackground,
    ): STabRowColors =
        remember(backgroundColor, contentColor, selectedBackgroundColor, selectedContentColor) {
            STabRowColors(
                backgroundColor = backgroundColor,
                contentColor = contentColor,
                selectedBackgroundColor = selectedBackgroundColor,
                selectedContentColor = selectedContentColor,
            )
        }
}

@Immutable
data class STabRowColors(
    private val backgroundColor: Color,
    private val contentColor: Color,
    private val selectedBackgroundColor: Color,
    private val selectedContentColor: Color,
) {
    @Stable
    fun backgroundColor(selected: Boolean): Color =
        if (selected) selectedBackgroundColor else backgroundColor

    @Stable
    fun contentColor(selected: Boolean): Color =
        if (selected) selectedContentColor else contentColor
}
