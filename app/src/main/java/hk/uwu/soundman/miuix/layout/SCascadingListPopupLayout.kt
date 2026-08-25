// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package hk.uwu.soundman.miuix.layout

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import hk.uwu.soundman.miuix.basic.SListPopupDefaults
import hk.uwu.soundman.miuix.basic.SPopupPositionProvider
import hk.uwu.soundman.miuix.basic.rememberSListPopupLayoutInfo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.anim.folmeSpring
import top.yukonga.miuix.kmp.basic.DropdownColors
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal val SCascadingPopupCornerRadius = 16.dp

private const val MAIN_SPRING_DAMPING = 0.95f
private const val EXPAND_SPRING_DAMPING = 0.99f
private const val EXPAND_SPRING_RESPONSE = 0.45f
private const val COLLAPSE_SPRING_RESPONSE = 0.2f
private const val ARROW_EXPAND_SPRING_RESPONSE = 0.2f
private const val ARROW_COLLAPSE_SPRING_RESPONSE = 0.3f
private const val PRIMARY_SHRUNK_SCALE = 0.95f
internal const val NEXIO_ENTER_SCALE_FROM = 0.15f
internal const val NEXIO_ENTER_SCALE_RANGE = 1f - NEXIO_ENTER_SCALE_FROM

/**
 * Internal shared layout for cascading list popups（S 版本）。
 * Cascading depth is limited to 2.
 *
 * 注意：此组件依赖 [CascadingMorphSubLayout]，后者是 private 的且包含大量内部渲染逻辑。
 * 如果无法访问原始 miuix 的内部成员，需要在此处完整重实现 CascadingMorphSubLayout。
 * TODO: Requires internal access to CascadingMorphSubLayout and its sub-composables
 *       (CascadingPrimaryContent, CascadingSecondaryContent, CascadingSecondaryColumn, MorphHeaderRow).
 *       These are private in the original miuix source and cannot be accessed externally.
 *       A full reimplementation would require copying all private composables and their dependencies.
 */
