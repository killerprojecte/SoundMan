package hk.uwu.soundman.ui.basic

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastFirst
import androidx.compose.ui.util.lerp
import androidx.compose.ui.zIndex
import hk.uwu.soundman.ui.utils.isAppDarkTheme
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.anim.folmeSpring
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.roundToInt

// ==================== State ====================

@Stable
class CollapsibleTopAppBarState(
    initialHeightOffsetLimit: Float = -Float.MAX_VALUE,
    initialHeightOffset: Float = 0f,
    initialContentOffset: Float = 0f,
) {
    var heightOffsetLimit = initialHeightOffsetLimit

    var heightOffset: Float
        get() = _heightOffset.floatValue
        set(newOffset) {
            _heightOffset.floatValue = newOffset.coerceIn(
                minimumValue = heightOffsetLimit,
                maximumValue = 0f
            )
        }

    var contentOffset by mutableFloatStateOf(initialContentOffset)

    var showButtonShadow = false

    val collapsedFraction: Float
        get() = if (heightOffsetLimit != 0f) {
            heightOffset / heightOffsetLimit
        } else {
            0f
        }

    private var _heightOffset = mutableFloatStateOf(initialHeightOffset)

    companion object {
        val Saver: Saver<CollapsibleTopAppBarState, *> = listSaver(
            save = { listOf(it.heightOffsetLimit, it.heightOffset, it.contentOffset) },
            restore = {
                CollapsibleTopAppBarState(
                    initialHeightOffsetLimit = it[0],
                    initialHeightOffset = it[1],
                    initialContentOffset = it[2],
                )
            },
        )
    }
}

@Composable
fun rememberCollapsibleTopAppBarState(
    initialHeightOffsetLimit: Float = -Float.MAX_VALUE,
    initialHeightOffset: Float = 0f,
    initialContentOffset: Float = 0f,
): CollapsibleTopAppBarState = rememberSaveable(saver = CollapsibleTopAppBarState.Saver) {
    CollapsibleTopAppBarState(initialHeightOffsetLimit, initialHeightOffset, initialContentOffset)
}

// ==================== ScrollBehavior ====================

@Stable
class SharedScrollBehavior(
    val state: CollapsibleTopAppBarState,
    val snapAnimationSpec: AnimationSpec<Float> = folmeSpring(damping = 1.0f, response = 0.3f),
    val flingAnimationSpec: DecayAnimationSpec<Float> = exponentialDecay(frictionMultiplier = 1f),
) {
    var currentHeightPx by mutableFloatStateOf(0f)

    var postCollapseScrollOffset by mutableFloatStateOf(0f)
        internal set

    private var connection: NestedScrollConnection? = null

    /** 带动画收起标题栏 */
    suspend fun collapse() {
        val target = state.heightOffsetLimit
        if (target == 0f) return
        val animatable = Animatable(state.heightOffset)
        animatable.animateTo(target, snapAnimationSpec) {
            state.heightOffset = value
        }
    }

    /** 带动画展开标题栏 */
    suspend fun expand() {
        val animatable = Animatable(state.heightOffset)
        animatable.animateTo(0f, snapAnimationSpec) {
            state.heightOffset = value
        }
    }

    val nestedScrollConnection: NestedScrollConnection
        get() {
            if (connection == null) {
                connection = object : NestedScrollConnection {
                    override fun onPreScroll(
                        available: Offset,
                        source: NestedScrollSource
                    ): Offset {
                        if (available.y > 0) {
                            // 向上滚动时不重置 postCollapseScrollOffset
                            // 只在回弹结束时由 LaunchedEffect 重置
                            return Offset.Zero
                        }

                        val currentHeightOffset = state.heightOffset
                        val heightOffsetLimit = state.heightOffsetLimit

                        // 当没有大标题时（heightOffsetLimit == -1f），或者 bar 已完全折叠时，
                        // 更新 postCollapseScrollOffset 用于按钮材质/阴影动画
                        if (currentHeightOffset <= heightOffsetLimit || heightOffsetLimit == -1f) {
                            postCollapseScrollOffset += -available.y
                            return Offset.Zero
                        }

                        val maxConsumable = currentHeightOffset - heightOffsetLimit
                        val consumed = maxConsumable.coerceAtMost(-available.y)

                        state.heightOffset = currentHeightOffset - consumed

                        return if (consumed > 0f) Offset(0f, -consumed) else Offset.Zero
                    }

                    override fun onPostScroll(
                        consumed: Offset,
                        available: Offset,
                        source: NestedScrollSource,
                    ): Offset {
                        state.contentOffset += consumed.y

                        if (available.y > 0f) {
                            val oldHeightOffset = state.heightOffset
                            state.heightOffset = (oldHeightOffset + available.y).coerceAtMost(0f)
                            val consumedByBar = state.heightOffset - oldHeightOffset
                            return Offset(0f, consumedByBar)
                        }

                        return Offset.Zero
                    }

                    override suspend fun onPostFling(
                        consumed: Velocity,
                        available: Velocity
                    ): Velocity {
                        if (available.y > 0) {
                            state.contentOffset = 0f
                        }
                        return settleAppBar(
                            state,
                            available.y,
                            flingAnimationSpec,
                            snapAnimationSpec
                        )
                    }
                }
            }
            return connection!!
        }
}

