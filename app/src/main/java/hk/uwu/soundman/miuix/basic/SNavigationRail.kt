// Copyright 2025, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package hk.uwu.soundman.miuix.basic

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.BadgedBox
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.VerticalDivider
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * A [SNavigationRail] that is suitable for wide screens.
 *
 * This is an external reimplementation of the modified NavigationRail from SSchedule-ref.
 * The expand/collapse mechanism has been removed, simplifying the component to a fixed-width rail.
 *
 * @param modifier The modifier to be applied to the [SNavigationRail].
 * @param header The header of the [SNavigationRail], usually a FloatingActionButton or a logo.
 * @param color The color of the [SNavigationRail].
 * @param showDivider Whether to show the divider line between the [SNavigationRail] and the content.
 * @param defaultWindowInsetsPadding whether to apply default window insets padding to the [SNavigationRail].
 * @param minWidth The minimum width of the [SNavigationRail].
 * @param mode The mode for displaying items in the [SNavigationRail].
 * @param content The content of the [SNavigationRail], usually [SNavigationRailItem]s.
 */
@Composable
fun SNavigationRail(
    modifier: Modifier = Modifier,
    header: @Composable (ColumnScope.() -> Unit)? = null,
    color: Color = MiuixTheme.colorScheme.surface,
    showDivider: Boolean = true,
    defaultWindowInsetsPadding: Boolean = true,
    minWidth: Dp = SNavigationRailDefaults.MinWidth,
    mode: SNavigationRailDisplayMode = SNavigationRailDisplayMode.IconAndText,
    content: @Composable ColumnScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxHeight()
            .then(
                if (defaultWindowInsetsPadding) {
                    Modifier
                        .windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Vertical))
                        .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Start))
                        .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Start))
                } else {
                    Modifier
                },
            )
            .background(color),
    ) {
        Column(
            modifier = Modifier
                .width(minWidth)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .selectableGroup()
                .padding(vertical = SNavigationRailDefaults.VerticalPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
        ) {
            if (header != null) {
                header()
                Spacer(modifier = Modifier.height(SNavigationRailDefaults.HeaderSpacing))
            }
            CompositionLocalProvider(LocalSNavigationRailDisplayMode provides mode) {
                content()
            }
        }
        if (showDivider) {
            VerticalDivider()
        }
    }
}

/**
 * A [SNavigationRailItem] that is suitable for [SNavigationRail].
 *
 * Note: The original miuix's NavigationItemIcon and badgeBounds are internal, so this
 * implementation uses BadgedBox directly instead.
 *
 * @param selected Whether the item is selected.
 * @param onClick The callback when the item is clicked.
 * @param icon The icon of the item.
 * @param label The label of the item.
 * @param modifier The modifier to be applied to the [SNavigationRailItem].
 * @param enabled Whether the item is enabled.
 * @param badge The optional badge shown on the item's icon.
 */
