package hk.uwu.soundman.ui.basic

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 应用内可复用的顶部栏。
 *
 * 样式 API 只负责选择采样、降级或实色容器；采样路径不再叠加应用固定 tint 或滚动渐变，
 * 以保持与 NexioSchedule 的 ProgressiveBlurTopBar 完全一致。
 */
@Composable
internal fun AppTopBar(
    title: String,
    style: TopBarStyle,
    modifier: Modifier = Modifier,
    largeTitle: String = title,
    showSmallTitle: Boolean? = null,
    showGradientOverlay: Boolean = true,
    scrollBehavior: SharedScrollBehavior? = null,
    backdrop: Backdrop? = null,
    startAction: @Composable ((backdropAlpha: Float, shadowAlpha: Float) -> Unit)? = null,
    endAction: @Composable ((backdropAlpha: Float, shadowAlpha: Float) -> Unit)? = null,
    contentPadding: (Dp) -> Unit = {},
) {
    val material = TopBarStyleResolver.resolve(
        style = style,
        hasBackdrop = backdrop != null,
        supportsBackdropSampling = isRuntimeShaderSupported(),
    )
    val safeBackdrop = rememberSafeBackdrop(backdrop, "AppTopBar[$title]")
    val hasLargeTitle = style == TopBarStyle.LargeGlass

    val content: @Composable BoxScope.() -> Unit = {
        CollapsibleTopAppBar(
            title = title,
            modifier = modifier,
            largeTitle = largeTitle,
            showLargeTitle = hasLargeTitle,
            showSmallTitle = showSmallTitle,
            showGradientOverlay = showGradientOverlay,
            scrollBehavior = scrollBehavior,
            contentPadding = contentPadding,
            startAction = startAction,
            endAction = endAction,
        )
    }

    when (material.backdropMode) {
        TopBarBackdropMode.Sampled,
        TopBarBackdropMode.Fallback -> ProgressiveBlurTopBar(
            backdrop = if (material.backdropMode == TopBarBackdropMode.Sampled) safeBackdrop else null,
            content = content,
        )

        TopBarBackdropMode.None -> CollapsibleTopAppBar(
            title = title,
            modifier = modifier,
            largeTitle = largeTitle,
            showLargeTitle = hasLargeTitle,
            showSmallTitle = showSmallTitle,
            showGradientOverlay = showGradientOverlay,
            scrollBehavior = scrollBehavior,
            contentPadding = contentPadding,
            startAction = startAction,
            endAction = endAction,
            containerColor = if (style == TopBarStyle.Solid) {
                MiuixTheme.colorScheme.surface
            } else {
                Color.Transparent
            },
        )
    }
}
