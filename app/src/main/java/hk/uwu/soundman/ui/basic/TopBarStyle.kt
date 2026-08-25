package hk.uwu.soundman.ui.basic

import androidx.compose.runtime.Immutable

/**
 * 应用页面顶部栏的可选视觉样式。
 *
 * 动机：页面需要按场景选择大标题玻璃、紧凑玻璃、透明或实色样式，而不应复制 TopBar
 * 结构或自行处理 Backdrop 降级。
 */
internal enum class TopBarStyle {
    LargeGlass,
    CompactGlass,
    Transparent,
    Solid,
}

/** 顶部栏材质层最终采用的渲染方式。 */
internal enum class TopBarBackdropMode {
    Sampled,
    Fallback,
    None,
}

/**
 * 由 [TopBarStyleResolver] 输出的稳定材质参数。
 *
 * @property backdropMode 是否从当前容器的 Backdrop 采样。
 * @property tintAlpha 普通表面 tint 的不透明度。
 * @property usesGradient 是否在滚动后添加阅读性渐变。
 */
@Immutable
internal data class TopBarMaterialSpec(
    val backdropMode: TopBarBackdropMode,
    val tintAlpha: Float,
    val usesGradient: Boolean,
)

/** 将页面样式与设备/Backdrop 能力解析为可预测、可测试的材质结果。 */
internal object TopBarStyleResolver {
    fun resolve(
        style: TopBarStyle,
        hasBackdrop: Boolean,
        supportsBackdropSampling: Boolean,
    ): TopBarMaterialSpec = when (style) {
        TopBarStyle.LargeGlass,
        TopBarStyle.CompactGlass,
            -> TopBarMaterialSpec(
            backdropMode = if (hasBackdrop && supportsBackdropSampling) {
                TopBarBackdropMode.Sampled
            } else {
                TopBarBackdropMode.Fallback
            },
            tintAlpha = 0.24f,
            usesGradient = true,
        )

        TopBarStyle.Transparent -> TopBarMaterialSpec(
            backdropMode = TopBarBackdropMode.None,
            tintAlpha = 0f,
            usesGradient = false,
        )

        TopBarStyle.Solid -> TopBarMaterialSpec(
            backdropMode = TopBarBackdropMode.None,
            tintAlpha = 1f,
            usesGradient = false,
        )
    }
}