/**
 * Settles the app bar to a stable state (fully expanded or collapsed) by animating
 * its height offset after a fling gesture.
 */
private suspend fun settleAppBar(
    state: CollapsibleTopAppBarState,
    velocity: Float,
    flingAnimationSpec: DecayAnimationSpec<Float>?,
    snapAnimationSpec: AnimationSpec<Float>?,
): Velocity {
    if (state.collapsedFraction < 0.01f || state.collapsedFraction == 1f) {
        return Velocity.Zero
    }
    var remainingVelocity = velocity

    if (flingAnimationSpec != null && abs(velocity) > 1f) {
        var lastValue = 0f
        AnimationState(initialValue = 0f, initialVelocity = velocity).animateDecay(
            flingAnimationSpec,
        ) {
            val delta = value - lastValue
            val initialHeightOffset = state.heightOffset
            state.heightOffset = initialHeightOffset + delta
            val consumed = abs(initialHeightOffset - state.heightOffset)
            lastValue = value
            remainingVelocity = this.velocity
            if (abs(delta - consumed) > 0.5f) this.cancelAnimation()
        }
    }

    if (snapAnimationSpec != null) {
        if (state.heightOffset < 0 && state.heightOffset > state.heightOffsetLimit) {
            AnimationState(initialValue = state.heightOffset).animateTo(
                if (state.collapsedFraction < 0.5f) {
                    0f
                } else {
                    state.heightOffsetLimit
                },
                animationSpec = snapAnimationSpec,
            ) {
                state.heightOffset = value
            }
        }
    }
    return Velocity(0f, velocity - remainingVelocity)
}

@Composable
fun rememberSharedScrollBehavior(
    state: CollapsibleTopAppBarState = rememberCollapsibleTopAppBarState(),
): SharedScrollBehavior = remember(state) {
    SharedScrollBehavior(state)
}

// ==================== Defaults ====================

object CollapsibleTopAppBarDefaults {
    val CollapsedHeight = 52.dp
    val TitlePadding = 26.dp
    val TitleWidthFraction = 0.9f
    val MaxLargeTitleBlur = 6.dp
    val MaxSmallTitleBlur = 6.dp
}

/**
 * 折叠标题栏的可测试滚动消费计算。
 *
 * 注意：当前 nestedScrollConnection 已对齐 NexioSchedule 的内联实现，不再使用此工具。
 * 保留以维持已有测试的编译兼容性。
 */
internal object CollapsibleScrollConsumption {
    fun consumeHeightOffset(
        currentOffset: Float,
        offsetLimit: Float,
        availableOffset: Float,
    ): Float {
        require(currentOffset.isFinite()) { "currentOffset must be finite" }
        require(offsetLimit.isFinite() && offsetLimit <= 0f) {
            "offsetLimit must be finite and non-positive"
        }
        require(availableOffset.isFinite()) { "availableOffset must be finite" }
        val clampedCurrent = currentOffset.coerceIn(offsetLimit, 0f)
        val nextOffset = (clampedCurrent + availableOffset).coerceIn(offsetLimit, 0f)
        return nextOffset - clampedCurrent
    }
}

// ==================== Component ====================

