package hk.uwu.soundman.hook.scopes.systemui.runtime

import hk.uwu.soundman.hook.scopes.systemui.hidden.OfficialExpandedMaterialMode

/**
 * 内置面板液态玻璃的渲染配置。
 *
 * 参数集与默认值承袭 HyperIsland 超级岛液态玻璃（IslandBlurHook / LiqudGlass）的调校：
 * - [enabled] 对应「启用玻璃效果」：在官方背景模糊之上叠加方向性边缘高光、透镜带与棱镜色散；
 * - [trueRefraction] 对应「液态玻璃」：捕获面板后方画面并用 AGSL 折射着色器实时渲染。
 *
 * 边缘带与折射位移按面板较短边计算——HyperIsland 的岛是横向 pill（高度即短边），
 * 音量面板更高瘦，沿用短边语义才能得到同量级的视觉效果。
 */
data class LiquidGlassPanelConfig(
    val enabled: Boolean,
    val trueRefraction: Boolean,
    val edgeWidth: Float = EDGE_WIDTH,
    val refraction: Float = REFRACTION,
    val highlight: Float = HIGHLIGHT,
    val shadow: Float = SHADOW,
    val lightDirection: Int = LIGHT_DIRECTION,
    val dispersion: Float = DISPERSION,
    val gyroscope: Boolean = GYROSCOPE,
    val captureFps: Int = CAPTURE_FPS,
    val captureScale: Float = CAPTURE_SCALE,
    val captureBlurRadius: Float = CAPTURE_BLUR_RADIUS,
    val blendColor: Int = BLEND_COLOR,
) {
    companion object {
        const val EDGE_WIDTH = 0.16f
        const val REFRACTION = 0.16f
        const val HIGHLIGHT = 0.42f
        const val SHADOW = 0.14f
        const val LIGHT_DIRECTION = 243
        const val DISPERSION = 0.18f
        const val GYROSCOPE = true
        const val CAPTURE_FPS = 20
        const val CAPTURE_SCALE = 0.3f
        const val CAPTURE_BLUR_RADIUS = 20f
        const val BLEND_COLOR = 0x20FFFFFF
    }
}

/**
 * 液态玻璃挂载策略（纯函数，JVM 可测）。
 *
 * 材质判定来自 [OfficialExpandedMaterialPolicy]：OS4 的 ADVANCED 官方玻璃自带完整
 * 材质（MiBackgroundStyle glass token），再叠我们的边缘高光会双重描边，因此只在
 * 非 ADVANCED 材质（OS3 THEME_BLUR 为主，含 BLUR_FOR_S / STATIC 兜底）上挂载。
 */
object LiquidGlassPanelPolicy {
    fun shouldAttach(
        materialMode: OfficialExpandedMaterialMode?,
        glassEnabled: Boolean,
    ): Boolean = glassEnabled &&
        materialMode != null &&
        materialMode != OfficialExpandedMaterialMode.ADVANCED

    /** 真实折射依赖玻璃效果开启；镜像偏好里残留的孤立开关闭语义上无效。 */
    fun refractionActive(glassEnabled: Boolean, refractionEnabled: Boolean): Boolean =
        glassEnabled && refractionEnabled
}
