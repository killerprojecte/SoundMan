// Copyright 2025, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package hk.uwu.soundman.miuix.basic

import android.graphics.BlurMaskFilter
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.kyant.capsule.ContinuousRoundedRectangle
import hk.uwu.soundman.ui.basic.rememberSafeBackdrop
import hk.uwu.soundman.ui.effects.edgelight.edgeLight
import hk.uwu.soundman.ui.effects.edgelight.rememberDefaultEdgeLight
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.anim.SinOutEasing
import top.yukonga.miuix.kmp.squircle.isSquircleEnabled
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.min

// =====================================================================
// 工具函数
// =====================================================================

/**
 * 将对齐方向根据布局方向（LTR/RTL）进行解析。
 * 在RTL布局下，Start和End会互换，TopStart和TopEnd会互换，依此类推。
 */
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

/**
 * 安全创建TransformOrigin，处理NaN和负值情况。
 * 如果值无效则返回0，否则返回原始值。
 */
fun sSafeTransformOrigin(x: Float, y: Float): TransformOrigin {
    val safeX = if (x.isNaN() || x < 0f) 0f else x
    val safeY = if (y.isNaN() || y < 0f) 0f else y
    return TransformOrigin(safeX, safeY)
}

/**
 * 从 [SPopupPositionResult] 和实际偏移量推导 [SPopupLayoutPosition] 和本地 [TransformOrigin]。
 * 在 composition 和 layout 阶段均可调用，保证方向和锚点始终一致。
 */
fun resolveSPopupAnchors(
    positionResult: SPopupPositionResult,
    calculatedOffset: IntOffset,
    popupContentSize: IntSize,
    parentBounds: IntRect,
    alignment: SPopupPositionProvider.Align,
    layoutDirection: LayoutDirection,
): Pair<SPopupLayoutPosition, TransformOrigin> {
    val isRightAligned = when (alignment.resolve(layoutDirection)) {
        SPopupPositionProvider.Align.End,
        SPopupPositionProvider.Align.TopEnd,
        SPopupPositionProvider.Align.BottomEnd,
            -> true

        else -> false
    }
    val distLeft = abs(calculatedOffset.x - parentBounds.left)
    val distRight = abs((calculatedOffset.x + popupContentSize.width) - parentBounds.right)
    val rightAligned = if (popupContentSize.width > 0) distRight < distLeft else isRightAligned

    val layoutPos = SPopupLayoutPosition(
        showBelow = positionResult.showBelow,
        showAbove = positionResult.showAbove,
        isRightAligned = rightAligned,
    )
    val origin = TransformOrigin(
        pivotFractionX = if (rightAligned) 1f else 0f,
        pivotFractionY = if (positionResult.showAbove) 1f else 0f,
    )
    return layoutPos to origin
}

// =====================================================================
// 常量 - 用于SListPopupColumn的测量策略
// =====================================================================

/** 计算宽度时考虑的最大子项数量 */
private const val MAX_ITEMS_FOR_WIDTH = 8

/** 计算高度时考虑的最大子项数量 */
private const val MAX_ITEMS_FOR_HEIGHT = 8

// =====================================================================
// SListPopupColumn - 弹窗内容列组件
// =====================================================================

/**
 * 弹窗内容列，自动将宽度对齐到最宽的子项。
 *
 * 功能说明：
 * - 自动计算宽度：取前8个子项的最大固有宽度，限制在200dp~288dp之间
 * - 支持垂直滚动
 * - 使用自定义MeasurePolicy进行精确的宽度控制
 *
 * @param content 弹窗内容子项
 */
