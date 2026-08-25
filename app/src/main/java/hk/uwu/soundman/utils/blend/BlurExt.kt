package hk.uwu.soundman.utils.blend

import androidx.compose.runtime.Composable
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 创建并记住一个 [LayerBackdrop]，用于 textureBlur 液态玻璃效果。
 *
 * 动机：与 REAREye 的 rememberBlurBackdrop 一致，在不支持 RuntimeShader 的设备上返回 null。
 * 使用 isRuntimeShaderSupported 替代 REAREye 中的 isRenderEffectSupported（miuix 0.9.3 中后者不存在）。
 */
@Composable
fun rememberBlurBackdrop(): LayerBackdrop? {
    if (!isRuntimeShaderSupported()) return null
    val surfaceColor = MiuixTheme.colorScheme.surface
    return rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
}
