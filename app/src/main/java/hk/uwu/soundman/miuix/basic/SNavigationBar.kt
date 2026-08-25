// Copyright 2025, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package hk.uwu.soundman.miuix.basic

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BadgedBox
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.Platform
import top.yukonga.miuix.kmp.utils.platform

/**
 * S version of [top.yukonga.miuix.kmp.basic.FloatingNavigationBar].
 *
 * Modification: removed captionBar animation height logic, replaced with
 * windowInsetsPadding for caption bar bottom.
 *
 * A floating navigation bar that supports 2 to 5 items.
 *
 * @param modifier A [Modifier] to be applied to the [SFloatingNavigationBar] for additional customization.
 * @param color The background color of the [SFloatingNavigationBar].
 * @param cornerRadius The corner radius of the [SFloatingNavigationBar], used for rounded corners.
 * @param horizontalAlignment The alignment of the [SFloatingNavigationBar] within its parent.
 * @param horizontalOutSidePadding The horizontal padding to be applied outside the [SFloatingNavigationBar].
 * @param shadowElevation The shadow elevation of the [SFloatingNavigationBar].
 * @param showDivider Whether to show the divider line around the [SFloatingNavigationBar].
 * @param defaultWindowInsetsPadding whether to apply default window insets padding to the [SFloatingNavigationBar].
 * @param content The content of the [SFloatingNavigationBar], usually [SFloatingNavigationBarItem]s.
 */
@Composable
fun SFloatingNavigationBar(
    modifier: Modifier = Modifier,
    color: Color = MiuixTheme.colorScheme.surfaceContainer,
    cornerRadius: Dp = SFloatingNavigationBarDefaults.CornerRadius,
    horizontalAlignment: Alignment.Horizontal = CenterHorizontally,
    horizontalOutSidePadding: Dp = SFloatingNavigationBarDefaults.HorizontalOutSidePadding,
    shadowElevation: Dp = SFloatingNavigationBarDefaults.ShadowElevation,
    showDivider: Boolean = false,
    defaultWindowInsetsPadding: Boolean = true,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)

    val navBarBottomPadding =
        WindowInsets.navigationBars.only(WindowInsetsSides.Bottom).asPaddingValues()
            .calculateBottomPadding()
    val bottomPaddingValue = when (platform()) {
        Platform.IOS -> 36.dp

        else -> {
            if (navBarBottomPadding != 0.dp) 26.dp + navBarBottomPadding else 36.dp
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = if (horizontalAlignment == Alignment.Start) horizontalOutSidePadding else 0.dp,
                end = if (horizontalAlignment == Alignment.End) horizontalOutSidePadding else 0.dp,
            ),
    ) {
        Row(
            modifier = Modifier
                .selectableGroup()
                .padding(bottom = bottomPaddingValue)
                .defaultMinSize(minHeight = 52.dp)
                .then(
                    if (defaultWindowInsetsPadding) {
                        Modifier
                            .then(
                                if (platform() != Platform.IOS) {
                                    Modifier
                                        .windowInsetsPadding(
                                            WindowInsets.statusBars.only(
                                                WindowInsetsSides.Bottom
                                            )
                                        )
                                } else {
                                    Modifier
                                },
                            )
                            // S modification: use windowInsetsPadding instead of animated captionBar height
                            .windowInsetsPadding(WindowInsets.captionBar.only(WindowInsetsSides.Bottom))
                    } else {
                        Modifier
                    },
                )
                .then(
                    if (showDivider) {
                        Modifier
                            .squircleBackground(
                                color = MiuixTheme.colorScheme.dividerLine,
                                cornerRadius = cornerRadius,
                            )
                            .padding(0.75.dp)
                    } else {
                        Modifier
                    },
                )
                .then(
                    if (shadowElevation > 0.dp) {
                        Modifier.dropShadow(
                            shape = shape,
                            shadow = Shadow(
                                radius = 10.dp,
                                color = Color.Black,
                                alpha = 0.2f,
                            ),
                        )
                    } else {
                        Modifier
                    },
                )
                .squircleBackground(color = color, cornerRadius = cornerRadius)
                .then(modifier)
                .padding(horizontal = SFloatingNavigationBarDefaults.HorizontalPadding)
                .align(horizontalAlignment)
                .pointerInput(Unit) {
                    detectTapGestures { /* Consume click */ }
                },
            horizontalArrangement = Arrangement.spacedBy(SFloatingNavigationBarDefaults.ItemSpacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            content()
        }
    }
}

/**
 * S version of [top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem].
 *
 * Note: badgeBounds() is internal in original miuix, so badge clamping within
 * item bounds is not applied. Badges will still display but may extend beyond
 * the item's bounds.
 *
 * @param selected Whether the item is selected.
 * @param onClick The callback when the item is clicked.
 * @param icon The icon of the item.
 * @param label The label of the item.
 * @param modifier The modifier to be applied to the [SFloatingNavigationBarItem].
 * @param enabled Whether the item is enabled.
 * @param badge The optional badge shown on the item's icon.
 */
@Composable
fun SFloatingNavigationBarItem(
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
            onSurfaceContainerColor.copy(alpha = SFloatingNavigationBarDefaults.SelectedPressedAlpha)
        } else {
            onSurfaceContainerColor.copy(alpha = SFloatingNavigationBarDefaults.UnselectedPressedAlpha)
        }

        selected -> onSurfaceContainerColor

        else -> onSurfaceContainerColor.copy(SFloatingNavigationBarDefaults.UnselectedAlpha)
    }

    Column(
        modifier = modifier
            .selectable(
                selected = selected,
                onClick = onClick,
                enabled = enabled,
                role = Role.Tab,
                interactionSource = interactionSource,
                indication = null,
            ),
        // TODO: Requires internal access to Modifier.badgeBounds() for badge clamping
        horizontalAlignment = CenterHorizontally,
    ) {
        SNavigationItemIcon(
            badge = badge,
            modifier = Modifier.padding(
                vertical = SFloatingNavigationBarDefaults.IconPadding,
                horizontal = SFloatingNavigationBarDefaults.IconPadding,
            ),
        ) { iconModifier ->
            Image(
                modifier = iconModifier.size(SFloatingNavigationBarDefaults.IconSize),
                imageVector = icon,
                contentDescription = label,
                colorFilter = ColorFilter.tint(tint),
            )
        }
    }
}

/**
 * S version of NavigationItemIcon (internal in original miuix).
 * Hosts the navigation item icon in a BadgedBox so badge anchors to the icon's top-end corner.
 */
@Composable
private fun SNavigationItemIcon(
    badge: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    BadgedBox(modifier = modifier, badge = { badge?.invoke() }) { content(Modifier) }
}

/** Contains default values used by [SFloatingNavigationBar] and [SFloatingNavigationBarItem]. */
object SFloatingNavigationBarDefaults {
    /** The default corner radius. */
    val CornerRadius = 50.dp

    /** The default horizontal outside padding. */
    val HorizontalOutSidePadding = 36.dp

    /** The default shadow elevation. */
    val ShadowElevation = 1.dp

    /** The default horizontal padding inside the bar. */
    val HorizontalPadding = 12.dp

    /** The default spacing between items. */
    val ItemSpacing = 12.dp

    /** The icon size for items. */
    val IconSize = 28.dp

    /** The padding around the icon. */
    val IconPadding = 10.dp

    /** The alpha value for the selected item when pressed. */
    val SelectedPressedAlpha = 0.5f

    /** The alpha value for an unselected item when pressed. */
    val UnselectedPressedAlpha = 0.6f

    /** The alpha value for an unselected item. */
    val UnselectedAlpha = 0.4f
}