@Composable
fun SListPopupColumn(
    content: @Composable () -> Unit,
) {
    val scrollState = rememberScrollState()

    val measurePolicy = remember {
        object : MeasurePolicy {
            override fun MeasureScope.measure(
                measurables: List<Measurable>,
                constraints: Constraints,
            ): MeasureResult {
                // 宽度范围：200dp ~ 288dp
                val minPx = 200.dp.roundToPx()
                val maxPx = 288.dp.roundToPx()
                val widthCount = min(MAX_ITEMS_FOR_WIDTH, measurables.size)
                var maxIntrinsic = 0
                for (i in 0 until widthCount) {
                    val w = measurables[i].maxIntrinsicWidth(constraints.maxHeight)
                    if (w > maxIntrinsic) maxIntrinsic = w
                }
                val parentMin = constraints.minWidth
                val parentMax = constraints.maxWidth
                val upper = maxOf(maxPx, parentMin).coerceAtMost(parentMax)
                val lower = maxOf(minPx, parentMin).coerceAtMost(upper)
                val listWidth = maxIntrinsic.coerceIn(lower, upper)

                // 使用计算出的宽度测量所有子项
                val childConstraints =
                    constraints.copy(minWidth = listWidth, maxWidth = listWidth, minHeight = 0)

                val placeables = ArrayList<Placeable>(measurables.size)
                var listHeight = 0
                for (i in measurables.indices) {
                    val p = measurables[i].measure(childConstraints)
                    placeables.add(p)
                    listHeight += p.height
                }

                return layout(listWidth, listHeight) {
                    var currentY = 0
                    for (i in placeables.indices) {
                        val p = placeables[i]
                        p.placeRelative(0, currentY)
                        currentY += p.height
                    }
                }
            }

            override fun IntrinsicMeasureScope.minIntrinsicHeight(
                measurables: List<IntrinsicMeasurable>,
                width: Int,
            ): Int {
                val minPx = 200.dp.roundToPx()
                val maxPx = 288.dp.roundToPx()
                val widthCount = min(MAX_ITEMS_FOR_WIDTH, measurables.size)
                var maxIntrinsic = 0
                for (i in 0 until widthCount) {
                    val w = measurables[i].maxIntrinsicWidth(Int.MAX_VALUE)
                    if (w > maxIntrinsic) maxIntrinsic = w
                }
                val listWidth = maxIntrinsic.coerceIn(minPx, maxPx)

                val heightCount = min(MAX_ITEMS_FOR_HEIGHT, measurables.size)
                var height = 0
                for (i in 0 until heightCount) {
                    height += measurables[i].minIntrinsicHeight(listWidth)
                }
                return height
            }
        }
    }

    Layout(
        content = content,
        modifier = Modifier
            .focusGroup()
            .height(IntrinsicSize.Min)
            .verticalScroll(state = scrollState),
        measurePolicy = measurePolicy,
    )
}

// =====================================================================
// SPopupPositionProvider - 弹窗位置提供者接口
// =====================================================================

/**
 * 弹窗位置计算结果，包含偏移量和展开方向。
 *
 * @param offset 弹窗左上角在窗口坐标系中的偏移量
 * @param showBelow 弹窗是否在锚点下方展开
 * @param showAbove 弹窗是否在锚点上方展开
 */
@Immutable
data class SPopupPositionResult(
    val offset: IntOffset,
    val showBelow: Boolean,
    val showAbove: Boolean,
)

/**
 * 弹窗位置提供者接口。
 * 负责计算弹窗相对于锚点（触发组件）的显示位置。
 *
 * 注意：位置是相对于窗口计算的，不是相对于锚点！
 */
@Stable
interface SPopupPositionProvider {
    /**
     * 计算弹窗的位置（偏移量）和展开方向。
     *
     * @param anchorBounds 锚点（父组件）的边界
     * @param windowBounds 窗口安全区域的边界（排除状态栏、导航栏、刘海等）
     * @param layoutDirection 布局方向（LTR/RTL）
     * @param popupContentSize 弹窗内容的实际大小
     * @param popupMargin 弹窗的额外边距
     * @param alignment 弹窗相对于窗口的对齐方式
     */
    fun calculatePosition(
        anchorBounds: IntRect,
        windowBounds: IntRect,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
        popupMargin: IntRect,
        alignment: Align,
    ): SPopupPositionResult

    /**
     * 获取弹窗的额外边距
     */
    fun getMargins(): PaddingValues

    /**
     * 弹窗相对于窗口的对齐方式（不是相对于锚点！）
     */
    enum class Align {
        Start,      // 左对齐（RTL下为右对齐）
        End,        // 右对齐（RTL下为左对齐）
        TopStart,   // 左上角
        TopEnd,     // 右上角
        BottomStart,// 左下角
        BottomEnd,  // 右下角
    }
}

// =====================================================================
// SListPopupDefaults - 弹窗默认配置
// =====================================================================

/**
 * 弹窗的默认配置对象。
 * 包含动画参数、尺寸限制、位置提供者等。
 */
object SListPopupDefaults {
    // ---- 动画参数 ----