@Composable
fun SCascadingListPopupLayout(
    show: Boolean,
    popupHost: @Composable (visible: Boolean, content: @Composable () -> Unit) -> Unit,
    entries: List<DropdownEntry>,
    onDismissRequest: () -> Unit,
    dropdownColors: DropdownColors,
    popupModifier: Modifier = Modifier,
    onDismissFinished: (() -> Unit)? = null,
    popupPositionProvider: SPopupPositionProvider = SListPopupDefaults.DropdownPositionProvider,
    alignment: SPopupPositionProvider.Align = SPopupPositionProvider.Align.End,
    enableWindowDim: Boolean = true,
    maxHeight: Dp? = null,
    minWidth: Dp = 200.dp,
    collapseOnSelection: Boolean = true,
) {
    val internalVisible = remember { mutableStateOf(false) }
    val enterFraction = remember { Animatable(0f) }
    val enterAlpha = remember { Animatable(0f) }
    val dimProgress = remember { Animatable(0f) }

    var expandedItem by remember { mutableStateOf<DropdownItem?>(null) }
    // Outlives [expandedItem] until the collapse spring settles so the secondary stays subcomposed.
    var displayedItem by remember { mutableStateOf<DropdownItem?>(null) }
    val expandFraction = remember { Animatable(0f) }
    val primaryScale = remember { Animatable(1f) }
    val maskAlpha = remember { Animatable(0f) }
    val arrowRotation = remember { Animatable(0f) }

    val layoutDirection = LocalLayoutDirection.current
    val arrowEndDeg = remember(layoutDirection) {
        if (layoutDirection == LayoutDirection.Ltr) -90f else 90f
    }

    val currentOnDismiss by rememberUpdatedState(onDismissRequest)
    val currentOnDismissFinished by rememberUpdatedState(onDismissFinished)
    val coroutineScope = rememberCoroutineScope()
    // Defer enter until both are measured — otherwise transformOrigin and offset jump on frame 1.
    var primarySize by remember { mutableStateOf(IntSize.Zero) }
    var hostPositionInWindow by remember { mutableStateOf(IntOffset.Zero) }
    var hostMeasured by remember { mutableStateOf(false) }

    LaunchedEffect(show) {
        if (show) {
            internalVisible.value = true
            launch { dimProgress.animateTo(1f, SListPopupDefaults.DimEnterAnimationSpec) }
            snapshotFlow { primarySize to hostMeasured }
                .first { (size, ready) -> size != IntSize.Zero && ready }
            launch { enterFraction.animateTo(1f, SListPopupDefaults.FractionAnimationSpec) }
            launch { enterAlpha.animateTo(1f, SListPopupDefaults.AlphaEnterAnimationSpec) }
        } else {
            if (!internalVisible.value) return@LaunchedEffect
            expandedItem = null
            launch { enterFraction.animateTo(0f, SListPopupDefaults.FractionAnimationSpec) }
            launch { dimProgress.animateTo(0f, SListPopupDefaults.DimExitAnimationSpec) }
            enterAlpha.animateTo(0f, SListPopupDefaults.AlphaExitAnimationSpec)
            enterFraction.stop()
            dimProgress.stop()
            internalVisible.value = false
            currentOnDismissFinished?.invoke()
        }
    }

    LaunchedEffect(expandedItem) {
        val item = expandedItem
        if (item != null) displayedItem = item
        val target = if (item != null) 1f else 0f
        val mainSpec = if (target == 1f) {
            folmeSpring(EXPAND_SPRING_DAMPING, EXPAND_SPRING_RESPONSE)
        } else {
            folmeSpring<Float>(MAIN_SPRING_DAMPING, COLLAPSE_SPRING_RESPONSE)
        }
        launch {
            expandFraction.animateTo(target, mainSpec)
            // Settle on the main spring — the slower arrowRotation would otherwise hold the
            // secondary subcomposed past visibility.
            if (target == 0f) displayedItem = null
        }
        launch {
            primaryScale.animateTo(
                if (target == 1f) PRIMARY_SHRUNK_SCALE else 1f,
                mainSpec,
            )
        }
        launch { maskAlpha.animateTo(target, mainSpec) }
        launch {
            val arrowResponse = if (target == 1f) {
                ARROW_EXPAND_SPRING_RESPONSE
            } else {
                ARROW_COLLAPSE_SPRING_RESPONSE
            }
            arrowRotation.animateTo(
                target * arrowEndDeg,
                folmeSpring(MAIN_SPRING_DAMPING, arrowResponse),
            )
        }
    }

    if (!show && !internalVisible.value) return

    var parentBounds by remember { mutableStateOf(IntRect.Zero) }
    Spacer(
        modifier = Modifier.onGloballyPositioned { childCoordinates ->
            childCoordinates.parentLayoutCoordinates?.let { parent ->
                val pos = parent.positionInWindow()
                parentBounds = IntRect(
                    left = pos.x.toInt(),
                    top = pos.y.toInt(),
                    right = pos.x.toInt() + parent.size.width,
                    bottom = pos.y.toInt() + parent.size.height,
                )
            }
        },
    )
    if (parentBounds == IntRect.Zero) return

    val layoutInfo = rememberSListPopupLayoutInfo(
        alignment = alignment,
        popupPositionProvider = popupPositionProvider,
        parentBounds = parentBounds,
        popupContentSize = primarySize,
    )

    val anchorBoundsByItem = remember { mutableStateMapOf<DropdownItem, IntRect>() }

    popupHost(internalVisible.value) {
        val backState = rememberNavigationEventState(currentInfo = NavigationEventInfo.None)
        NavigationBackHandler(
            state = backState,
            isBackEnabled = show,
            onBackCancelled = {
                // Reset whichever tree the gesture drove; depth cannot change mid-gesture.
                coroutineScope.launch {
                    if (expandedItem != null) {
                        val mainSpec =
                            folmeSpring<Float>(EXPAND_SPRING_DAMPING, EXPAND_SPRING_RESPONSE)
                        val arrowSpec =
                            folmeSpring<Float>(MAIN_SPRING_DAMPING, ARROW_EXPAND_SPRING_RESPONSE)
                        joinAll(
                            launch { expandFraction.animateTo(1f, mainSpec) },
                            launch { primaryScale.animateTo(PRIMARY_SHRUNK_SCALE, mainSpec) },
                            launch { maskAlpha.animateTo(1f, mainSpec) },
                            launch { arrowRotation.animateTo(arrowEndDeg, arrowSpec) },
                        )
                    } else {
                        joinAll(
                            launch {
                                enterFraction.animateTo(
                                    1f,
                                    SListPopupDefaults.ResetAnimationSpec
                                )
                            },
                            launch {
                                enterAlpha.animateTo(
                                    1f,
                                    SListPopupDefaults.AlphaEnterAnimationSpec
                                )
                            },
                            launch {
                                dimProgress.animateTo(
                                    1f,
                                    SListPopupDefaults.DimEnterAnimationSpec
                                )
                            },
                        )
                    }
                }
            },
            // Mirror outside-tap: depth > 0 collapses the secondary, depth 0 dismisses the popup.
            onBackCompleted = {
                if (expandedItem != null) {
                    expandedItem = null
                } else {
                    currentOnDismiss()
                }
            },
        )

        LaunchedEffect(Unit) {
            // Single-coroutine collector so per-frame ticks do not relaunch this effect.
            snapshotFlow { backState.transitionState }
                .collect { transitionState ->
                    if (
                        transitionState is NavigationEventTransitionState.InProgress &&
                        transitionState.direction == NavigationEventTransitionState.TRANSITIONING_BACK
                    ) {
                        val inv = 1f - transitionState.latestEvent.progress
                        if (expandedItem != null) {
                            // Preview secondary → primary collapse along the expand-tree.
                            expandFraction.snapTo(inv)
                            primaryScale.snapTo(1f + (PRIMARY_SHRUNK_SCALE - 1f) * inv)
                            maskAlpha.snapTo(inv)
                            arrowRotation.snapTo(arrowEndDeg * inv)
                        } else {
                            // Preview popup → spawn corner retreat along the entry tree.
                            enterFraction.snapTo(inv)
                            enterAlpha.snapTo(inv)
                            dimProgress.snapTo(inv)
                        }
                    }
                }
        }

        CompositionLocalProvider(LocalDismissState provides { currentOnDismiss() }) {
            val dimColor = MiuixTheme.colorScheme.windowDimming
            val maskColor = remember(dimColor) {
                dimColor.copy(alpha = (dimColor.alpha * 0.5f).coerceIn(0f, 1f))
            }
            val surfaceColor = MiuixTheme.colorScheme.surfaceContainer

            Box(modifier = Modifier.fillMaxSize()) {
                if (enableWindowDim) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = dimProgress.value }
                            .background(dimColor),
                    )
                }
                Box(
                    modifier = popupModifier
                        .fillMaxSize()
                        .onGloballyPositioned { coords ->
                            val pos = coords.positionInWindow()
                            hostPositionInWindow = IntOffset(pos.x.toInt(), pos.y.toInt())
                            hostMeasured = true
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = {
                                if (expandedItem != null) {
                                    expandedItem = null
                                } else {
                                    currentOnDismiss()
                                }
                            })
                        },
                ) {
                    // TODO: Requires internal access to CascadingMorphSubLayout and its sub-composables
                    //       (CascadingPrimaryContent, CascadingSecondaryContent, CascadingSecondaryColumn,
                    //        MorphHeaderRow). These are private in the original miuix source and cannot
                    //        be accessed externally. A full reimplementation would require copying all
                    //        private composables and their dependencies (ContinuousRoundedRectangle shapes,
                    //        edge light effects, dropdown item rendering, etc.).
                    //
                    // The original CascadingMorphSubLayout call was:
                    // CascadingMorphSubLayout(
                    //     entries = entries,
                    //     expandedItem = expandedItem,
                    //     displayedItem = displayedItem,
                    //     onExpand = { expandedItem = it },
                    //     onCollapseSecondary = { expandedItem = null },
                    //     onLeafSelected = { item ->
                    //         item.onClick?.invoke()
                    //         if (collapseOnSelection) {
                    //             expandedItem = null
                    //             currentOnDismiss()
                    //         }
                    //     },
                    //     getAnchorBounds = { anchorBoundsByItem[it] },
                    //     setAnchorBounds = { item, bounds ->
                    //         if (displayedItem == null) {
                    //             anchorBoundsByItem[item] = bounds
                    //         }
                    //     },
                    //     onPrimarySizeChange = { primarySize = it },
                    //     windowBounds = layoutInfo.windowBounds,
                    //     popupMargin = layoutInfo.popupMargin,
                    //     parentBounds = parentBounds,
                    //     popupPositionProvider = popupPositionProvider,
                    //     alignment = alignment,
                    //     hostOriginInWindow = { hostPositionInWindow },
                    //     maxHeight = maxHeight,
                    //     minWidth = minWidth,
                    //     dropdownColors = dropdownColors,
                    //     enterFraction = { enterFraction.value },
                    //     enterAlpha = { enterAlpha.value },
                    //     primaryScale = { primaryScale.value },
                    //     maskAlpha = { maskAlpha.value },
                    //     expandFraction = { expandFraction.value },
                    //     arrowRotation = { arrowRotation.value },
                    //     layoutInfo = layoutInfo,
                    //     layoutDirection = layoutDirection,
                    //     surfaceColor = surfaceColor,
                    //     maskColor = maskColor,
                    // )
                }
            }
        }
    }
}

