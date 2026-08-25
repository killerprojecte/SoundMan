package hk.uwu.soundman.ui.effects.edgelight

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color

@Immutable
sealed interface EdgeLightStyle {

    val color: Color

    val blendMode: BlendMode

    @Immutable
    data class Uniform(
        override val color: Color = Color.White.copy(alpha = 0.5f),
        override val blendMode: BlendMode = BlendMode.Plus
    ) : EdgeLightStyle

    @Immutable
    data class Directional(
        override val color: Color = Color.White.copy(alpha = 0.5f),
        override val blendMode: BlendMode = BlendMode.Plus,
        val angle: Float = 45f,
        val falloff: Float = 1f
    ) : EdgeLightStyle

    @Immutable
    data class Glow(
        override val color: Color = Color.White.copy(alpha = 0.5f),
        override val blendMode: BlendMode = BlendMode.Plus,
        val glowSize: Float = 10f
    ) : EdgeLightStyle

    companion object {

        @Stable
        val Default: Uniform = Uniform()

        @Stable
        val Directional: Directional = Directional()

        @Stable
        val Glow: Glow = Glow()
    }
}