    /**
     * 进入时的缩放动画（较慢，stiffness较小）
     * - dampingRatio: 阻尼比，控制弹簧的弹性程度（0.78 = 适中的弹性）
     * - stiffness: 刚度，控制弹簧的硬度（232 = 较慢）
     */
    val FractionEnterAnimationSpec =
        spring<Float>(dampingRatio = 0.78f, stiffness = 232f, visibilityThreshold = 0.0001f)

    /**
     * 退出时的缩放动画
     * - dampingRatio: 阻尼比，控制弹簧的弹性程度（0.78 = 适中的弹性）
     * - stiffness: 刚度，控制弹簧的硬度（400 = 中等速度）
     */
    val FractionExitAnimationSpec =
        spring<Float>(dampingRatio = 0.78f, stiffness = 400f, visibilityThreshold = 0.0001f)

    /** 通用缩放动画（兼容库引用，等同于进入动画） */
    val FractionAnimationSpec = FractionEnterAnimationSpec

    /** 进入时的透明度动画（120ms渐入） */
    val AlphaEnterAnimationSpec = tween<Float>(durationMillis = 120)

    /** 退出时的透明度动画（320ms渐出） */
    val AlphaExitAnimationSpec = tween<Float>(durationMillis = 320)

    /** 背景变暗的进入动画（200ms，使用SinOut缓动） */
    val DimEnterAnimationSpec = tween<Float>(durationMillis = 200, easing = SinOutEasing)

    /** 背景变暗的退出动画（300ms，使用SinOut缓动） */
    val DimExitAnimationSpec = tween<Float>(durationMillis = 300, easing = SinOutEasing)

    /** 手势重置动画（弹簧效果，用于返回手势后恢复弹窗状态） */
    val ResetAnimationSpec =
        spring<Float>(dampingRatio = 0.82f, stiffness = 362.5f, visibilityThreshold = 0.0001f)

    // ---- 尺寸限制 ----

    /** 弹窗最小宽度（200dp） */
    val MinWidth = 200.dp

    /** 弹窗测量时的最小高度（50dp），用作maxHeight和minHeight约束的下限 */
    val MinPopupHeight = 50.dp

    // ---- 位置提供者 ----

