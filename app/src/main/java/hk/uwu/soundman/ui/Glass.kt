package hk.uwu.soundman.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.squircle.SquircleDefaults
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface

internal val OverlayGlassRadius = 36.dp
internal val OverlayGlassFill = Color(0x663C3C3E)
internal val OverlayGlassBorder = Color.White.copy(alpha = 0.20f)
internal val HomeGlassRadius = 28.dp
internal val HomeButtonRadius = 24.dp
internal val OnGlassText = Color.White.copy(alpha = 0.88f)
internal val OnGlassMuted = Color.White.copy(alpha = 0.55f)
private val MAJOR_SURFACE_SQUIRCLE_EXTENSION = SquircleDefaults.Extension

/** 统一玻璃表面用途；每个主要容器必须通过该枚举应用同一形状策略。 */
internal enum class BlurMaterialPurpose {
    Card,
    Action,
    Panel,
    Hint,
    DeviceRow,
    DeviceSelected,
    VolumeTrack,
    VolumeFill,
}

/** 可由 JVM 单元测试验证的稳定半透明材质参数。 */
@Immutable
internal data class BlurMaterialSpec(
    val blurRadius: Float,
    val tintAlpha: Float,
    val contrast: Float,
    val saturation: Float,
    val noise: Float,
    val softLightAlpha: Float,
)

internal object BlurMaterialTokens {
    fun spec(purpose: BlurMaterialPurpose): BlurMaterialSpec = when (purpose) {
        BlurMaterialPurpose.Card -> BlurMaterialSpec(22f, 0f, 1.03f, 1.04f, 0.0025f, 0.008f)
        BlurMaterialPurpose.Action -> BlurMaterialSpec(20f, 0f, 1.04f, 1.05f, 0.0025f, 0.008f)
        BlurMaterialPurpose.Panel -> BlurMaterialSpec(28f, 0f, 1.03f, 1.03f, 0.002f, 0.008f)
        BlurMaterialPurpose.Hint -> BlurMaterialSpec(16f, 0.34f, 1.03f, 1.03f, 0.0025f, 0.008f)
        BlurMaterialPurpose.DeviceRow -> BlurMaterialSpec(16f, 0f, 1.04f, 1.04f, 0.0025f, 0.008f)
        BlurMaterialPurpose.DeviceSelected -> BlurMaterialSpec(
            14f,
            0.68f,
            1.02f,
            1.02f,
            0.002f,
            0.008f
        )

        BlurMaterialPurpose.VolumeTrack -> BlurMaterialSpec(16f, 0f, 1.04f, 1.05f, 0.0025f, 0.008f)
        BlurMaterialPurpose.VolumeFill -> BlurMaterialSpec(8f, 0.38f, 1.02f, 1.02f, 0.0015f, 0.008f)
    }
}

internal enum class GlassOutlineKind { Rounded, Squircle }

@Immutable
internal data class GlassShapePolicy(
    val clip: GlassOutlineKind,
    val border: GlassOutlineKind,
    val squircleExtension: Float,
    val finalClipCount: Int,
    val surfaceTintInsideFinalClip: Boolean,
    val usesOfficialSurfaceAndBorder: Boolean,
)

@Immutable
internal data class GlassGeometryPolicy(
    val surfaceCornerRadius: Float,
    val borderCornerRadius: Float,
    val squircleExtension: Float,
    val finalClipCount: Int,
    val borderStrokeInset: Float,
)

/** 所有玻璃用途共享同一 clip/fill/border 几何，边框按官方规则向内缩半个线宽。 */
internal object GlassShapeTokens {
    fun policy(purpose: BlurMaterialPurpose, smoothCornersEnabled: Boolean): GlassShapePolicy {
        require(purpose in BlurMaterialPurpose.entries) { "unknown glass surface purpose" }
        val outline =
            if (smoothCornersEnabled) GlassOutlineKind.Squircle else GlassOutlineKind.Rounded
        return GlassShapePolicy(
            clip = outline,
            border = outline,
            squircleExtension = MAJOR_SURFACE_SQUIRCLE_EXTENSION,
            finalClipCount = 1,
            surfaceTintInsideFinalClip = true,
            usesOfficialSurfaceAndBorder = true,
        )
    }

    fun geometry(
        purpose: BlurMaterialPurpose,
        smoothCornersEnabled: Boolean,
        cornerRadius: Float,
        borderWidth: Float,
    ): GlassGeometryPolicy {
        require(cornerRadius.isFinite() && cornerRadius >= 0f) {
            "corner radius must be finite and non-negative"
        }
        require(borderWidth.isFinite() && borderWidth >= 0f) {
            "border width must be finite and non-negative"
        }
        val policy = policy(purpose, smoothCornersEnabled)
        return GlassGeometryPolicy(
            surfaceCornerRadius = cornerRadius,
            borderCornerRadius = cornerRadius,
            squircleExtension = policy.squircleExtension,
            finalClipCount = policy.finalClipCount,
            borderStrokeInset = borderWidth / 2f,
        )
    }
}

