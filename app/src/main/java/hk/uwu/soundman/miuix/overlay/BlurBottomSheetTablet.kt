/** 自定义模糊底部弹窗 - 平板版本：居中悬浮矩形，从底部滑入 */
package hk.uwu.soundman.miuix.overlay

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

/**
 * 平板版模糊底部弹窗组件：居中悬浮矩形，从底部滑入动画。
 *
 * @param show 是否显示
 * @param title 标题文字
 * @param blurRadius 模糊半径
 * @param dimBackground 是否压暗背景
 * @param sheetMaxWidth 弹窗最大宽度
 * @param sheetBackgroundColor 弹窗背景颜色，null 则使用默认颜色
 * @param sheetBackgroundAlpha 弹窗背景透明度，null 则使用默认值
 * @param onDismissRequest 关闭回调
 * @param startAction 标题栏左侧操作按钮
 * @param endAction 标题栏右侧操作按钮
 * @param liquidGlassBackdrop 液态玻璃 backdrop
 * @param content 内容区域
 */
@Composable
fun BlurBottomSheetTablet(
    show: Boolean,
    title: String,
    blurRadius: Float = 24f,
    dimBackground: Boolean = false,
    sheetMaxWidth: Dp = 560.dp,
    sheetMaxHeight: Dp = Dp.Unspecified,
    sheetBackgroundColor: Color? = null,
    sheetBackgroundAlpha: Float? = null,
    isBottomAligned: Boolean = false,
    onDismissRequest: () -> Unit,
    startAction: @Composable (() -> Unit)? = null,
    endAction: @Composable (() -> Unit)? = null,
    liquidGlassBackdrop: Backdrop? = null,
    onSheetContentBackdropCreated: ((Backdrop?) -> Unit)? = null,
    skipEnterAnimation: Boolean = false,
    content: @Composable () -> Unit,
) {
    val visibleState = remember { mutableStateOf(show) }
    val sheetContentBackdropHolder = remember { mutableStateOf<Backdrop?>(null) }

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
        BlurBottomSheetTabletContent(
            show = show,
            visibleState = visibleState,
            title = title,
            dimBackground = dimBackground,
            sheetMaxWidth = sheetMaxWidth,
            sheetMaxHeight = sheetMaxHeight,
            sheetBackgroundColor = sheetBackgroundColor,
            sheetBackgroundAlpha = sheetBackgroundAlpha,
            isBottomAligned = isBottomAligned,
            onDismissRequest = onDismissRequest,
            startAction = startAction,
            endAction = endAction,
            liquidGlassBackdrop = liquidGlassBackdrop,
            sheetContentBackdropHolder = sheetContentBackdropHolder,
            skipEnterAnimation = skipEnterAnimation,
            content = content,
        )
    }
}

@Composable
private fun BlurBottomSheetTabletContent(
    show: Boolean,
    visibleState: MutableState<Boolean>,
    title: String,
    dimBackground: Boolean = false,
    sheetMaxWidth: Dp = 560.dp,
    sheetMaxHeight: Dp = Dp.Unspecified,
    sheetBackgroundColor: Color? = null,
    sheetBackgroundAlpha: Float? = null,
    isBottomAligned: Boolean = false,
    onDismissRequest: () -> Unit,
    startAction: @Composable (() -> Unit)? = null,
    endAction: @Composable (() -> Unit)? = null,
    liquidGlassBackdrop: Backdrop? = null,
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
            val density = LocalDensity.current
            val windowInfo = LocalWindowInfo.current
            val sheetHeightPx = remember { mutableIntStateOf(0) }

            val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
            val safeLiquidBackdrop =
                rememberSafeBackdrop(liquidGlassBackdrop, "BlurBottomSheetTablet")
            val sheetBgColor =
                sheetBackgroundColor ?: if (isDark) Color(0xFF1E1E1E) else Color(0xFFF2F2F2)

            // 显示/隐藏动画（同时驱动弹窗位移与遮罩透明度，确保二者完全同步）
            LaunchedEffect(show) {
                if (show) {
                    if (skipEnterAnimation) {
                        animationProgress.snapTo(1f)
                    } else {
                        animationProgress.animateTo(
                            targetValue = 1f,
                            animationSpec = tween(
                                durationMillis = 500,
                                easing = CubicBezierEasing(0.34f, 1.12f, 0.3f, 1f)
                            )
                        )
                    }
                } else {
                    animationProgress.animateTo(
                        0f,
                        animationSpec = tween(380, easing = CubicBezierEasing(0.34f, 1f, 0.3f, 1f))
                    )
                    visibleState.value = false
                }
            }

            // 本组件存在性完全由 DialogEntry（visibleState）控制，禁止在此提前 return，
            // 否则退出动画结束瞬间遮罩被移除而 DialogEntry content 仍空挂在屏上，造成触摸穿透。

            // 平板弹窗主体 - 居中悬浮矩形，从底部滑入
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
                contentAlignment = if (isBottomAligned) Alignment.BottomCenter else Alignment.Center,
            ) {
                val sheetModifier = Modifier
                    .graphicsLayer {
                        val progress = animationProgress.value
                        val windowHeightPx =
                            with(density) { windowInfo.containerDpSize.height.toPx() }
                        // 从屏幕底部滑入
                        translationY = windowHeightPx * (1f - progress)
                    }

                Box(
                    modifier = sheetModifier
                        .width(sheetMaxWidth)
                        .fillMaxWidth()
                        .heightIn(max = if (sheetMaxHeight != Dp.Unspecified) sheetMaxHeight else windowInfo.containerDpSize.height * 0.8f)
                        .then(if (isBottomAligned) Modifier.padding(bottom = 20.dp) else Modifier)
                        .onGloballyPositioned { coordinates ->
                            sheetHeightPx.intValue = coordinates.size.height
                        }
                        .clip(ContinuousRoundedRectangle(38.dp))
                        .then(
                            if (safeLiquidBackdrop != null && Build.VERSION.SDK_INT >= 33) {
                                Modifier.drawBackdrop(
                                    backdrop = safeLiquidBackdrop,
                                    shape = { ContinuousRoundedRectangle(38.dp) },
                                    effects = {
                                        vibrancy()
                                        blur(24.dp.toPx())
                                    },
                                    highlight = null
                                )
                            } else {
                                Modifier
                            }
                        )
                        .edgeLight(
                            shape = ContinuousRoundedRectangle(38.dp),
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
                        },
                    content = {
                        // === 顶栏机制（迁移自手机版 BlurBottomSheet）===
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
                            // 捕获弹窗内容的 backdrop
                            val sheetBackdropColor =
                                if (isDark) Color(0xFF1E1E1E) else Color(0xFFF4F4F4)
                            val sheetContentBackdrop = rememberLayerBackdrop {
                                drawRect(sheetBackdropColor)
                                drawContent()
                            }

                            LaunchedEffect(sheetContentBackdrop) {
                                sheetContentBackdropHolder?.value = sheetContentBackdrop
                            }

                            // 内容区域（nestedScroll 接入顶栏滚动行为）
                            Box(
                                modifier = Modifier
                                    .nestedScroll(proxyConnection)
                                    .wrapContentHeight()
                                    .layerBackdrop(sheetContentBackdrop)
                            ) {
                                content()
                            }

                            // 渐变模糊遮罩
                            ProgressiveBlurTopBar(
                                backdrop = sheetContentBackdrop,
                                height = 82.dp,
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

                            // 标题栏
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 16.dp, bottom = 16.dp)
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
