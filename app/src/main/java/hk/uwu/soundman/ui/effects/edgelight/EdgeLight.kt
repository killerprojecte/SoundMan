package hk.uwu.soundman.ui.effects.edgelight

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hk.uwu.soundman.ui.utils.isAppDarkTheme

@Immutable
data class EdgeLight(
    val width: Dp = 1.dp,
    val blurRadius: Dp = 2.dp,
    val intensity: Float = 1f,
    val style: EdgeLightStyle = EdgeLightStyle.Default
) {
    companion object {

        @Stable
        val Default: EdgeLight = EdgeLight()

        @Stable
        val Subtle: EdgeLight = EdgeLight(
            width = 0.5f.dp,
            blurRadius = 1.dp,
            intensity = 0.6f
        )

        @Stable
        val Prominent: EdgeLight = EdgeLight(
            width = 2.dp,
            blurRadius = 4.dp,
            intensity = 1f
        )

        @Stable
        fun Uniform(
            color: Color = Color.White.copy(alpha = 0.5f),
            width: Dp = 0.28.dp,
            blurRadius: Dp = 0.8.dp,
            intensity: Float = 1f
        ): EdgeLight = EdgeLight(
            width = width,
            blurRadius = blurRadius,
            intensity = intensity,
            style = EdgeLightStyle.Uniform(color = color)
        )

        @Stable
        fun Directional(
            color: Color = Color.White.copy(alpha = 0.5f),
            width: Dp = 1.dp,
            blurRadius: Dp = 2.dp,
            intensity: Float = 1f,
            angle: Float = 45f,
            falloff: Float = 1f
        ): EdgeLight = EdgeLight(
            width = width,
            blurRadius = blurRadius,
            intensity = intensity,
            style = EdgeLightStyle.Directional(color = color, angle = angle, falloff = falloff)
        )

        @Stable
        fun Glow(
            color: Color = Color.White.copy(alpha = 0.5f),
            width: Dp = 1.dp,
            blurRadius: Dp = 2.dp,
            intensity: Float = 1f,
            glowSize: Float = 10f
        ): EdgeLight = EdgeLight(
            width = width,
            blurRadius = blurRadius,
            intensity = intensity,
            style = EdgeLightStyle.Glow(color = color, glowSize = glowSize)
        )

        @Stable
        fun CourseCard(
            color: Color = Color.White.copy(alpha = 0.5f),
            width: Dp = 0.36.dp,
            blurRadius: Dp = 1.26.dp,
            intensity: Float = 0.52f,
        ): EdgeLight = Uniform(
            color = color,
            width = width,
            blurRadius = blurRadius,
            intensity = intensity
        )

        @Stable
        fun Card(
            color: Color = Color.White.copy(alpha = 0.5f),
            width: Dp = 0.35.dp,
            blurRadius: Dp = 1.24.dp,
            intensity: Float = 0.5f
        ): EdgeLight = Uniform(
            color = color,
            width = width,
            blurRadius = blurRadius,
            intensity = intensity
        )
    }
}

@Composable
fun rememberDefaultEdgeLight(): EdgeLight {
    val isLightTheme = !isAppDarkTheme()
    val color = if (isLightTheme) Color.White.copy(alpha = 0.7f)
    else Color.White.copy(alpha = 0.4f)
    return remember(isLightTheme) { EdgeLight.Uniform(color = color) }
}

@Composable
fun rememberCourseCardEdgeLight(): EdgeLight {
    val isLightTheme = !isAppDarkTheme()
    val color = if (isLightTheme) Color.White.copy(alpha = 0.12f)
    else Color.White.copy(alpha = 0.12f)
    return remember(isLightTheme) { EdgeLight.CourseCard(color = color) }
}

@Composable
fun rememberCardEdgeLight(): EdgeLight {
    val isLightTheme = !isAppDarkTheme()
    val color = if (isLightTheme) Color.White.copy(alpha = 0.2f)
    else Color.White.copy(alpha = 0.2f)
    return remember(isLightTheme) { EdgeLight.Card(color = color) }
}

@Composable
fun rememberLiquidTopBarButtonEdgeLight(): EdgeLight {
    val isLightTheme = !isAppDarkTheme()
    val color = if (isLightTheme) Color.White.copy(alpha = 0.8f)
    else Color.White.copy(alpha = 0.32f)
    return remember(isLightTheme) {
        EdgeLight.Uniform(
            color = color,
            width = 0.15f.dp,
            blurRadius = 0.5f.dp,
            intensity = 1f
        )
    }
}
