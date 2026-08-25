// Copyright 2025, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package hk.uwu.soundman.miuix.basic

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.util.fastFirst
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.anim.folmeSpring
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBarDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * S version of [top.yukonga.miuix.kmp.basic.TopAppBar].
 *
 * Modification: fastRoundToInt → roundToInt (3 places) + NaN checks.
 *
 * A [STopAppBar] with Miuix style that can collapse and expand based on the
 * scroll position of the content below it.
 *
 * @param title The title of the [STopAppBar].
 * @param modifier The modifier to be applied to the [STopAppBar].
 * @param color The background color of the [STopAppBar].
 * @param titleColor The color of the collapsed small title text.
 * @param largeTitle The large title of the [STopAppBar].
 * @param largeTitleColor The color of the expanded large title text.
 * @param subtitle The subtitle displayed below the title bar area.
 * @param subtitleColor The color of the subtitle text.
 * @param navigationIcon The [Composable] content that represents the navigation icon.
 * @param actions The [Composable] content that represents the action icons.
 * @param scrollBehavior The [ScrollBehavior] that controls the behavior of the [STopAppBar].
 * @param defaultWindowInsetsPadding Whether to apply default window insets padding to the [STopAppBar].
 * @param titlePadding The horizontal padding of the [STopAppBar]'s title & large title.
 * @param navigationIconPadding The start padding of the navigation icon.
 * @param actionIconPadding The end padding of the action icons.
 * @param bottomContent The [Composable] content displayed below the title bar area.
 */
