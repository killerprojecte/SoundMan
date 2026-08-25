/** 自定义模糊底部弹窗 - 支持全区域模糊背景 */
package hk.uwu.soundman.miuix.overlay

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.kyant.capsule.ContinuousRoundedRectangle
import hk.uwu.soundman.ui.basic.LocalOverScrollState
import hk.uwu.soundman.ui.basic.OverScrollState
import hk.uwu.soundman.ui.basic.ProgressiveBlurTopBar
import hk.uwu.soundman.ui.basic.rememberCollapsibleTopAppBarState
import hk.uwu.soundman.ui.basic.rememberSafeBackdrop
import hk.uwu.soundman.ui.basic.rememberSharedScrollBehavior
import hk.uwu.soundman.ui.effects.edgelight.edgeLight
import hk.uwu.soundman.ui.effects.edgelight.rememberDefaultEdgeLight
import hk.uwu.soundman.ui.utils.LocalForcedDarkTheme
import hk.uwu.soundman.ui.utils.rememberAppSettingDark
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.anim.folmeSpring
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.utils.MiuixPopupUtils.Companion.DialogLayout
import kotlin.math.abs

/**
 * 顶栏材质动画值载体：由 BlurBottomSheet 顶栏机制下发，供 startAction/endAction
 * 内部的 LiquidTopBarButton 读取，驱动液态玻璃材质/阴影随滚动渐变。
 */
@Stable
class SheetTopBarMaterial(val backdropAlpha: Float, val shadowAlpha: Float)

val LocalSheetTopBarMaterial = compositionLocalOf { SheetTopBarMaterial(1f, 1f) }

/**
 * 自定义模糊底部弹窗组件，支持全区域（包括标题栏）的模糊背景效果。
 *
 * @param show 是否显示
 * @param title 标题文字
 * @param liquidGlassBackdrop 液态玻璃 backdrop
 * @param blurRadius 模糊半径
 * @param dimBackground 是否压暗背景
 * @param sheetBackgroundColor 弹窗背景颜色，null 则使用默认颜色
 * @param sheetBackgroundAlpha 弹窗背景透明度，null 则使用默认值
 * @param onDismissRequest 关闭回调
 * @param startAction 标题栏左侧操作按钮
 * @param endAction 标题栏右侧操作按钮
 * @param onSheetContentBackdropCreated 弹窗内容 backdrop 创建回调
 * @param skipEnterAnimation 是否跳过进入动画
 * @param content 内容区域
 */
@Composable
fun BlurBottomSheet(
    show: Boolean,
    title: String,
    liquidGlassBackdrop: Backdrop? = null,
    blurRadius: Float = 24f,
    dimBackground: Boolean = false,
    sheetBackgroundColor: Color? = null,
    sheetBackgroundAlpha: Float? = null,
    sheetOffsetDp: Dp = Dp.Unspecified,
    sheetMaxWidth: Dp = Dp.Unspecified,
    onDismissRequest: () -> Unit,
    startAction: @Composable (() -> Unit)? = null,
    endAction: @Composable (() -> Unit)? = null,
    onSheetContentBackdropCreated: ((Backdrop?) -> Unit)? = null,
    skipEnterAnimation: Boolean = false,
    content: @Composable () -> Unit,
) {
    val visibleState = remember { mutableStateOf(show) }
    val sheetContentBackdropHolder = remember { mutableStateOf<Backdrop?>(null) }

    // 显示时立即可见，隐藏时等动画播完再隐藏
    LaunchedEffect(show) {
        if (show) {
            visibleState.value = true
        }
    }

    LaunchedEffect(sheetContentBackdropHolder.value) {
        onSheetContentBackdropCreated?.invoke(sheetContentBackdropHolder.value)
    }

    // 返回手势放在 DialogLayout 外面，确保组合时立即生效
    BackHandler(enabled = show) {
        onDismissRequest()
    }

    DialogLayout(
        visible = visibleState,
        enableWindowDim = false,
        enterTransition = EnterTransition.None,
        exitTransition = ExitTransition.None,
        enableAutoLargeScreen = false,
        renderInRootScaffold = true,
    ) {
        BlurBottomSheetContent(
            show = show,
            visibleState = visibleState,
            title = title,
            liquidGlassBackdrop = liquidGlassBackdrop,
            blurRadius = blurRadius,
            dimBackground = dimBackground,
            sheetBackgroundColor = sheetBackgroundColor,
            sheetBackgroundAlpha = sheetBackgroundAlpha,
            onDismissRequest = onDismissRequest,
            startAction = startAction,
            endAction = endAction,
            sheetContentBackdropHolder = sheetContentBackdropHolder,
            sheetOffsetDp = sheetOffsetDp,
            sheetMaxWidth = sheetMaxWidth,
            skipEnterAnimation = skipEnterAnimation,
            content = content,
        )
    }
}