// =====================================================================
// Helper functions (ported from original, kept for future use when
// CascadingMorphSubLayout is fully reimplemented)
// =====================================================================

private fun unionOf(a: IntRect, b: IntRect): IntRect = IntRect(
    left = minOf(a.left, b.left),
    top = minOf(a.top, b.top),
    right = maxOf(a.right, b.right),
    bottom = maxOf(a.bottom, b.bottom),
)

internal fun computeSSecondaryRect(
    anchor: IntRect,
    secondarySize: IntSize,
    windowBounds: IntRect,
    layoutDirection: LayoutDirection,
): IntRect {
    val ltr = layoutDirection == LayoutDirection.Ltr
    // Leading-edge align; fall back to the opposite edge if the trailing side overflows.
    val left = if (ltr) {
        val leftAligned = anchor.left
        when {
            leftAligned + secondarySize.width <= windowBounds.right -> leftAligned

            anchor.right - secondarySize.width >= windowBounds.left ->
                windowBounds.right - secondarySize.width

            else -> windowBounds.left
        }
    } else {
        val rightAligned = anchor.right - secondarySize.width
        when {
            rightAligned >= windowBounds.left -> rightAligned
            anchor.left + secondarySize.width <= windowBounds.right -> anchor.left
            else -> (windowBounds.right - secondarySize.width).coerceAtLeast(windowBounds.left)
        }
    }
    val top = when {
        anchor.top + secondarySize.height <= windowBounds.bottom -> anchor.top
        anchor.bottom - secondarySize.height >= windowBounds.top -> anchor.bottom - secondarySize.height
        else -> (windowBounds.bottom - secondarySize.height).coerceAtLeast(windowBounds.top)
    }
    return IntRect(
        left = left,
        top = top,
        right = left + secondarySize.width,
        bottom = top + secondarySize.height,
    )
}