@Composable
fun STopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    color: Color = MiuixTheme.colorScheme.surface,
    titleColor: Color = MiuixTheme.colorScheme.onSurface,
    largeTitle: String = title,
    largeTitleColor: Color = MiuixTheme.colorScheme.onSurface,
    subtitle: String = "",
    subtitleColor: Color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: ScrollBehavior? = null,
    defaultWindowInsetsPadding: Boolean = true,
    titlePadding: Dp = TopAppBarDefaults.TitlePadding,
    navigationIconPadding: Dp = TopAppBarDefaults.NavigationIconPadding,
    actionIconPadding: Dp = TopAppBarDefaults.ActionIconPadding,
    bottomContent: @Composable () -> Unit = {},
) {
    val actionsRow =
        @Composable {
            Row(
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
        }

    STopAppBarLayout(
        title = title,
        color = color,
        titleColor = titleColor,
        largeTitle = largeTitle,
        largeTitleColor = largeTitleColor,
        subtitle = subtitle,
        subtitleColor = subtitleColor,
        navigationIcon = navigationIcon,
        actions = actionsRow,
        titlePadding = titlePadding,
        navigationIconPadding = navigationIconPadding,
        actionIconPadding = actionIconPadding,
        scrollBehavior = scrollBehavior,
        modifier = modifier,
        defaultWindowInsetsPadding = defaultWindowInsetsPadding,
        bottomContent = bottomContent,
    )
}

/**
 * S version of [top.yukonga.miuix.kmp.basic.SmallTopAppBar].
 *
 * Modification: fastRoundToInt → roundToInt (3 places) + NaN checks.
 */
@Composable
fun SSmallTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    color: Color = MiuixTheme.colorScheme.surface,
    titleColor: Color = MiuixTheme.colorScheme.onSurface,
    subtitle: String = "",
    subtitleColor: Color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: ScrollBehavior? = null,
    defaultWindowInsetsPadding: Boolean = true,
    titlePadding: Dp = TopAppBarDefaults.TitlePadding,
    navigationIconPadding: Dp = TopAppBarDefaults.NavigationIconPadding,
    actionIconPadding: Dp = TopAppBarDefaults.ActionIconPadding,
    bottomContent: @Composable () -> Unit = {},
) {
    SideEffect {
        scrollBehavior?.state?.let { state ->
            if (state.heightOffsetLimit != 0f) state.heightOffsetLimit = 0f
        }
    }

    val actionsRow =
        @Composable {
            Row(
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
        }

    SSmallTopAppBarLayout(
        title = title,
        color = color,
        titleColor = titleColor,
        subtitle = subtitle,
        subtitleColor = subtitleColor,
        navigationIcon = navigationIcon,
        actions = actionsRow,
        titlePadding = titlePadding,
        navigationIconPadding = navigationIconPadding,
        actionIconPadding = actionIconPadding,
        modifier = modifier,
        defaultWindowInsetsPadding = defaultWindowInsetsPadding,
        bottomContent = bottomContent,
    )
}

/**
 * S version of TopAppBarLayout (private in original miuix).
 *
 * Modification: fastRoundToInt → roundToInt + NaN checks.
 */
@Composable
private fun STopAppBarLayout(
    title: String,
    color: Color,
    titleColor: Color,
    largeTitleColor: Color,
    subtitle: String,
    subtitleColor: Color,
    navigationIcon: @Composable () -> Unit,
    actions: @Composable () -> Unit,
    titlePadding: Dp,
    navigationIconPadding: Dp,
    actionIconPadding: Dp,
    scrollBehavior: ScrollBehavior?,
    modifier: Modifier = Modifier,
    largeTitle: String = title,
    defaultWindowInsetsPadding: Boolean = true,
    bottomContent: @Composable () -> Unit = {},
) {
    val scrolledOffset = remember(scrollBehavior) {
        { scrollBehavior?.state?.heightOffset ?: 0f }
    }
    val largeTitleAlpha = remember(scrollBehavior) {
        {
            val frac = scrollBehavior?.state?.collapsedFraction ?: 0f
            1f - (frac * 3f).coerceIn(0f, 1f)
        }
    }
    val updateHeightOffsetLimit = remember(scrollBehavior) {
        { height: Int ->
            scrollBehavior?.state?.let { state ->
                val limit = -height.toFloat()
                if (state.heightOffsetLimit != limit) state.heightOffsetLimit = limit
            }
            Unit
        }
    }

    val smallTitleVisible by remember(scrollBehavior) {
        derivedStateOf {
            (scrollBehavior?.state?.collapsedFraction ?: 0f) * 3f >= 1f
        }
    }
    val smallTitleAlpha = remember { Animatable(if (smallTitleVisible) 1f else 0f) }
    val smallTitleTranslationY = remember { Animatable(if (smallTitleVisible) 0f else 20f) }

    LaunchedEffect(smallTitleVisible) {
        if (smallTitleVisible) {
            val showSpec = folmeSpring<Float>(damping = 1.0f, response = 0.3f)
            launch { smallTitleAlpha.animateTo(1f, showSpec) }
            launch { smallTitleTranslationY.animateTo(0f, showSpec) }
        } else {
            val hideSpec = folmeSpring<Float>(damping = 1.0f, response = 0.15f)
            launch { smallTitleAlpha.animateTo(0f, hideSpec) }
            launch { smallTitleTranslationY.animateTo(20f, hideSpec) }
        }
    }

    val animatedTitleColor by animateColorAsState(
        targetValue = titleColor,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 50),
    )
    val animatedLargeTitleColor by animateColorAsState(
        targetValue = largeTitleColor,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 50),
    )
    val animatedSubtitleColor by animateColorAsState(
        targetValue = subtitleColor,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 50),
    )

    Layout(
        {
            Box(
                Modifier
                    .layoutId("navigationIcon")
                    .padding(start = navigationIconPadding),
            ) {
                navigationIcon()
            }
            Box(
                Modifier
                    .layoutId("title")
                    .padding(horizontal = titlePadding)
                    .graphicsLayer {
                        alpha = smallTitleAlpha.value
                        translationY = smallTitleTranslationY.value
                    },
            ) {
                Text(
                    text = title,
                    color = animatedTitleColor,
                    fontSize = MiuixTheme.textStyles.title3.fontSize,
                    fontWeight = FontWeight.Medium,
                    overflow = TextOverflow.Ellipsis,
                    softWrap = false,
                )
            }
            Box(
                Modifier
                    .layoutId("actionIcons")
                    .padding(end = actionIconPadding),
            ) {
                actions()
            }
            Box(
                Modifier
                    .layoutId("largeTitle")
                    .padding(top = TopAppBarDefaults.CollapsedHeight)
                    .padding(horizontal = titlePadding)
                    .graphicsLayer { alpha = largeTitleAlpha() },
            ) {
                Column(
                    modifier = Modifier
                        .offset {
                            val v = scrolledOffset()
                            // S modification: NaN check before roundToInt
                            IntOffset(0, if (v.isNaN()) 0 else v.roundToInt())
                        }
                        .onSizeChanged { updateHeightOffsetLimit(it.height) },
                ) {
                    Text(
                        text = largeTitle,
                        color = animatedLargeTitleColor,
                        fontSize = MiuixTheme.textStyles.title1.fontSize,
                        fontWeight = FontWeight.Normal,
                    )
                    if (subtitle.isNotEmpty()) {
                        Text(
                            text = subtitle,
                            color = animatedSubtitleColor,
                            style = MiuixTheme.textStyles.body2,
                        )
                    }
                }
            }
            if (subtitle.isNotEmpty()) {
                Box(
                    Modifier
                        .layoutId("smallSubtitle")
                        .graphicsLayer {
                            alpha = smallTitleAlpha.value
                            translationY = smallTitleTranslationY.value
                        },
                ) {
                    Text(
                        text = subtitle,
                        color = animatedSubtitleColor,
                        style = MiuixTheme.textStyles.body2,
                    )
                }
            }
            Box(Modifier.layoutId("bottomContent")) {
                bottomContent()
            }
        },
        modifier = modifier
            .background(color)
            .then(
                if (defaultWindowInsetsPadding) {
                    Modifier
                        .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
                        .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal))
                } else {
                    Modifier
                },
            )
            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top))
            .clipToBounds()
            .pointerInput(Unit) {
                detectTapGestures { /* Consume click */ }
            },
    ) { measurables, constraints ->
        val navigationIconPlaceable =
            measurables
                .fastFirst { it.layoutId == "navigationIcon" }
                .measure(constraints.copy(minWidth = 0, minHeight = 0))

        val actionIconsPlaceable =
            measurables
                .fastFirst { it.layoutId == "actionIcons" }
                .measure(constraints.copy(minWidth = 0, minHeight = 0))

        val maxTitleWidth =
            if (constraints.maxWidth == Constraints.Infinity) {
                constraints.maxWidth
            } else {
                (constraints.maxWidth - navigationIconPlaceable.width - actionIconsPlaceable.width)
                    .coerceAtLeast(0)
            }
        val titleMaxWidth =
            if (maxTitleWidth == Constraints.Infinity) {
                maxTitleWidth
            } else {
                // S modification: roundToInt instead of fastRoundToInt
                (maxTitleWidth * STitleWidthFraction).roundToInt()
            }

        val titlePlaceable =
            measurables
                .fastFirst { it.layoutId == "title" }
                .measure(constraints.copy(minWidth = 0, maxWidth = titleMaxWidth, minHeight = 0))

        val largeTitlePlaceable =
            measurables
                .fastFirst { it.layoutId == "largeTitle" }
                .measure(
                    constraints.copy(
                        minWidth = 0,
                        minHeight = 0,
                        maxHeight = Constraints.Infinity,
                    ),
                )

        val smallSubtitlePlaceable =
            measurables
                .firstOrNull { it.layoutId == "smallSubtitle" }
                ?.measure(constraints.copy(minWidth = 0, maxWidth = titleMaxWidth, minHeight = 0))

        val bottomContentPlaceable =
            measurables
                .fastFirst { it.layoutId == "bottomContent" }
                .measure(constraints.copy(minWidth = 0, minHeight = 0))

        val collapsedHeight = TopAppBarDefaults.CollapsedHeight.roundToPx()
        val expansion = (largeTitlePlaceable.height - collapsedHeight).coerceAtLeast(0)
        val barHeight = if (expansion > 0) {
            val offset = scrolledOffset()
            // S modification: NaN check
            val collapseFraction = if (offset.isNaN()) {
                0f
            } else {
                (abs(offset) / expansion.toFloat()).coerceIn(0f, 1f)
            }
            lerp(
                start = collapsedHeight,
                stop = collapsedHeight + expansion,
                fraction = 1f - collapseFraction,
            )
        } else {
            collapsedHeight
        }

        val verticalCenter = collapsedHeight / 2
        val smallSubtitleHeight = smallSubtitlePlaceable?.height ?: 0
        val smallSubtitleBottom = verticalCenter + titlePlaceable.height / 2 + smallSubtitleHeight
        val expandedBottomPadding = if (smallSubtitlePlaceable != null) {
            TopAppBarDefaults.SubtitleBottomPadding.roundToPx()
        } else {
            TopAppBarDefaults.LargeTitleBottomPadding.roundToPx()
        }
        val contentTop =
            maxOf(barHeight + expandedBottomPadding, smallSubtitleBottom + expandedBottomPadding)
        val layoutHeight = contentTop + bottomContentPlaceable.height

        layout(constraints.maxWidth, layoutHeight) {
            navigationIconPlaceable.placeRelative(
                x = 0,
                y = verticalCenter - navigationIconPlaceable.height / 2,
            )

            var baseX = (constraints.maxWidth - titlePlaceable.width) / 2
            if (baseX < navigationIconPlaceable.width) {
                baseX += (navigationIconPlaceable.width - baseX)
            } else if (baseX + titlePlaceable.width > constraints.maxWidth - actionIconsPlaceable.width) {
                baseX += ((constraints.maxWidth - actionIconsPlaceable.width) - (baseX + titlePlaceable.width))
            }
            titlePlaceable.placeRelative(
                x = baseX,
                y = verticalCenter - titlePlaceable.height / 2,
            )

            smallSubtitlePlaceable?.placeRelative(
                x = (constraints.maxWidth - smallSubtitlePlaceable.width) / 2,
                y = verticalCenter + titlePlaceable.height / 2,
            )

            actionIconsPlaceable.placeRelative(
                x = constraints.maxWidth - actionIconsPlaceable.width,
                y = verticalCenter - actionIconsPlaceable.height / 2,
            )

            largeTitlePlaceable.placeRelative(x = 0, y = 0)

            bottomContentPlaceable.placeRelative(x = 0, y = contentTop)
        }
    }
}