@Composable
fun CollapsibleTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    largeTitle: String = title,
    showLargeTitle: Boolean? = null,
    showSmallTitle: Boolean? = null,
    showShadow: Boolean? = null,
    showGradientOverlay: Boolean = true,
    scrollBehavior: SharedScrollBehavior? = null,
    contentPadding: (Dp) -> Unit = {},
    /** 左侧操作区；参数表示当前滚动后的材质/阴影强度。 */
    startAction: @Composable ((backdropAlpha: Float, shadowAlpha: Float) -> Unit)? = null,
    /** 右侧操作区；参数表示当前滚动后的材质/阴影强度。 */
    endAction: @Composable ((backdropAlpha: Float, shadowAlpha: Float) -> Unit)? = null,
    gradientMaskHeight: Dp = CollapsibleTopAppBarDefaults.CollapsedHeight + 70.dp,
    onAlphaChanged: (backdropAlpha: Float, shadowAlpha: Float) -> Unit = { _, _ -> },
    /** 仅供实色样式使用的容器颜色；玻璃样式由外层材质组件绘制。 */
    containerColor: Color = Color.Transparent,
) {
    val state = scrollBehavior?.state

    val isTablet = LocalConfiguration.current.screenWidthDp >= 600
    val effectiveShowLargeTitle = showLargeTitle ?: !isTablet

    val overScrollState = LocalOverScrollState.current


    val scrolledOffset = remember(scrollBehavior) {
        { scrollBehavior?.state?.heightOffset ?: 0f }
    }
    val largeTitleAlpha = remember(scrollBehavior) {
        {
            val frac = scrollBehavior?.state?.collapsedFraction ?: 0f
            1f - (frac * 2.5f).coerceIn(0f, 1f)
        }
    }
    val largeTitleBlur: () -> Dp = remember(scrollBehavior) {
        {
            val frac = scrollBehavior?.state?.collapsedFraction ?: 0f
            Dp((frac * 4f).coerceIn(0f, 1f) * CollapsibleTopAppBarDefaults.MaxLargeTitleBlur.value)
        }
    }
    val updateHeightOffsetLimit = remember(scrollBehavior) {
        { height: Int ->
            scrollBehavior?.state?.let { s ->
                val limit = -height.toFloat()
                if (s.heightOffsetLimit != limit) s.heightOffsetLimit = limit
            }
            Unit
        }
    }

    // 没有大标题时，设置 heightOffsetLimit = -1 表示 bar 已完全折叠，不消费滚动
    // 设为 -1 而非 0，确保 onPreScroll 中 heightOffset(0) > heightOffsetLimit(-1) 为 true，
    // 这样向上滚动时能正确重置 postCollapseScrollOffset
    LaunchedEffect(effectiveShowLargeTitle, scrollBehavior) {
        if (!effectiveShowLargeTitle) {
            scrollBehavior?.state?.heightOffsetLimit = -1f
        }
    }

    val smallTitleVisible by remember(state, effectiveShowLargeTitle, showSmallTitle) {
        derivedStateOf {
            // 外部显式控制时使用外部值
            showSmallTitle
            // 没有大标题时，小标题始终显示
                ?: if (!effectiveShowLargeTitle) true
                // 默认：折叠到一定程度时显示
                else (state?.collapsedFraction ?: 0f) >= 0.45f
        }
    }
    val density = LocalDensity.current
    val scrollShadowThresholdPx = with(density) { 10.dp.toPx() }
    val showButtonShadow =
        remember(scrollBehavior, showShadow, effectiveShowLargeTitle, overScrollState.offset) {
            derivedStateOf {
                if (showShadow != null) return@derivedStateOf showShadow
                val contentOffset = scrollBehavior?.state?.contentOffset ?: 0f
                val overscrollOffset = overScrollState.offset
                // 正常滚动检测：内容滚动超过 10dp 即触发（阈值调低，更易触发）
                if (contentOffset < -scrollShadowThresholdPx) return@derivedStateOf true
                // 向下回弹检测：内容在顶部且向下回弹（overscrollOffset < 0）
                if (contentOffset >= 0f && overscrollOffset < 0f) return@derivedStateOf true
                false
            }
        }
    val shadowAlpha = remember { Animatable(if (showButtonShadow.value) 1f else 0f) }
    val backdropAlpha = remember { Animatable(if (showButtonShadow.value) 1f else 0f) }
    LaunchedEffect(showButtonShadow.value) {
        val target = if (showButtonShadow.value) 1f else 0f
        val spec = if (showButtonShadow.value) {
            folmeSpring(damping = 1.0f, response = 0.6f)
        } else {
            folmeSpring<Float>(damping = 1.0f, response = 0.4f)
        }
        launch { shadowAlpha.animateTo(target, spec) }
        launch { backdropAlpha.animateTo(target, spec) }
    }
    LaunchedEffect(showButtonShadow.value) {
        state?.showButtonShadow = showButtonShadow.value
    }
    val smallTitleAlpha = remember { Animatable(if (smallTitleVisible) 1f else 0f) }
    val smallTitleTranslationY = remember { Animatable(if (smallTitleVisible) 0f else 20f) }
    val smallTitleBlur =
        remember { Animatable(if (smallTitleVisible) 0f else CollapsibleTopAppBarDefaults.MaxSmallTitleBlur.value) }

    LaunchedEffect(smallTitleVisible) {
        if (smallTitleVisible) {
            val showSpec = folmeSpring<Float>(damping = 1.0f, response = 0.3f)
            launch { smallTitleAlpha.animateTo(1f, showSpec) }
            launch { smallTitleTranslationY.animateTo(0f, showSpec) }
            launch { smallTitleBlur.animateTo(0f, showSpec) }
        } else {
            val hideSpec = folmeSpring<Float>(damping = 1.0f, response = 0.15f)
            launch { smallTitleAlpha.animateTo(0f, hideSpec) }
            launch { smallTitleTranslationY.animateTo(20f, hideSpec) }
            launch {
                smallTitleBlur.animateTo(
                    CollapsibleTopAppBarDefaults.MaxSmallTitleBlur.value,
                    hideSpec
                )
            }
        }
    }

    // 渐变遮罩动画
    val gradientAlpha = remember { Animatable(0f) }
    LaunchedEffect(showButtonShadow.value) {
        val target = if (showButtonShadow.value) 1f else 0f
        val spec = if (showButtonShadow.value) {
            folmeSpring(damping = 1.0f, response = 0.6f)
        } else {
            folmeSpring<Float>(damping = 1.0f, response = 0.4f)
        }
        gradientAlpha.animateTo(target, spec)
    }
    val gradientColor = if (isAppDarkTheme()) Color.Black else Color.White

    LaunchedEffect(backdropAlpha.value, shadowAlpha.value) {
        onAlphaChanged(backdropAlpha.value, shadowAlpha.value)
    }

    Box(modifier = Modifier.background(containerColor)) {
        // 渐变遮罩（超出顶栏范围）
        if (showGradientOverlay) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(gradientMaskHeight)
                    .graphicsLayer { alpha = gradientAlpha.value }
                    .drawBehind {
                        drawRect(
                            brush = Brush.verticalGradient(
                                0f to gradientColor.copy(alpha = 0.8f),
                                0.7f to gradientColor.copy(alpha = 0.4f),
                                0.9f to gradientColor.copy(alpha = 0.15f),
                                1f to Color.Transparent
                            )
                        )
                    }
            )
        }

        Layout(
            {
                // 左侧自定义 Composable
                if (startAction != null) {
                    Box(
                        Modifier
                            .layoutId("startAction")
                            .zIndex(2f),
                    ) {
                        startAction(backdropAlpha.value, shadowAlpha.value)
                    }
                }
                Box(
                    Modifier
                        .layoutId("title")
                        .padding(horizontal = CollapsibleTopAppBarDefaults.TitlePadding)
                        .graphicsLayer {
                            alpha = smallTitleAlpha.value
                            translationY = smallTitleTranslationY.value
                        }
                        .blur(Dp(smallTitleBlur.value)),
                ) {
                    Text(
                        text = title,
                        color = MiuixTheme.colorScheme.onSurface,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Medium,
                        overflow = TextOverflow.Ellipsis,
                        softWrap = false,
                    )
                }
                // 右侧自定义 Composable
                if (endAction != null) {
                    Box(
                        Modifier
                            .layoutId("endAction")
                            .zIndex(2f),
                    ) {
                        endAction(backdropAlpha.value, shadowAlpha.value)
                    }
                }
                if (effectiveShowLargeTitle) {
                    Box(
                        Modifier
                            .layoutId("largeTitle")
                            .padding(top = CollapsibleTopAppBarDefaults.CollapsedHeight)
                            .padding(horizontal = CollapsibleTopAppBarDefaults.TitlePadding)
                            .graphicsLayer { alpha = largeTitleAlpha() }
                            .blur(largeTitleBlur()),
                    ) {
                        Column(
                            modifier = Modifier.onSizeChanged { updateHeightOffsetLimit(it.height) },
                        ) {
                            Text(
                                text = largeTitle,
                                color = MiuixTheme.colorScheme.onSurface,
                                fontSize = 30.sp,
                                fontWeight = FontWeight.Normal,
                            )
                        }
                    }
                }
            },
            modifier = modifier
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top))
                .onSizeChanged { size ->
                    scrollBehavior?.currentHeightPx = size.height.toFloat()
                    contentPadding(with(density) { size.height.toDp() })
                },
        ) { measurables, constraints ->
            val backButtonPlaceable = measurables
                .firstOrNull { it.layoutId == "backButton" }
                ?.measure(constraints.copy(minWidth = 0, minHeight = 0))

            val startActionPlaceable = measurables
                .firstOrNull { it.layoutId == "startAction" }
                ?.measure(constraints.copy(minWidth = 0, minHeight = 0))

            val endActionPlaceable = measurables
                .firstOrNull { it.layoutId == "endAction" }
                ?.measure(constraints.copy(minWidth = 0, minHeight = 0))

            val maxTitleWidth = if (constraints.maxWidth == Constraints.Infinity) {
                constraints.maxWidth
            } else {
                constraints.maxWidth
            }
            val titleMaxWidth = if (maxTitleWidth == Constraints.Infinity) {
                maxTitleWidth
            } else {
                (maxTitleWidth * CollapsibleTopAppBarDefaults.TitleWidthFraction).roundToInt()
            }

            val titlePlaceable = measurables
                .fastFirst { it.layoutId == "title" }
                .measure(constraints.copy(minWidth = 0, maxWidth = titleMaxWidth, minHeight = 0))

            val largeTitlePlaceable = if (effectiveShowLargeTitle) {
                measurables
                    .firstOrNull { it.layoutId == "largeTitle" }
                    ?.measure(
                        constraints.copy(
                            minWidth = 0,
                            minHeight = 0,
                            maxHeight = Constraints.Infinity,
                        ),
                    )
            } else null

            val collapsedHeightPx = CollapsibleTopAppBarDefaults.CollapsedHeight.roundToPx()
            val expansion =
                ((largeTitlePlaceable?.height ?: 0) - collapsedHeightPx).coerceAtLeast(0)

            val offset = scrolledOffset()
            val collapseFraction = if (expansion > 0 && !offset.isNaN()) {
                (abs(offset) / expansion.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            val barHeight = lerp(
                start = collapsedHeightPx.toFloat(),
                stop = (collapsedHeightPx + expansion).toFloat(),
                fraction = 1f - collapseFraction,
            ).roundToInt()

            val verticalCenter = collapsedHeightPx / 2

            layout(constraints.maxWidth, barHeight) {
                backButtonPlaceable?.placeRelative(
                    x = with(density) { 16.dp.roundToPx() },
                    y = verticalCenter - backButtonPlaceable.height / 2,
                )

                startActionPlaceable?.placeRelative(
                    x = with(density) { 16.dp.roundToPx() },
                    y = verticalCenter - startActionPlaceable.height / 2,
                )

                titlePlaceable.placeRelative(
                    x = (constraints.maxWidth - titlePlaceable.width) / 2,
                    y = verticalCenter - titlePlaceable.height / 2,
                )

                endActionPlaceable?.placeRelative(
                    x = constraints.maxWidth - with(density) { 16.dp.roundToPx() } - endActionPlaceable.width,
                    y = verticalCenter - endActionPlaceable.height / 2,
                )

                val largeTitleY = if (offset.isNaN()) 0 else offset.roundToInt()
                largeTitlePlaceable?.placeRelative(x = 0, y = largeTitleY)
            }
        }
    }
}
