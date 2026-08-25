// Copyright 2025, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package hk.uwu.soundman.miuix.layout

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import hk.uwu.soundman.miuix.basic.SListPopupContent
import hk.uwu.soundman.miuix.basic.SListPopupDefaults
import hk.uwu.soundman.miuix.basic.SPopupLayoutPosition
import hk.uwu.soundman.miuix.basic.SPopupPositionProvider
import hk.uwu.soundman.miuix.basic.SPopupPositionResult
import hk.uwu.soundman.miuix.basic.rememberSListPopupLayoutInfo
import hk.uwu.soundman.miuix.basic.resolveSPopupAnchors
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.anim.SinOutEasing
import top.yukonga.miuix.kmp.theme.LocalDismissState

// 本地动画参数（库版本没有这些属性）
private val FractionEnterAnimSpec =
    spring<Float>(dampingRatio = 0.78f, stiffness = 232f, visibilityThreshold = 0.0001f)
private val FractionExitAnimSpec =
    spring<Float>(dampingRatio = 0.78f, stiffness = 400f, visibilityThreshold = 0.0001f)
private val AlphaEnterAnimSpec = tween<Float>(durationMillis = 120)
private val AlphaExitAnimSpec = tween<Float>(durationMillis = 320)
private val DimEnterAnimSpec = tween<Float>(durationMillis = 200, easing = SinOutEasing)
private val DimExitAnimSpec = tween<Float>(durationMillis = 300, easing = SinOutEasing)
private val LocalMinPopupHeight = 50.dp

private fun SPopupPositionProvider.Align.resolve(layoutDirection: LayoutDirection): SPopupPositionProvider.Align {
    if (layoutDirection == LayoutDirection.Ltr) return this
    return when (this) {
        SPopupPositionProvider.Align.Start -> SPopupPositionProvider.Align.End
        SPopupPositionProvider.Align.End -> SPopupPositionProvider.Align.Start
        SPopupPositionProvider.Align.TopStart -> SPopupPositionProvider.Align.TopEnd
        SPopupPositionProvider.Align.TopEnd -> SPopupPositionProvider.Align.TopStart
        SPopupPositionProvider.Align.BottomStart -> SPopupPositionProvider.Align.BottomEnd
        SPopupPositionProvider.Align.BottomEnd -> SPopupPositionProvider.Align.BottomStart
    }
}

// 自定义下拉定位提供者（带偏移量）
fun sLiquidDropdownPositionProvider(): SPopupPositionProvider = object : SPopupPositionProvider {
    private val margins = PaddingValues(horizontal = 0.dp, vertical = 0.dp)

    override fun calculatePosition(
        anchorBounds: IntRect,
        windowBounds: IntRect,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
        popupMargin: IntRect,
        alignment: SPopupPositionProvider.Align,
    ): SPopupPositionResult {
        val offsetXDelta = 82  //@ 3x density
        val offsetYDelta = 94  //@ 3x density

        val offsetX = if (alignment.resolve(layoutDirection) == SPopupPositionProvider.Align.End) {
            anchorBounds.right - popupContentSize.width - popupMargin.right + offsetXDelta
        } else {
            anchorBounds.left + popupMargin.left + offsetXDelta
        }

        val spaceBelow = windowBounds.bottom - anchorBounds.bottom
        val spaceAbove = anchorBounds.top - windowBounds.top
        val offsetY: Int
        val showBelow: Boolean
        val showAbove: Boolean
        if (spaceBelow > popupContentSize.height) {
            offsetY = anchorBounds.top - offsetYDelta
            showBelow = true
            showAbove = false
        } else if (spaceAbove > popupContentSize.height) {
            offsetY = anchorBounds.bottom - popupContentSize.height + offsetYDelta
            showBelow = false
            showAbove = true
        } else {
            offsetY = anchorBounds.top + anchorBounds.height / 2 - popupContentSize.height / 2
            showBelow = false
            showAbove = false
        }

        val clampedOffset = IntOffset(
            x = offsetX.coerceIn(
                windowBounds.left,
                (windowBounds.right - popupContentSize.width - popupMargin.right).coerceAtLeast(
                    windowBounds.left
                ),
            ),
            y = offsetY.coerceIn(
                (windowBounds.top + popupMargin.top).coerceAtMost(windowBounds.bottom - popupContentSize.height - popupMargin.bottom),
                windowBounds.bottom - popupContentSize.height - popupMargin.bottom,
            ),
        )
        return SPopupPositionResult(clampedOffset, showBelow, showAbove)
    }

    override fun getMargins(): PaddingValues = margins
}