/**
 * S version of SmallTopAppBarLayout (private in original miuix).
 *
 * Modification: fastRoundToInt → roundToInt.
 */
@Composable
private fun SSmallTopAppBarLayout(
    title: String,
    color: Color,
    titleColor: Color,
    subtitle: String,
    subtitleColor: Color,
    navigationIcon: @Composable () -> Unit,
    actions: @Composable () -> Unit,
    titlePadding: Dp,
    navigationIconPadding: Dp,
    actionIconPadding: Dp,
    modifier: Modifier = Modifier,
    defaultWindowInsetsPadding: Boolean = true,
    bottomContent: @Composable () -> Unit = {},
) {
    val animatedTitleColor by animateColorAsState(
        targetValue = titleColor,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 50),
    )
    val animatedSubtitleColor by animateColorAsState(
        targetValue = subtitleColor,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 50),
    )

    Layout(
        {
            Box(
                Modifier
                    .layoutId("navigationIcon")
                    .padding(start = navigationIconPadding),
            ) {
                navigationIcon()
            }
            Box(
                Modifier
                    .layoutId("title")
                    .padding(horizontal = titlePadding),
            ) {
                Text(
                    text = title,
                    color = animatedTitleColor,
                    maxLines = 1,
                    fontSize = MiuixTheme.textStyles.title3.fontSize,
                    fontWeight = FontWeight.Medium,
                    overflow = TextOverflow.Ellipsis,
                    softWrap = false,
                )
            }
            Box(
                Modifier
                    .layoutId("actionIcons")
                    .padding(end = actionIconPadding),
            ) {
                actions()
            }
            if (subtitle.isNotEmpty()) {
                Box(Modifier.layoutId("subtitle")) {
                    Text(
                        text = subtitle,
                        color = animatedSubtitleColor,
                        style = MiuixTheme.textStyles.body2,
                    )
                }
            }
            Box(Modifier.layoutId("bottomContent")) {
                bottomContent()
            }
        },
        modifier = modifier
            .background(color)
            .then(
                if (defaultWindowInsetsPadding) {
                    Modifier
                        .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
                        .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal))
                } else {
                    Modifier
                },
            )
            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top))
            .clipToBounds()
            .pointerInput(Unit) {
                detectTapGestures { /* Consume click */ }
            },
    ) { measurables, constraints ->
        val navigationIconPlaceable =
            measurables
                .fastFirst { it.layoutId == "navigationIcon" }
                .measure(constraints.copy(minWidth = 0, minHeight = 0))

        val actionIconsPlaceable =
            measurables
                .fastFirst { it.layoutId == "actionIcons" }
                .measure(constraints.copy(minWidth = 0, minHeight = 0))

        val maxTitleWidth =
            if (constraints.maxWidth == Constraints.Infinity) {
                constraints.maxWidth
            } else {
                (constraints.maxWidth - navigationIconPlaceable.width - actionIconsPlaceable.width)
                    .coerceAtLeast(0)
            }
        val titleMaxWidth =
            if (maxTitleWidth == Constraints.Infinity) {
                maxTitleWidth
            } else {
                // S modification: roundToInt instead of fastRoundToInt
                (maxTitleWidth * STitleWidthFraction).roundToInt()
            }

        val titlePlaceable =
            measurables
                .fastFirst { it.layoutId == "title" }
                .measure(constraints.copy(minWidth = 0, maxWidth = titleMaxWidth, minHeight = 0))

        val subtitlePlaceable =
            measurables
                .firstOrNull { it.layoutId == "subtitle" }
                ?.measure(constraints.copy(minWidth = 0, maxWidth = titleMaxWidth, minHeight = 0))

        val bottomContentPlaceable =
            measurables
                .fastFirst { it.layoutId == "bottomContent" }
                .measure(constraints.copy(minWidth = 0, minHeight = 0))

        val subtitleHeight = subtitlePlaceable?.height ?: 0
        val collapsedHeight = TopAppBarDefaults.CollapsedHeight.roundToPx()
        val verticalCenter = TopAppBarDefaults.SmallTopAppBarCenterHeight.roundToPx() / 2
        val subtitleY = verticalCenter + titlePlaceable.height / 2
        val subtitleBottomPadding =
            if (subtitlePlaceable != null) TopAppBarDefaults.SubtitleBottomPadding.roundToPx() else 0
        val contentTop = maxOf(collapsedHeight, subtitleY + subtitleHeight + subtitleBottomPadding)
        val layoutHeight = contentTop + bottomContentPlaceable.height

        layout(constraints.maxWidth, layoutHeight) {
            navigationIconPlaceable.placeRelative(
                x = 0,
                y = verticalCenter - navigationIconPlaceable.height / 2,
            )

            var baseX = (constraints.maxWidth - titlePlaceable.width) / 2
            if (baseX < navigationIconPlaceable.width) {
                baseX += (navigationIconPlaceable.width - baseX)
            } else if (baseX + titlePlaceable.width > constraints.maxWidth - actionIconsPlaceable.width) {
                baseX += ((constraints.maxWidth - actionIconsPlaceable.width) - (baseX + titlePlaceable.width))
            }
            titlePlaceable.placeRelative(
                x = baseX,
                y = verticalCenter - titlePlaceable.height / 2,
            )

            actionIconsPlaceable.placeRelative(
                x = constraints.maxWidth - actionIconsPlaceable.width,
                y = verticalCenter - actionIconsPlaceable.height / 2,
            )

            subtitlePlaceable?.placeRelative(
                x = (constraints.maxWidth - subtitlePlaceable.width) / 2,
                y = subtitleY,
            )

            bottomContentPlaceable.placeRelative(x = 0, y = contentTop)
        }
    }
}

// Slack so the centred title isn't butted against nav/actions.
private val STitleWidthFraction = 0.9
