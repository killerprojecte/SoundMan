package hk.uwu.soundman.miuix.basic

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import com.kyant.capsule.ContinuousRoundedRectangle

/**
 * 创建一个圆角随动画进度反向放大的 Shape。
 *
 * 在弹窗缩放过程中，为保持视觉圆角不变，圆角需要按缩放比例反向放大
 *（与 popupClipReveal 的圆角补偿逻辑一致）。
 *
 * 每帧 createOutline 时都会重新读取 fractionProgress()，从而动态更新圆角。
 *
 * @param fractionProgress 提供当前动画进度（0→1）
 * @param cornerRadius 基准圆角
 */
@Composable
fun rememberDynamicCornerRadiusShape(
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