    /**
     * 创建下拉式位置提供者。
     * 弹窗会覆盖锚点文字显示（弹窗上端或下端与锚点对齐）。
     *
     * @param verticalMargin 弹窗与锚点之间的垂直间距（默认0dp，覆盖模式）
     * @param horizontalMargin 弹窗的水平边距（默认0dp）
     */
    fun sDropdownPositionProvider(
        verticalMargin: Dp = 0.dp,
        horizontalMargin: Dp = 0.dp,
    ): SPopupPositionProvider = object : SPopupPositionProvider {
        private val margins =
            PaddingValues(horizontal = horizontalMargin, vertical = verticalMargin)

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

            // 计算X偏移（左对齐或右对齐，往右偏移）
            val offsetX =
                if (alignment.resolve(layoutDirection) == SPopupPositionProvider.Align.End) {
                    anchorBounds.right - popupContentSize.width - popupMargin.right + offsetXDelta
                } else {
                    anchorBounds.left + popupMargin.left + offsetXDelta
                }

            // 计算Y偏移并记录展开方向
            val spaceBelow = windowBounds.bottom - anchorBounds.bottom
            val spaceAbove = anchorBounds.top - windowBounds.top
            val offsetY: Int
            val showBelow: Boolean
            val showAbove: Boolean
            if (spaceBelow > popupContentSize.height) {
                // 显示在下方：弹窗上端与锚点上端对齐，往上偏移
                offsetY = anchorBounds.top - offsetYDelta
                showBelow = true
                showAbove = false
            } else if (spaceAbove > popupContentSize.height) {
                // 显示在上方：弹窗下端与锚点下端对齐，往下偏移
                offsetY = anchorBounds.bottom - popupContentSize.height + offsetYDelta
                showBelow = false
                showAbove = true
            } else {
                // 居中显示
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

    /** 默认的下拉位置提供者（verticalMargin=0dp, horizontalMargin=0dp） */
    val DropdownPositionProvider: SPopupPositionProvider = sDropdownPositionProvider()

    /**
     * 右键菜单/上下文菜单的位置提供者。
     * 弹窗会锚定到锚点的某个角上。
     */
    val ContextMenuPositionProvider = object : SPopupPositionProvider {
        override fun calculatePosition(
            anchorBounds: IntRect,
            windowBounds: IntRect,
            layoutDirection: LayoutDirection,
            popupContentSize: IntSize,
            popupMargin: IntRect,
            alignment: SPopupPositionProvider.Align,
        ): SPopupPositionResult {
            val offsetX: Int
            val offsetY: Int
            val showBelow: Boolean
            val showAbove: Boolean
            when (alignment.resolve(layoutDirection)) {
                SPopupPositionProvider.Align.TopStart -> {
                    offsetX = anchorBounds.left + popupMargin.left
                    offsetY = anchorBounds.bottom + popupMargin.top
                    showBelow = true
                    showAbove = false
                }

                SPopupPositionProvider.Align.TopEnd -> {
                    offsetX = anchorBounds.right - popupContentSize.width - popupMargin.right
                    offsetY = anchorBounds.bottom + popupMargin.top
                    showBelow = true
                    showAbove = false
                }

                SPopupPositionProvider.Align.BottomStart -> {
                    offsetX = anchorBounds.left + popupMargin.left
                    offsetY = anchorBounds.top - popupContentSize.height - popupMargin.bottom
                    showBelow = false
                    showAbove = true
                }

                SPopupPositionProvider.Align.BottomEnd -> {
                    offsetX = anchorBounds.right - popupContentSize.width - popupMargin.right
                    offsetY = anchorBounds.top - popupContentSize.height - popupMargin.bottom
                    showBelow = false
                    showAbove = true
                }

                else -> {
                    // 兜底逻辑：与sDropdownPositionProvider相同
                    offsetX =
                        if (alignment.resolve(layoutDirection) == SPopupPositionProvider.Align.End) {
                            anchorBounds.right - popupContentSize.width - popupMargin.right
                        } else {
                            anchorBounds.left + popupMargin.left
                        }
                    val spaceBelow = windowBounds.bottom - anchorBounds.bottom
                    val spaceAbove = anchorBounds.top - windowBounds.top
                    if (spaceBelow > popupContentSize.height) {
                        offsetY = anchorBounds.bottom + popupMargin.bottom
                        showBelow = true
                        showAbove = false
                    } else if (spaceAbove > popupContentSize.height) {
                        offsetY = anchorBounds.top - popupContentSize.height - popupMargin.top
                        showBelow = false
                        showAbove = true
                    } else {
                        offsetY =
                            anchorBounds.top + anchorBounds.height / 2 - popupContentSize.height / 2
                        showBelow = false
                        showAbove = false
                    }
                }
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

        override fun getMargins(): PaddingValues = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
    }
}

// =====================================================================
// 布局位置描述
// =====================================================================

/**
 * 描述弹窗相对于其锚点的放置方式。
 * 用于驱动方向性揭示动画和变换原点。
 */
@Immutable
data class SPopupLayoutPosition(
    val showBelow: Boolean,     // 弹窗是否显示在锚点下方
    val showAbove: Boolean,     // 弹窗是否显示在锚点上方
    val isRightAligned: Boolean,// 弹窗是否右对齐（与锚点右侧对齐）
)

/**
 * 弹窗的解析布局信息。
 * 由 [rememberSListPopupLayoutInfo] 计算并记忆。
 */
@Immutable
data class SListPopupLayoutInfo(
    val windowBounds: IntRect,              // 窗口安全区域边界
    val popupMargin: IntRect,               // 弹窗的额外边距（像素）
    val effectiveTransformOrigin: TransformOrigin, // 窗口坐标系下的变换原点（用于缩放动画）
    val localTransformOrigin: TransformOrigin,     // 本地坐标系下的变换原点（用于graphicsLayer）
    val popupLayoutPosition: SPopupLayoutPosition,  // 弹窗的放置方向
)

// =====================================================================
// rememberSListPopupLayoutInfo - 计算弹窗布局信息
// =====================================================================

/**
 * 计算并记忆弹窗的布局信息。
 * 根据锚点位置、内容大小、对齐方式等计算弹窗应该显示的位置。
 *
 * @param alignment 弹窗相对于窗口的对齐方式
 * @param popupPositionProvider 弹窗位置提供者
 * @param parentBounds 锚点（父组件）在窗口坐标系中的边界
 * @param popupContentSize 弹窗内容的测量大小
 */
@Composable
fun rememberSListPopupLayoutInfo(
    alignment: SPopupPositionProvider.Align,
    popupPositionProvider: SPopupPositionProvider,
    parentBounds: IntRect,
    popupContentSize: IntSize,
): SListPopupLayoutInfo {
    val density = LocalDensity.current
    val windowInfo = LocalWindowInfo.current
    val layoutDirection = LocalLayoutDirection.current
    val displayCutout = WindowInsets.displayCutout
    val statusBars = WindowInsets.statusBars
    val navigationBars = WindowInsets.navigationBars
    val captionBar = WindowInsets.captionBar

    // 计算弹窗边距（像素）
    val margins = popupPositionProvider.getMargins()
    val popupMargin = remember(layoutDirection, density, margins) {
        with(density) {
            IntRect(
                left = margins.calculateLeftPadding(layoutDirection).roundToPx(),
                top = margins.calculateTopPadding().roundToPx(),
                right = margins.calculateRightPadding(layoutDirection).roundToPx(),
                bottom = margins.calculateBottomPadding().roundToPx(),
            )
        }
    }

    val containerSize = windowInfo.containerSize

    // 计算窗口安全区域边界（排除刘海、状态栏、导航栏等）
    val windowBounds = remember(
        layoutDirection,
        density,
        displayCutout,
        statusBars,
        navigationBars,
        captionBar,
        containerSize,
    ) {
        with(density) {
            IntRect(
                left = displayCutout.getLeft(this, layoutDirection),
                top = statusBars.getTop(this),
                right = containerSize.width - displayCutout.getRight(this, layoutDirection),
                bottom = containerSize.height - navigationBars.getBottom(this) - captionBar.getBottom(
                    this
                ),
            )
        }
    }

    // 预测变换原点（在弹窗未测量时使用）
    val predictedTransformOrigin =
        remember(alignment, popupMargin, parentBounds, layoutDirection, containerSize) {
            val xInWindow = when (alignment.resolve(layoutDirection)) {
                SPopupPositionProvider.Align.End,
                SPopupPositionProvider.Align.TopEnd,
                SPopupPositionProvider.Align.BottomEnd,
                    -> parentBounds.right - popupMargin.right

                else -> parentBounds.left + popupMargin.left
            }
            val yInWindow = when (alignment.resolve(layoutDirection)) {
                SPopupPositionProvider.Align.BottomEnd, SPopupPositionProvider.Align.BottomStart ->
                    parentBounds.top - popupMargin.bottom

                else ->
                    parentBounds.bottom + popupMargin.bottom
            }
            sSafeTransformOrigin(
                xInWindow / containerSize.width.toFloat(),
                yInWindow / containerSize.height.toFloat(),
            )
        }

    // 计算弹窗位置和展开方向（由 positionProvider 一次性返回）
    val positionResult = remember(
        popupContentSize,
        windowBounds,
        parentBounds,
        alignment,
        layoutDirection,
        popupMargin,
        popupPositionProvider,
    ) {
        if (popupContentSize == IntSize.Zero) {
            SPopupPositionResult(IntOffset.Zero, showBelow = true, showAbove = false)
        } else {
            popupPositionProvider.calculatePosition(
                parentBounds,
                windowBounds,
                layoutDirection,
                popupContentSize,
                popupMargin,
                alignment,
            )
        }
    }
    val calculatedOffset = positionResult.offset

    // 解析弹窗的放置方向和本地变换原点
    val (popupLayoutPosition, localTransformOrigin) = remember(
        popupContentSize,
        calculatedOffset,
        positionResult,
        layoutDirection,
        alignment,
    ) {
        if (popupContentSize == IntSize.Zero) {
            val isRightAligned = when (alignment.resolve(layoutDirection)) {
                SPopupPositionProvider.Align.End,
                SPopupPositionProvider.Align.TopEnd,
                SPopupPositionProvider.Align.BottomEnd,
                    -> true

                else -> false
            }
            SPopupLayoutPosition(
                showBelow = true,
                showAbove = false,
                isRightAligned = isRightAligned
            ) to
                    TransformOrigin(if (isRightAligned) 1f else 0f, 0f)
        } else {
            resolveSPopupAnchors(
                positionResult,
                calculatedOffset,
                popupContentSize,
                parentBounds,
                alignment,
                layoutDirection
            )
        }
    }

    // 计算有效的变换原点（窗口坐标系，用于缩放动画的pivot）
    val effectiveTransformOrigin = remember(
        popupContentSize,
        calculatedOffset,
        positionResult,
        containerSize,
        predictedTransformOrigin,
        layoutDirection,
        alignment,
    ) {
        if (popupContentSize == IntSize.Zero) {
            predictedTransformOrigin
        } else {
            val isRightAligned = when (alignment.resolve(layoutDirection)) {
                SPopupPositionProvider.Align.End,
                SPopupPositionProvider.Align.TopEnd,
                SPopupPositionProvider.Align.BottomEnd,
                    -> true

                else -> false
            }
            val cornerX = if (isRightAligned) {
                (calculatedOffset.x + popupContentSize.width).toFloat()
            } else {
                calculatedOffset.x.toFloat()
            }

            val cornerY = when {
                positionResult.showBelow -> calculatedOffset.y.toFloat()
                positionResult.showAbove -> (calculatedOffset.y + popupContentSize.height).toFloat()
                else -> (calculatedOffset.y + popupContentSize.height / 2f)
            }

            sSafeTransformOrigin(
                cornerX / containerSize.width.toFloat(),
                cornerY / containerSize.height.toFloat(),
            )
        }
    }

    return SListPopupLayoutInfo(
        windowBounds = windowBounds,
        popupMargin = popupMargin,
        effectiveTransformOrigin = effectiveTransformOrigin,
        localTransformOrigin = localTransformOrigin,
        popupLayoutPosition = popupLayoutPosition,
    )
}

// =====================================================================
// rememberSDynamicCornerRadiusShape - 动态圆角形状
// =====================================================================

/**
 * 创建一个圆角随动画进度反向放大的 Shape。
 *
 * 在弹窗缩放过程中，为保持视觉圆角不变，圆角需要按缩放比例反向放大
 *（与 sPopupClipReveal 的圆角补偿逻辑一致）。
 *
 * 每帧 createOutline 时都会重新读取 fractionProgress()，从而动态更新圆角。
 *
 * @param fractionProgress 提供当前动画进度（0→1）
 * @param cornerRadius 基准圆角
 */
@Composable
fun rememberSDynamicCornerRadiusShape(
    fractionProgress: () -> Float,
    cornerRadius: Dp,
): Shape = remember {
    object : Shape {
        override fun createOutline(
            size: androidx.compose.ui.geometry.Size,
            layoutDirection: LayoutDirection,
            density: Density,
        ): Outline {
            val fraction = fractionProgress().coerceIn(0f, 1f)
            val avgScale = 0.24f + 0.76f * fraction
            val scaledCornerRadius = cornerRadius / avgScale
            return ContinuousRoundedRectangle(scaledCornerRadius).createOutline(
                size,
                layoutDirection,
                density
            )
        }
    }
}

// =====================================================================
// SListPopupContent - 弹窗内容容器
// =====================================================================

/**
 * 弹窗内容容器，提供缩放、淡入淡出和方向性裁剪揭示效果。
 *
 * 这是弹窗的视觉容器，负责：
 * - 缩放动画：从0.24倍缩放到1倍
 * - 透明度动画：从0到1
 * - 裁剪揭示：根据弹窗显示方向，从锚点位置逐渐揭示内容
 *
 * @param popupContentSize 弹窗内容的当前大小
 * @param onPopupContentSizeChange 内容大小变化时的回调
 * @param fractionProgress 提供当前缩放/裁剪进度（0→1）
 * @param alphaProgress 提供当前透明度（0→1）
 * @param popupLayoutPosition 弹窗的放置方向
 * @param localTransformOrigin 本地坐标系下的变换原点
 * @param modifier 修饰符
 * @param liquidGlassBackdrop 液体玻璃模糊效果的Backdrop
 * @param revealLimitHeight 揭示限制高度
 * @param content 弹窗内容
 */
@Composable
fun SListPopupContent(
    popupContentSize: IntSize,
    onPopupContentSizeChange: (IntSize) -> Unit,
    fractionProgress: () -> Float,
    alphaProgress: () -> Float,
    popupLayoutPosition: SPopupLayoutPosition,
    localTransformOrigin: TransformOrigin,
    modifier: Modifier = Modifier,
    liquidGlassBackdrop: com.kyant.backdrop.Backdrop? = null,
    revealLimitHeight: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    // ============================================
    // 圆角值 - 修改这里可以改变弹窗的圆角
    // ============================================
    val cornerRadius = 25.dp
    val safeBackdrop = rememberSafeBackdrop(liquidGlassBackdrop, "SListPopupContent")
    val backgroundColor = MiuixTheme.colorScheme.surfaceContainer
    val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f

    val shadowPadding = 24.dp

    // 阴影渐变动画：进入时升到 0.78 显示，退出时降到 0.99 消失
    val shadowAlphaState = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        var prevFraction = fractionProgress()
        var shadowVisible = false
        var animationJob: Job? = null
        snapshotFlow { fractionProgress() }
            .collect { current ->
                val isEntering = current >= prevFraction
                prevFraction = current
                val newVisible = if (isEntering) {
                    current >= 0.78f
                } else {
                    current >= 0.99f
                }
                if (newVisible != shadowVisible) {
                    shadowVisible = newVisible
                    animationJob?.cancel()
                    animationJob = launch {
                        if (newVisible) {
                            // 进入：渐变出现
                            shadowAlphaState.animateTo(1f, tween(200))
                        } else {
                            // 退出：若进入动画未播完则立即消失，否则快速渐出
                            if (shadowAlphaState.value >= 1f) {
                                shadowAlphaState.animateTo(0f, tween(50))
                            } else {
                                shadowAlphaState.snapTo(0f)
                            }
                        }
                    }
                }
            }
    }

    Box(
        modifier = modifier
            .padding(shadowPadding)
            .drawBehind {
                val shadowAlpha = shadowAlphaState.value
                if (shadowAlpha <= 0f) return@drawBehind
                val baseAlpha = (32 * shadowAlpha).toInt().coerceIn(0, 255)
                val shadowColor = android.graphics.Color.argb(baseAlpha, 0, 0, 0)
                val blurRadius = 16f * density
                val cornerRadiusPx = cornerRadius.toPx()
                val nativePath = android.graphics.Path().apply {
                    addRoundRect(
                        0f, 0f, size.width, size.height,
                        cornerRadiusPx, cornerRadiusPx,
                        android.graphics.Path.Direction.CW
                    )
                }
                val paint = android.graphics.Paint().apply {
                    color = shadowColor
                    maskFilter = BlurMaskFilter(
                        blurRadius.coerceAtLeast(0.1f),
                        BlurMaskFilter.Blur.NORMAL
                    )
                }
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawPath(nativePath, paint)
                }
            }
    ) {
        Box(
            modifier = Modifier
                .onGloballyPositioned { coordinates ->
                    val size = coordinates.size
                    if (popupContentSize != size) onPopupContentSizeChange(size)
                }
                .graphicsLayer {
                    // 缩放动画：先快后慢的曲线效果
                    val fraction = fractionProgress()
                    // X轴：从0.24倍缩放到1.0倍
                    val scaleXL = 0.24f + 0.76f * fraction
                    // Y轴：从0.24倍缩放到1.0倍
                    val scaleXY = 0.24f + 0.76f * fraction
                    scaleX = scaleXL
                    scaleY = scaleXY
                    alpha = alphaProgress()
                    // 缩放中心点：从原位置移动到弹窗中心，先快后慢
                    val targetOrigin = TransformOrigin(0.5f, 0.5f)
                    transformOrigin = TransformOrigin(
                        pivotFractionX = localTransformOrigin.pivotFractionX + (targetOrigin.pivotFractionX - localTransformOrigin.pivotFractionX) * fraction,
                        pivotFractionY = localTransformOrigin.pivotFractionY + (targetOrigin.pivotFractionY - localTransformOrigin.pivotFractionY) * fraction
                    )
                }
                // 方向性裁剪揭示效果（位于 blur 外层，裁掉模糊产生的圆角溢出）
                .sPopupClipReveal(
                    fractionProgress = fractionProgress,
                    popupLayoutPosition = popupLayoutPosition,
                    cornerRadius = cornerRadius,
                    squircleEnabled = isSquircleEnabled(),
                    revealLimitHeightPx = with(LocalDensity.current) { revealLimitHeight.toPx() },
                )
                // 模糊效果：进入时从8dp变小到0，退出时从0变大到8dp
                .blur(radius = (8f * (1f - fractionProgress())).dp)
                .then(
                    if (safeBackdrop != null && android.os.Build.VERSION.SDK_INT >= 33) {
                        Modifier.drawBackdrop(
                            backdrop = safeBackdrop,
                            shape = {
                                // 圆角随缩放反向放大，保持视觉圆角不变（与 sPopupClipReveal 一致）
                                val fraction = fractionProgress().coerceIn(0f, 1f)
                                val avgScale = 0.24f + 0.76f * fraction
                                val scaledCornerRadius = cornerRadius / avgScale
                                ContinuousRoundedRectangle(scaledCornerRadius)
                            },
                            effects = {
                                vibrancy()
                                blur(24.dp.toPx())
                            },
                            highlight = null,
                            shadow = null,
                            onDrawSurface = {
                                drawRect(color = backgroundColor.copy(alpha = if (isDark) 0.8f else 0.72f))
                            }
                        )
                    } else {
                        Modifier.background(backgroundColor)
                    }
                )
                .edgeLight(
                    shape = rememberSDynamicCornerRadiusShape(fractionProgress, cornerRadius),
                    edgeLight = rememberDefaultEdgeLight()
                ),
        ) {
            content()
        }
    }
}

// =====================================================================
// sPopupClipReveal - 方向性裁剪揭示修饰符
// =====================================================================

/**
 * 方向性裁剪揭示修饰符。
 *
 * 在弹窗进入/退出时，可见区域会沿着弹窗的生成方向逐渐展开：
 * - 显示在锚点下方：从顶部向下展开
 * - 显示在锚点上方：从底部向上展开
 * - 居中显示：从中心向两侧展开
 *
 * 使用 ContinuousRoundedRectangle 形状来保持四角圆角。
 * 当squircleEnabled为false时，会退化为普通的圆角矩形。
 */
fun Modifier.sPopupClipReveal(
    fractionProgress: () -> Float,
    popupLayoutPosition: SPopupLayoutPosition,
    cornerRadius: Dp,
    squircleEnabled: Boolean,
    revealLimitHeightPx: Float = 0f,
): Modifier = drawWithCache {
    val path = Path()
    val showBelow = popupLayoutPosition.showBelow
    val showAbove = popupLayoutPosition.showAbove
    onDrawWithContent {
        // 限制进度值在0~1之间（弹簧动画可能会超出）
        val progress = fractionProgress().coerceIn(0f, 1f)
        if (progress <= 0f) return@onDrawWithContent

        val height = size.height
        // 当设置了 revealLimitHeightPx 且朝上/朝下时，从限制高度展开到完整高度
        val visibleHeight = if (revealLimitHeightPx > 0f && (showBelow || showAbove)) {
            (revealLimitHeightPx + (height - revealLimitHeightPx) * progress).coerceIn(0f, height)
        } else {
            height
        }
        if (visibleHeight <= 0f) return@onDrawWithContent

        // 计算裁剪起始位置
        val clipStart = when {
            showBelow -> 0f                        // 朝下：从顶部向下展开
            showAbove -> height - visibleHeight   // 朝上：从底部向上展开
            else -> height * (0.5f - 0.5f * progress) // 居中：从中心向两侧展开
        }

        path.rewind()
        // 圆角在动画过程中保持不变：当弹窗缩小时，圆角需要放大以抵消缩放
        val fraction = fractionProgress().coerceIn(0f, 1f)
        val scaleXL = 0.24f + 0.76f * fraction
        val scaleXY = 0.24f + 0.76f * fraction
        // 使用两个轴缩放的平均值来计算圆角，保持圆角不变
        val avgScale = (scaleXL + scaleXY) / 2f
        val scaledCornerRadius = cornerRadius / avgScale
        val roundedRectShape = ContinuousRoundedRectangle(scaledCornerRadius)
        val outline = roundedRectShape.createOutline(
            size = Size(size.width, visibleHeight),
            layoutDirection = layoutDirection,
            density = this@drawWithCache
        )
        when (outline) {
            is Outline.Rounded -> path.addRoundRect(outline.roundRect)
            is Outline.Generic -> path.addPath(outline.path)
            is Outline.Rectangle -> path.addRect(outline.rect)
        }
        if (clipStart == 0f) {
            clipPath(path) {
                this@onDrawWithContent.drawContent()
            }
        } else {
            translate(top = clipStart) {
                clipPath(path) {
                    translate(top = -clipStart) {
                        this@onDrawWithContent.drawContent()
                    }
                }
            }
        }
    }
}

private fun Color.luminance(): Float {
    return 0.299f * red + 0.587f * green + 0.114f * blue
}