internal object BlurHostTokens {
    fun isOrdinarySurface(purpose: BlurMaterialPurpose): Boolean = when (purpose) {
        BlurMaterialPurpose.Card,
        BlurMaterialPurpose.Action,
        BlurMaterialPurpose.Panel,
        BlurMaterialPurpose.DeviceRow,
        BlurMaterialPurpose.VolumeTrack,
            -> true

        else -> false
    }

    fun surfaceFill(requestedTint: Color, purpose: BlurMaterialPurpose): Color =
        if (isOrdinarySurface(purpose)) OverlayGlassFill else requestedTint
}

private val LocalSmoothCornersEnabled = staticCompositionLocalOf { false }

/** 为主页或悬浮面板中的所有玻璃表面提供统一的平滑圆角设置。 */
@Composable
internal fun BlurMaterialHost(
    smoothCornersEnabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    CompositionLocalProvider(LocalSmoothCornersEnabled provides smoothCornersEnabled) {
        Box(modifier = modifier, content = content)
    }
}

/** 统一玻璃表面：先裁剪 fill/content，最后按完全相同的轮廓绘制边框。 */
@Composable
internal fun GlassSurface(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = HomeGlassRadius,
    fill: Color = OverlayGlassFill,
    border: Color = OverlayGlassBorder,
    purpose: BlurMaterialPurpose = BlurMaterialPurpose.Card,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    var surfaceModifier = modifier.blurMaterial(purpose, cornerRadius, fill, border = null)
    if (onClick != null) surfaceModifier = surfaceModifier.clickable(onClick = onClick)
    Box(modifier = surfaceModifier) {
        content()
        Box(
            Modifier
                .matchParentSize()
                .glassBorder(
                    purpose = purpose,
                    cornerRadius = cornerRadius,
                    color = border,
                ),
        )
    }
}

/** 对所有主要容器应用统一、稳定的半透明填充、单次内容裁剪和 matching border。 */
@Composable
internal fun Modifier.blurMaterial(
    purpose: BlurMaterialPurpose,
    cornerRadius: Dp,
    tint: Color = OverlayGlassFill,
    border: Color? = OverlayGlassBorder,
): Modifier {
    val smoothCornersEnabled = LocalSmoothCornersEnabled.current
    val borderWidth = 1.dp
    val shapePolicy = GlassShapeTokens.policy(purpose, smoothCornersEnabled)
    val geometry = GlassShapeTokens.geometry(
        purpose = purpose,
        smoothCornersEnabled = smoothCornersEnabled,
        cornerRadius = cornerRadius.value,
        borderWidth = borderWidth.value,
    )
    val surfaceFill = BlurHostTokens.surfaceFill(tint, purpose)

    check(shapePolicy.finalClipCount == 1) { "A material surface must have exactly one final clip" }
    check(shapePolicy.surfaceTintInsideFinalClip) { "Surface tint must be drawn inside the final clip" }
    check(shapePolicy.clip == shapePolicy.border) { "Glass clip and border must use the same outline" }
    check(shapePolicy.usesOfficialSurfaceAndBorder) { "Glass surfaces must use matching official modifiers" }
    check(geometry.surfaceCornerRadius == geometry.borderCornerRadius) {
        "Glass surface and border must use the same corner radius"
    }

    val surfaced = when (shapePolicy.clip) {
        GlassOutlineKind.Squircle -> this.squircleSurface(
            color = surfaceFill,
            cornerRadius = cornerRadius,
            extension = geometry.squircleExtension,
        )

        GlassOutlineKind.Rounded -> this
            .clip(RoundedCornerShape(cornerRadius))
            .background(surfaceFill)
    }
    return if (border == null) surfaced else surfaced.glassBorder(
        purpose = purpose,
        cornerRadius = cornerRadius,
        color = border,
        width = borderWidth,
    )
}

/** 使用与表面相同的半径和 extension 绘制官方内缩边框，不再创建第二套裁剪。 */
@Composable
internal fun Modifier.glassBorder(
    purpose: BlurMaterialPurpose,
    cornerRadius: Dp,
    color: Color,
    width: Dp = 1.dp,
): Modifier {
    val smoothCornersEnabled = LocalSmoothCornersEnabled.current
    val shapePolicy = GlassShapeTokens.policy(purpose, smoothCornersEnabled)
    val geometry = GlassShapeTokens.geometry(
        purpose = purpose,
        smoothCornersEnabled = smoothCornersEnabled,
        cornerRadius = cornerRadius.value,
        borderWidth = width.value,
    )
    check(geometry.borderStrokeInset == width.value / 2f) {
        "Glass border must be inset by half its stroke width"
    }
    return when (shapePolicy.border) {
        GlassOutlineKind.Squircle -> this.squircleBorder(
            width = width,
            color = color,
            cornerRadius = cornerRadius,
            extension = geometry.squircleExtension,
        )

        GlassOutlineKind.Rounded -> this.border(
            width = width,
            color = color,
            shape = RoundedCornerShape(cornerRadius),
        )
    }
}