/**
 * 弹窗布局的核心逻辑（S 版本）。
 *
 * @param show 是否显示弹窗
 * @param popupHost 弹窗容器
 * @param popupModifier 弹窗修饰符
 * @param popupPositionProvider 弹窗位置提供者
 * @param alignment 弹窗对齐方式
 * @param enableWindowDim 是否启用背景变暗
 * @param onDismissRequest 关闭弹窗的回调
 * @param onDismissFinished 关闭动画完成后的回调
 * @param maxHeight 弹窗最大高度
 * @param minWidth 弹窗最小宽度
 * @param liquidGlassBackdrop 液体玻璃模糊效果的Backdrop
 * @param onFractionProgress 缩放进度回调
 * @param revealLimitHeight 揭示限制高度
 * @param content 弹窗内容
 */
@Composable
fun SListPopupLayout(
    show: Boolean,
    popupHost: @Composable (visible: Boolean, content: @Composable () -> Unit) -> Unit,
    popupModifier: Modifier = Modifier,
    popupPositionProvider: SPopupPositionProvider = sLiquidDropdownPositionProvider(),
    alignment: SPopupPositionProvider.Align = SPopupPositionProvider.Align.Start,
    enableWindowDim: Boolean = true,
    onDismissRequest: (() -> Unit)? = null,
    onDismissFinished: (() -> Unit)? = null,
    maxHeight: Dp? = null,
    minWidth: Dp = SListPopupDefaults.MinWidth,
    liquidGlassBackdrop: Backdrop? = null,
    onFractionProgress: ((Float) -> Unit)? = null,
    revealLimitHeight: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    val fractionProgress = remember { Animatable(0f) }
    val alphaProgress = remember { Animatable(0f) }
    val dimProgress = remember { Animatable(0f) }
    val currentOnDismiss by rememberUpdatedState(onDismissRequest)
    val currentOnDismissFinished by rememberUpdatedState(onDismissFinished)
    val internalVisible = remember { mutableStateOf(false) }
    var popupContentSize by remember { mutableStateOf(IntSize.Zero) }
    var hostPositionInWindow by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(show) {
        if (show) {
            internalVisible.value = true
            launch { fractionProgress.animateTo(1f, FractionEnterAnimSpec) }
            launch { alphaProgress.animateTo(1f, AlphaEnterAnimSpec) }
            if (enableWindowDim) {
                launch { dimProgress.animateTo(1f, DimEnterAnimSpec) }
            }
        } else {
            if (!internalVisible.value) return@LaunchedEffect
            launch { fractionProgress.animateTo(0f, FractionExitAnimSpec) }
            if (enableWindowDim) {
                launch { dimProgress.animateTo(0f, DimExitAnimSpec) }
            }
            alphaProgress.animateTo(0f, AlphaExitAnimSpec)
            fractionProgress.snapTo(0f)
            alphaProgress.snapTo(0f)
            dimProgress.snapTo(0f)
            internalVisible.value = false
            currentOnDismissFinished?.invoke()
        }
    }

    val currentOnFractionProgress by rememberUpdatedState(onFractionProgress)
    LaunchedEffect(Unit) {
        currentOnFractionProgress?.let { callback ->
            snapshotFlow { fractionProgress.value }
                .collect { callback(it) }
        }
    }

    if (!show && !internalVisible.value) return

    var parentBounds by remember { mutableStateOf(IntRect.Zero) }

    Spacer(
        modifier = Modifier
            .onGloballyPositioned { childCoordinates ->
                childCoordinates.parentLayoutCoordinates?.let { parentLayoutCoordinates ->
                    val positionInWindow = parentLayoutCoordinates.positionInWindow()
                    parentBounds = IntRect(
                        left = positionInWindow.x.toInt(),
                        top = positionInWindow.y.toInt(),
                        right = positionInWindow.x.toInt() + parentLayoutCoordinates.size.width,
                        bottom = positionInWindow.y.toInt() + parentLayoutCoordinates.size.height,
                    )
                }
            },
    )

    if (parentBounds == IntRect.Zero) return

    val layoutInfo = rememberSListPopupLayoutInfo(
        alignment = alignment,
        popupPositionProvider = popupPositionProvider,
        parentBounds = parentBounds,
        popupContentSize = popupContentSize,
    )

    // 由 layout 阶段计算的真实方向和锚点，与 calculatedOffset 使用同一个 measuredSize，
    // 避免 composition 阶段的 popupContentSize 与 layout 阶段的 measuredSize 不一致。
    var realPopupLayoutPosition by remember {
        mutableStateOf(
            SPopupLayoutPosition(
                showBelow = true,
                showAbove = false,
                isRightAligned = false
            )
        )
    }
    var realLocalTransformOrigin by remember { mutableStateOf(TransformOrigin(0f, 0f)) }

    val requestDismiss: () -> Unit = remember {
        { currentOnDismiss?.invoke() }
    }

    popupHost(internalVisible.value) {
        BackHandler(enabled = show) {
            requestDismiss()
        }

        Box(
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = popupModifier
                    .fillMaxSize()
                    .onGloballyPositioned { coordinates ->
                        hostPositionInWindow = coordinates.positionInWindow()
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { requestDismiss() },
                        )
                    }
                    .layout { measurable, constraints ->
                        val windowBounds = layoutInfo.windowBounds
                        val popupMargin = layoutInfo.popupMargin
                        val minHeightPx = LocalMinPopupHeight.roundToPx()
                        val placeable = measurable.measure(
                            constraints.copy(
                                maxHeight = maxHeight?.roundToPx()?.coerceAtLeast(minHeightPx)
                                    ?: (windowBounds.height - popupMargin.top - popupMargin.bottom)
                                        .coerceAtLeast(minHeightPx),
                                minHeight = if (minHeightPx <= constraints.maxHeight) minHeightPx else constraints.maxHeight,
                                maxWidth = constraints.maxWidth,
                                minWidth = minWidth.roundToPx().coerceAtMost(constraints.maxWidth),
                            ),
                        )
                        val measuredSize = IntSize(placeable.width, placeable.height)

                        val positionResult = popupPositionProvider.calculatePosition(
                            parentBounds,
                            windowBounds,
                            layoutDirection,
                            measuredSize,
                            popupMargin,
                            alignment,
                        )
                        val calculatedOffset = positionResult.offset

                        // 从同一个 positionResult 推导方向和锚点，保证一致性
                        val (layoutPos, transformOrigin) = resolveSPopupAnchors(
                            positionResult,
                            calculatedOffset,
                            measuredSize,
                            parentBounds,
                            alignment,
                            layoutDirection,
                        )
                        realPopupLayoutPosition = layoutPos
                        realLocalTransformOrigin = transformOrigin

                        val adjustedOffset = IntOffset(
                            x = calculatedOffset.x - hostPositionInWindow.x.toInt(),
                            y = calculatedOffset.y - hostPositionInWindow.y.toInt(),
                        )

                        layout(constraints.maxWidth, constraints.maxHeight) {
                            placeable.place(adjustedOffset)
                        }
                    },
            ) {
                SListPopupContent(
                    popupContentSize = popupContentSize,
                    onPopupContentSizeChange = { popupContentSize = it },
                    fractionProgress = { fractionProgress.value },
                    alphaProgress = { alphaProgress.value },
                    popupLayoutPosition = realPopupLayoutPosition,
                    localTransformOrigin = realLocalTransformOrigin,
                    liquidGlassBackdrop = liquidGlassBackdrop,
                    revealLimitHeight = revealLimitHeight,
                    content = {
                        CompositionLocalProvider(LocalDismissState provides requestDismiss) {
                            content()
                        }
                    },
                )
            }
        }
    }
}
