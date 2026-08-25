package hk.uwu.soundman.ui.basic

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.runtimeShaderEffect
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 顶部栏的液体玻璃材质层。
 *
 * 动机：TopBar 的布局与材质分离后，不同页面可复用同一布局并选择不同风格。传入的
 * Backdrop 仅用于采样；它为空、不支持 RuntimeShader 或采样失效时，组件会使用与主题
 * 一致的渐变遮罩，保证 UI 可读且不会崩溃。正常采样路径保持 NexioSchedule 的
 * shader、tint 与 alpha 行为，避免和液态按钮产生色差。
 *
 * @param backdrop 当前容器创建的可选背景采样源。
 * @param height 材质区域高度；未指定时按照状态栏高度自适应。
 * @param tintIntensity 表面 tint 强度，必须在 0..1 范围内。
 * @param blurAlpha 整个材质层的可见度，默认始终展示玻璃基础材质。
 */
@Composable
fun ProgressiveBlurTopBar(
    backdrop: Backdrop?,
    modifier: Modifier = Modifier,
    height: Dp = Dp.Unspecified,
    tintIntensity: Float = 0.2f,
    tintColor: Color = MiuixTheme.colorScheme.surface,
    blurAlpha: Float = 1f,
    content: @Composable BoxScope.() -> Unit,
) {
    require(tintIntensity in 0f..1f) { "tintIntensity must be within 0..1" }
    require(blurAlpha in 0f..1f) { "blurAlpha must be within 0..1" }

    val density = LocalDensity.current
    val safeBackdrop = rememberSafeBackdrop(backdrop, "ProgressiveBlurTopBar")
    val totalHeight = if (height != Dp.Unspecified) {
        height
    } else {
        val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        if (statusBarHeight > 0.dp) 80.dp + statusBarHeight else 120.dp
    }
    val fallbackEndY = totalHeight.value * density.density

    Box(modifier = modifier) {
        if (safeBackdrop != null && Build.VERSION.SDK_INT >= 33) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(totalHeight)
                    .graphicsLayer { alpha = blurAlpha }
                    .drawPlainBackdrop(
                        backdrop = safeBackdrop,
                        shape = { RectangleShape },
                        effects = {
                            blur(4f.dp.toPx())
                            runtimeShaderEffect(
                                "ProgressiveBlurAlphaMask",
                                """
                                    uniform shader content;
                                    uniform float2 size;
                                    layout(color) uniform half4 tint;
                                    uniform float tintIntensity;

                                    half4 main(float2 coord) {
                                        float blurAlpha = smoothstep(size.y, size.y * 0.6, coord.y);
                                        float tintAlpha = smoothstep(size.y, size.y * 0.7, coord.y);
                                        return mix(content.eval(coord) * blurAlpha, tint * tintAlpha, tintIntensity);
                                    }
                                """.trimIndent(),
                                "content",
                            ) {
                                setFloatUniform("size", size.width, size.height)
                                setColorUniform("tint", tintColor)
                                setFloatUniform("tintIntensity", tintIntensity)
                            }
                        },
                    ),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(totalHeight)
                    .graphicsLayer { alpha = blurAlpha }
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0f to tintColor.copy(alpha = 0.9f),
                                0.4f to tintColor.copy(alpha = 0.82f),
                                0.7f to tintColor.copy(alpha = 0.6f),
                                1f to Color.Transparent,
                            ),
                            startY = 0f,
                            endY = fallbackEndY,
                        ),
                    ),
            )
        }

        content()
    }
}