@Composable
private fun BlurBottomSheetContent(
    show: Boolean,
    visibleState: MutableState<Boolean>,
    title: String,
    liquidGlassBackdrop: Backdrop?,
    blurRadius: Float,
    dimBackground: Boolean = false,
    sheetBackgroundColor: Color? = null,
    sheetBackgroundAlpha: Float? = null,
    sheetOffsetDp: Dp = Dp.Unspecified,
    sheetMaxWidth: Dp = Dp.Unspecified,
    onDismissRequest: () -> Unit,
    startAction: @Composable (() -> Unit)? = null,
    endAction: @Composable (() -> Unit)? = null,
    sheetContentBackdropHolder: MutableState<Backdrop?>? = null,
    skipEnterAnimation: Boolean = false,
    content: @Composable () -> Unit,
) {
    // 弹窗始终跟随应用主题，不受壁纸强制主题影响
    val sheetAppDark = rememberAppSettingDark()
    val sheetAppController = remember(sheetAppDark) {
        ThemeController(if (sheetAppDark) ColorSchemeMode.Dark else ColorSchemeMode.Light)
    }
    CompositionLocalProvider(LocalForcedDarkTheme provides null) {
        MiuixTheme(controller = sheetAppController) {
            val animationProgress =
                remember { Animatable(if (show && skipEnterAnimation) 1f else 0f) }
            val dragOffsetY = remember { Animatable(0f) }
            val density = LocalDensity.current
            val windowInfo = LocalWindowInfo.current
            val coroutineScope = rememberCoroutineScope()
            val sheetHeightPx = remember { mutableIntStateOf(0) }
            val imeInsets = WindowInsets.ime

            val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
            val safeLiquidBackdrop = rememberSafeBackdrop(liquidGlassBackdrop, "BlurBottomSheet")
            val sheetBgColor =
                sheetBackgroundColor ?: if (isDark) Color(0xFF1E1E1E) else Color(0xFFF2F2F2)
            val dismissThresholdPx = with(density) { 150.dp.toPx() }
            val velocityThresholdPx = with(density) { 800.dp.toPx() }

            // 显示/隐藏动画（同时驱动弹窗位移与遮罩透明度，确保二者完全同步）
            LaunchedEffect(show) {
                if (show) {
                    dragOffsetY.snapTo(0f)
                    if (skipEnterAnimation) {
                        animationProgress.snapTo(1f)
                    } else {
                        // 进入动画：使用 CubicBezier 带回弹效果
                        animationProgress.animateTo(
                            targetValue = 1f,
                            animationSpec = tween(
                                durationMillis = 480,
                                easing = CubicBezierEasing(0.34f, 1.12f, 0.3f, 1f)
                            )
                        )
                    }
                } else {
                    // 退出动画
                    animationProgress.animateTo(
                        0f,
                        animationSpec = tween(320, easing = CubicBezierEasing(0.34f, 1f, 0.3f, 1f))
                    )
                    visibleState.value = false
                }
            }

            // 本组件存在性完全由 DialogEntry（visibleState）控制，禁止在此提前 return，
            // 否则退出动画结束瞬间遮罩被移除而 DialogEntry content 仍空挂在屏上，造成触摸穿透。

            // 底部弹窗主体 - 允许内容溢出屏幕底部
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (dimBackground) {
                            Modifier.background(Color.Black.copy(alpha = 0.2f * animationProgress.value))
                        } else Modifier
                    )
                    .clickable(
                        interactionSource = null,
                        indication = null,
                        onClick = onDismissRequest,
                    ),
            ) {
                val sheetModifier = Modifier
                    .graphicsLayer {
                        val progress = animationProgress.value
                        val currentHeight = sheetHeightPx.intValue.toFloat()
                        val windowHeightPx =
                            with(density) { windowInfo.containerDpSize.height.toPx() }
                        val baseOffset = if (currentHeight > 0) currentHeight else windowHeightPx
                        translationY = baseOffset * (1f - progress) + dragOffsetY.value
                    }

                val sheetOffsetDpValue =
                    if (sheetOffsetDp != Dp.Unspecified) sheetOffsetDp else 200.dp

                Box(
                    modifier = sheetModifier
                        .offset(y = sheetOffsetDpValue)
                        .align(Alignment.BottomCenter)
                        .then(
                            if (sheetMaxWidth != Dp.Unspecified) Modifier
                                .width(sheetMaxWidth)
                                .fillMaxWidth()
                            else Modifier.fillMaxWidth()
                        )
                        .heightIn(max = windowInfo.containerDpSize.height)
                        .onGloballyPositioned { coordinates ->
                            if (imeInsets.getBottom(density) == 0) {
                                sheetHeightPx.intValue = coordinates.size.height
                            }
                        }
                        .imePadding()
                        .clip(ContinuousRoundedRectangle(36.dp))
                        .then(
                            if (safeLiquidBackdrop != null && Build.VERSION.SDK_INT >= 33) {
                                Modifier.drawBackdrop(
                                    backdrop = safeLiquidBackdrop,
                                    shape = { ContinuousRoundedRectangle(36.dp) },
                                    effects = {
                                        vibrancy()
                                        blur(blurRadius.dp.toPx())
                                    },
                                    highlight = null
                                )
                            } else {
                                Modifier
                            }
                        )
                        .edgeLight(
                            shape = ContinuousRoundedRectangle(36.dp),
                            edgeLight = rememberDefaultEdgeLight()
                        )
                        .background(
                            sheetBgColor.copy(
                                alpha = sheetBackgroundAlpha ?: if (safeLiquidBackdrop != null)
                                    if (Build.VERSION.SDK_INT >= 33) 0.92f else 1f
                                else 1f
                            )
                        )
                        .pointerInput(Unit) {
                            // 消费弹窗空白处的点击，防止事件穿透到背景层触发关闭
                            detectTapGestures(onTap = {})
                        }
                        .semantics {
                            onClick(label = "Dismiss") {
                                onDismissRequest()
                                true
                            }
                        }
                        .draggable(
                            orientation = Orientation.Vertical,
                            state = rememberDraggableState { dragAmount ->
                                coroutineScope.launch {
                                    val newOffset = dragOffsetY.value + dragAmount
                                    // 往上拖时加阻尼，越往上越难拖
                                    val dampedOffset = if (newOffset < 0f) {
                                        val resistance = 1f / (1f + abs(newOffset) / 30f)
                                        dragOffsetY.value + dragAmount * resistance
                                    } else {
                                        newOffset
                                    }
                                    dragOffsetY.snapTo(dampedOffset)
                                }
                            },
                            onDragStopped = { velocity ->
                                coroutineScope.launch {
                                    val shouldDismiss =
                                        velocity > velocityThresholdPx || dragOffsetY.value > dismissThresholdPx
                                    if (shouldDismiss) {
                                        onDismissRequest()
                                    } else {
                                        // 使用 spring 动画回弹，传入初始速度让回弹更自然
                                        dragOffsetY.animateTo(
                                            targetValue = 0f,
                                            animationSpec = spring(
                                                dampingRatio = 0.72f,
                                                stiffness = Spring.StiffnessMediumLow
                                            ),
                                            initialVelocity = velocity * 0.12f
                                        )
                                    }
                                }
                            },
                        ),
                    content = {
                        // === 顶栏机制（迁移自 CollapsibleTopAppBar，小标题模式）===
                        val topBarState = rememberCollapsibleTopAppBarState()
                        val scrollBehavior = rememberSharedScrollBehavior(topBarState)
                        val overScrollState = remember { OverScrollState() }
                        LaunchedEffect(Unit) { topBarState.heightOffsetLimit = -1f }

                        val showButtonShadow by remember(scrollBehavior) {
                            derivedStateOf {
                                val contentOffset = scrollBehavior.state.contentOffset
                                val os = overScrollState.offset
                                contentOffset < 0f || os < 0f
                            }
                        }
                        // OverScrollNode.onPostScroll 在 overscroll 时把 available.y 全部返回给父级，
                        // 会被 scrollBehavior 累加进 contentOffset 造成污染（松手后不归零，阴影错保持）。
                        // 用代理连接包裹：overscroll 激活时，把 onPostScroll 的 consumed.y 置零，contentOffset 不再被污染。
                        // 这样零F页面 co 始终为 0；可滚动页面 co 仅累积真实滚动量。
                        val proxyConnection = remember(scrollBehavior, overScrollState) {
                            val delegate = scrollBehavior.nestedScrollConnection
                            object : NestedScrollConnection {
                                override fun onPreScroll(
                                    available: Offset,
                                    source: NestedScrollSource
                                ): Offset =
                                    delegate.onPreScroll(available, source)

                                override fun onPostScroll(
                                    consumed: Offset,
                                    available: Offset,
                                    source: NestedScrollSource,
                                ): Offset {
                                    // 用 offset != 0f 而非 isOverScrollActive 判断，避免 os 在 0~offsetThreshold(1f) 之间时
                                    // isOverScrollActive 仍为 false 导致的几帧污染窗口
                                    if (overScrollState.offset != 0f) {
                                        return delegate.onPostScroll(Offset.Zero, available, source)
                                    }
                                    return delegate.onPostScroll(consumed, available, source)
                                }

                                override suspend fun onPreFling(available: Velocity): Velocity =
                                    delegate.onPreFling(available)

                                override suspend fun onPostFling(
                                    consumed: Velocity,
                                    available: Velocity
                                ): Velocity =
                                    delegate.onPostFling(consumed, available)
                            }
                        }
                        val shadowAlpha = remember { Animatable(0f) }
                        val backdropAlpha = remember { Animatable(0f) }
                        LaunchedEffect(showButtonShadow) {
                            val target = if (showButtonShadow) 1f else 0f
                            val spec = if (showButtonShadow) {
                                folmeSpring(damping = 1.0f, response = 0.6f)
                            } else {
                                folmeSpring<Float>(damping = 1.0f, response = 0.4f)
                            }
                            launch { shadowAlpha.animateTo(target, spec) }
                            launch { backdropAlpha.animateTo(target, spec) }
                        }

                        CompositionLocalProvider(
                            LocalOverScrollState provides overScrollState,
                            LocalSheetTopBarMaterial provides SheetTopBarMaterial(
                                backdropAlpha.value,
                                shadowAlpha.value
                            ),
                        ) {
                            // 拖拽手柄（仅按下放大动画）
                            DragHandleArea()

                            // 捕获弹窗内容的 backdrop（先画不透明背景，再画内容，确保采样到不透明像素）
                            val sheetBackdropColor =
                                if (isDark) Color(0xFF1E1E1E) else Color(0xFFF4F4F4)
                            val sheetContentBackdrop = rememberLayerBackdrop {
                                drawRect(sheetBackdropColor)
                                drawContent()
                            }

                            // 将 backdrop 暴露给调用方
                            LaunchedEffect(sheetContentBackdrop) {
                                sheetContentBackdropHolder?.value = sheetContentBackdrop
                            }

                            // 内容区域（底层，用 layerBackdrop 捕获内容；nestedScroll 接入顶栏滚动行为）
                            Box(
                                modifier = Modifier
                                    .nestedScroll(proxyConnection)
                                    .wrapContentHeight()
                                    .layerBackdrop(sheetContentBackdrop)
                            ) {
                                content()
                            }

                            // 渐变模糊遮罩（采样弹窗内容）
                            ProgressiveBlurTopBar(
                                backdrop = sheetContentBackdrop,
                                height = 84.dp,
                                tintColor = sheetBgColor,
                                tintIntensity = 0f,
                                blurAlpha = backdropAlpha.value,
                                modifier = Modifier.zIndex(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(60.dp)
                                )
                            }

                            // 标题栏（zIndex 提升到顶层，消费触摸事件）
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 18.dp, bottom = 16.dp)
                                    .zIndex(2f),
                            ) {
                                Text(
                                    text = title,
                                    modifier = Modifier.align(Alignment.Center),
                                    fontSize = MiuixTheme.textStyles.title4.fontSize,
                                    fontWeight = FontWeight.Medium,
                                    textAlign = TextAlign.Center,
                                    color = MiuixTheme.colorScheme.onSurface,
                                )
                                if (startAction != null) {
                                    Box(modifier = Modifier.align(Alignment.CenterStart)) {
                                        startAction()
                                    }
                                }
                                if (endAction != null) {
                                    Box(modifier = Modifier.align(Alignment.CenterEnd)) {
                                        endAction()
                                    }
                                }
                            }
                        }
                    },
                )
            }
        }
    }
}

private fun Color.luminance(): Float {
    return 0.299f * red + 0.587f * green + 0.114f * blue
}

/**
 * Miuix 风格的拖拽手柄：按下时放大。
 */
@Composable
private fun DragHandleArea() {
    val pressScale = remember { Animatable(1f) }
    val pressWidth = remember { Animatable(45f) }
    val coroutineScope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(22.dp)
            .zIndex(2f)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitFirstDown(requireUnconsumed = false)
                        coroutineScope.launch {
                            launch { pressScale.animateTo(1.15f, tween(100)) }
                            launch { pressWidth.animateTo(55f, tween(100)) }
                        }
                        // 等待松手
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.none { it.pressed }) break
                        }
                        coroutineScope.launch {
                            launch { pressScale.animateTo(1f, tween(150)) }
                            launch { pressWidth.animateTo(45f, tween(150)) }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(pressWidth.value.dp)
                .height(4.dp)
                .graphicsLayer {
                    scaleY = pressScale.value
                }
                .clip(RoundedCornerShape(2.dp))
                .background(MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.2f)),
        )
    }
}