/** First/last/middle padding for [MorphHeaderRow]'s expansion-time padding interpolation.
 *  Must match the primary row's popup-global first/last logic or the morph's start frame jumps. */
private fun Density.computeAnchorPaddingPx(
    item: DropdownItem,
    entries: List<DropdownEntry>,
): Pair<Int, Int> {
    // Skip leading/trailing empty entries so the visually-first/last row keeps FirstLast padding.
    val firstNonEmptyIdx = entries.indexOfFirst { it.items.isNotEmpty() }
    val lastNonEmptyIdx = entries.indexOfLast { it.items.isNotEmpty() }
    entries.forEachIndexed { entryIdx, entry ->
        val idx = entry.items.indexOf(item)
        if (idx != -1) {
            val isPopupFirst = entryIdx == firstNonEmptyIdx && idx == 0
            val isPopupLast = entryIdx == lastNonEmptyIdx && idx == entry.items.lastIndex
            val top = if (isPopupFirst) {
                DropdownDefaults.FirstLastVerticalPadding.roundToPx()
            } else {
                DropdownDefaults.MiddleVerticalPadding.roundToPx()
            }
            val bottom = if (isPopupLast) {
                DropdownDefaults.FirstLastVerticalPadding.roundToPx()
            } else {
                DropdownDefaults.MiddleVerticalPadding.roundToPx()
            }
            return top to bottom
        }
    }
    return 0 to 0
}

private inline fun <T> List<T>.maxOfOrZero(selector: (T) -> Int): Int =
    if (isEmpty()) 0 else maxOf(selector)

private inline fun <T> List<T>.sumOfOrZero(selector: (T) -> Int): Int =
    if (isEmpty()) 0 else sumOf(selector)