@Composable
fun SNavigationRailItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    badge: (@Composable () -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val onSurfaceContainerColor = MiuixTheme.colorScheme.onSurfaceContainer
    val tint = when {
        isPressed -> if (selected) {
            onSurfaceContainerColor.copy(alpha = SNavigationRailDefaults.SelectedPressedAlpha)
        } else {
            onSurfaceContainerColor.copy(alpha = SNavigationRailDefaults.UnselectedPressedAlpha)
        }

        selected -> onSurfaceContainerColor

        else -> onSurfaceContainerColor.copy(SNavigationRailDefaults.UnselectedAlpha)
    }
    val fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
    val mode = LocalSNavigationRailDisplayMode.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                onClick = onClick,
                enabled = enabled,
                role = Role.Tab,
                interactionSource = interactionSource,
                indication = null,
            )
            .padding(vertical = SNavigationRailDefaults.ItemVerticalPadding)
            .animateContentSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when (mode) {
            SNavigationRailDisplayMode.IconAndText -> {
                SNavigationItemIcon(badge = badge) { iconModifier ->
                    Image(
                        modifier = iconModifier.size(SNavigationRailDefaults.IconSize),
                        imageVector = icon,
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(tint),
                    )
                }
                Spacer(modifier = Modifier.height(SNavigationRailDefaults.IconTextSpacing))
                Text(
                    text = label,
                    color = tint,
                    textAlign = TextAlign.Center,
                    fontSize = SNavigationRailDefaults.LabelFontSize,
                    fontWeight = fontWeight,
                )
            }

            SNavigationRailDisplayMode.IconWithSelectedLabel -> {
                SNavigationItemIcon(badge = badge) { iconModifier ->
                    Image(
                        modifier = iconModifier.size(SNavigationRailDefaults.IconSize),
                        imageVector = icon,
                        contentDescription = if (selected) null else label,
                        colorFilter = ColorFilter.tint(tint),
                    )
                }
                if (selected) {
                    Spacer(modifier = Modifier.height(SNavigationRailDefaults.IconTextSpacing))
                    Text(
                        text = label,
                        color = tint,
                        textAlign = TextAlign.Center,
                        fontSize = SNavigationRailDefaults.LabelFontSize,
                        fontWeight = fontWeight,
                    )
                }
            }

            else -> {
                SNavigationItemIcon(badge = badge) { iconModifier ->
                    Image(
                        modifier = iconModifier.size(SNavigationRailDefaults.IconSize),
                        imageVector = icon,
                        contentDescription = label,
                        colorFilter = ColorFilter.tint(tint),
                    )
                }
            }
        }
    }
}

/**
 * Reimplementation of the original miuix internal NavigationItemIcon.
 * Uses BadgedBox to wrap the icon with an optional badge.
 */
@Composable
private fun SNavigationItemIcon(
    badge: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    BadgedBox(modifier = modifier, badge = { badge?.invoke() }) { content(Modifier) }
}

/** Contains default values used by [SNavigationRail] and [SNavigationRailItem]. */
object SNavigationRailDefaults {
    /** The default minimum width of the [SNavigationRail]. */
    val MinWidth = 80.dp

    /** The default width when the rail is expanded. */
    val ExpandedWidth = 200.dp

    /** The default vertical padding of the [SNavigationRail] content. */
    val VerticalPadding = 24.dp

    /** The default spacing after the header. */
    val HeaderSpacing = 24.dp

    /** The default icon size. */
    val IconSize = 28.dp

    /** The default spacing between icon and text. */
    val IconTextSpacing = 4.dp

    /** The default vertical padding for each item. */
    val ItemVerticalPadding = 12.dp

    /** The default label font size. */
    val LabelFontSize = 12.sp

    /** The alpha value for the selected item when pressed. */
    val SelectedPressedAlpha = 0.5f

    /** The alpha value for an unselected item when pressed. */
    val UnselectedPressedAlpha = 0.6f

    /** The alpha value for an unselected item. */
    val UnselectedAlpha = 0.4f
}

/**
 * State for [SNavigationRail].
 *
 * Note: The expand/collapse mechanism has been removed in this reimplementation.
 * This class is kept for API compatibility but the isExpanded property is deprecated.
 */
@Stable
class SNavigationRailState(initialExpanded: Boolean = true) {
    @Deprecated("Expand/collapse mechanism has been removed. This property has no effect.")
    var isExpanded: Boolean by androidx.compose.runtime.mutableStateOf(initialExpanded)
}

@Composable
fun rememberSNavigationRailState(initialExpanded: Boolean = true): SNavigationRailState {
    return remember { SNavigationRailState(initialExpanded) }
}

/**
 * Defines the display mode for items in a [SNavigationRail].
 *
 * This controls whether to show both icon and text, icon only,
 * or icon with text only when selected.
 */
enum class SNavigationRailDisplayMode {
    /** Show both icon and text. */
    IconAndText,

    /** Show icon only. */
    IconOnly,

    /** Show icon always, show text only when selected. */
    IconWithSelectedLabel,
}

/**
 * A composition local to control the display mode for items in a [SNavigationRail].
 */
val LocalSNavigationRailDisplayMode = compositionLocalOf { SNavigationRailDisplayMode.IconAndText }
