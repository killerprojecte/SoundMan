package hk.uwu.soundman.ui.components

import android.graphics.BlurMaskFilter
import android.graphics.Paint
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.kyant.capsule.ContinuousRoundedRectangle
import hk.uwu.soundman.miuix.basic.rememberDynamicCornerRadiusShape
import hk.uwu.soundman.ui.effects.edgelight.edgeLight
import hk.uwu.soundman.ui.effects.edgelight.rememberDefaultEdgeLight
import hk.uwu.soundman.ui.utils.isAppDarkTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * LiquidGlass 风格的下拉菜单，移植自 NexioSchedule 的 LiquidGlassDropdownMenu。
 *
 * 动机：TopBar 右上角"更多"按钮需要一个从按钮位置展开的液态玻璃下拉菜单，
 * 使用 drawBackdrop 采样背景实现玻璃效果，自带缩放/裁剪/阴影/模糊动画。
 * 不依赖 SOverlayListPopup 的定位系统，而是通过外层 Box 的 contentAlignment + padding 手动定位。
 */

private val ShadowPadding = 24.dp

@Composable
fun LiquidGlassDropdownMenu(
    show: Boolean,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    fraction: Animatable<Float, *> = remember { Animatable(0f) },
    onDismiss: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (show && onDismiss != null) {
        BackHandler { onDismiss() }
    }

    val isLightTheme = !isAppDarkTheme()
    val containerColor = if (isLightTheme) Color(0xFFFFFFFF).copy(0.72f)
    else Color(0xFF242424).copy(0.8f)

    val menuAlpha = remember { Animatable(0f) }

    var contentAlpha by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var prevFraction = 0f
        snapshotFlow { fraction.value }
            .collect { current ->
                val isEntering = current >= prevFraction
                prevFraction = current
                contentAlpha = if (isEntering) {
                    0.2f + 0.8f * current
                } else {
                    if (current > 0.5f) 1f else current * 2f
                }
            }
    }

    val shadowAlphaState = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        var prevFraction = 0f
        var shadowVisible = false
        var animJob: Job? = null
        snapshotFlow { fraction.value }
            .collect { current ->
                val isEntering = current >= prevFraction
                prevFraction = current
                val newVisible = if (isEntering) current >= 0.78f else current >= 0.99f
                if (newVisible != shadowVisible) {
                    shadowVisible = newVisible
                    animJob?.cancel()
                    animJob = launch {
                        if (newVisible) {
                            shadowAlphaState.animateTo(1f, tween(200))
                        } else {
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

    val transformOriginProgress = remember { Animatable(0f) }

    val cornerRadius = 25.dp

    val clipShape = remember {
        DropdownClipShape(
            fractionProgress = { fraction.value },
            cornerRadius = cornerRadius,
            buttonDiameter = 42.dp,
        )
    }

    LaunchedEffect(show) {
        if (show) {
            launch {
                fraction.animateTo(
                    1f,
                    spring(dampingRatio = 0.78f, stiffness = 240f, visibilityThreshold = 0.0001f),
                )
            }
            launch {
                transformOriginProgress.animateTo(
                    1f,
                    spring(dampingRatio = 0.78f, stiffness = 500f, visibilityThreshold = 0.0001f),
                )
            }
            launch {
                menuAlpha.animateTo(1f, tween(120))
            }
        } else {
            val exitEasing = CubicBezierEasing(0.0f, 0.0f, 0.0f, 1.0f)
            launch {
                fraction.animateTo(
                    0f,
                    spring(dampingRatio = 0.78f, stiffness = 400f, visibilityThreshold = 0.0001f),
                )
            }
            launch {
                transformOriginProgress.animateTo(
                    0f,
                    tween(450, easing = exitEasing),
                )
            }
            menuAlpha.animateTo(0f, tween(400))
            fraction.snapTo(0f)
            transformOriginProgress.snapTo(0f)
            menuAlpha.snapTo(0f)
        }
    }

    if (menuAlpha.value <= 0f && !show) return

    Box(
        modifier = modifier
            .width(200.dp + ShadowPadding * 2)
            .wrapContentHeight()
            .padding(ShadowPadding)
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
                        android.graphics.Path.Direction.CW,
                    )
                }
                val paint = Paint().apply {
                    color = shadowColor
                    maskFilter = BlurMaskFilter(
                        blurRadius.coerceAtLeast(0.1f),
                        BlurMaskFilter.Blur.NORMAL,
                    )
                }
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawPath(nativePath, paint)
                }
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    val f = fraction.value
                    val scale = 0.24f + 0.76f * f
                    scaleX = scale
                    scaleY = scale
                    this.alpha = menuAlpha.value
                    val startOrigin = TransformOrigin(1f, 0f)
                    val targetOrigin = TransformOrigin(0.5f, 0.5f)
                    val f2 = transformOriginProgress.value
                    transformOrigin = TransformOrigin(
                        pivotFractionX = startOrigin.pivotFractionX + (targetOrigin.pivotFractionX - startOrigin.pivotFractionX) * f2,
                        pivotFractionY = startOrigin.pivotFractionY + (targetOrigin.pivotFractionY - startOrigin.pivotFractionY) * f2,
                    )
                    clip = false
                }
                .clip(clipShape)
                .blur(radius = (8f * (1f - fraction.value)).dp)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = {
                        val f = fraction.value.coerceIn(0f, 1f)
                        val avgScale = 0.24f + 0.76f * f
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
                        drawRect(containerColor)
                    },
                )
                .edgeLight(
                    shape = rememberDynamicCornerRadiusShape(
                        fractionProgress = { fraction.value },
                        cornerRadius = cornerRadius,
                    ),
                    edgeLight = rememberDefaultEdgeLight(),
                ),
        ) {
            Column(
                modifier = Modifier
                    .padding(vertical = 8.dp)
                    .graphicsLayer { alpha = contentAlpha },
            ) {
                content()
            }
        }
    }
}

@Composable
fun LiquidGlassDropdownMenuItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable (() -> Unit)? = null,
) {
    val isLightTheme = !isAppDarkTheme()
    val textColor = if (isLightTheme) Color(0xFF1A1A1A) else Color(0xFFE8E4DE)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(ContinuousRoundedRectangle(17.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.5.dp),
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            if (icon != null) {
                icon()
                Spacer(modifier = Modifier.size(10.dp))
            }
            Text(
                text = text,
                fontSize = 15.6.sp,
                fontWeight = FontWeight.Medium,
                color = textColor,
            )
        }
    }
}

private class DropdownClipShape(
    private val fractionProgress: () -> Float,
    private val cornerRadius: Dp,
    private val buttonDiameter: Dp,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val f = fractionProgress().coerceIn(0f, 1f)
        val scale = 0.24f + 0.76f * f

        val buttonPx = with(density) { buttonDiameter.toPx() }
        val targetVisualWidth = buttonPx + (size.width - buttonPx) * f
        val targetVisualHeight = buttonPx + (size.height - buttonPx) * f

        val clipWidth = (targetVisualWidth / scale).coerceAtMost(size.width)
        val clipHeight = (targetVisualHeight / scale).coerceAtMost(size.height)

        val left = size.width - clipWidth
        val top = 0f
        val right = size.width
        val bottom = clipHeight

        val cornerRadiusPx = with(density) { (cornerRadius / scale).toPx() }

        val path = Path().apply {
            addRoundRect(
                RoundRect(
                    left = left,
                    top = top,
                    right = right,
                    bottom = bottom,
                    cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx),
                ),
            )
        }
        return Outline.Generic(path)
    }
}
