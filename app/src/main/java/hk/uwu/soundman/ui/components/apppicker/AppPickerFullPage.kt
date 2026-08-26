package hk.uwu.soundman.ui.components.apppicker

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import hk.uwu.soundman.ui.basic.rememberSafeBackdrop
import hk.uwu.soundman.ui.components.LiquidTopBarButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 完整页面形式的应用选择器。
 *
 * 独立全屏页面，自带液态玻璃顶栏（× 退出 / √ 保存），
 * 适用于从设置页直接跳转进入的场景。
 *
 * 动画：页面从右侧滑入，向右侧滑出；顶栏按钮带液态玻璃材质。
 *
 * @param visible 是否可见
 * @param strings 文案集合，由外部传入
 * @param initialSelection 初始选中的包名集合
 * @param liquidGlassBackdrop 外部传入的液态玻璃 backdrop，为 null 时内部创建
 * @param hideSystemApps 是否隐藏系统应用
 * @param onDismiss 请求关闭（不保存）
 * @param onSave 保存选中包名集合并关闭
 */
@Composable
fun AppPickerFullPage(
    visible: Boolean,
    strings: AppPickerStrings,
    initialSelection: Set<String>,
    liquidGlassBackdrop: Backdrop? = null,
    hideSystemApps: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (Set<String>) -> Unit,
) {
    val context = LocalContext.current
    val state = remember(initialSelection, hideSystemApps) {
        AppPickerState(context, initialSelection, hideSystemApps)
    }
    val internalBackdrop = rememberLayerBackdrop()
    val backdrop = liquidGlassBackdrop ?: internalBackdrop

    LaunchedEffect(visible) {
        if (visible) state.load()
    }

    BackHandler(enabled = visible) { onDismiss() }

    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(
            animationSpec = tween(360, easing = CubicBezierEasing(0.34f, 1f, 0.3f, 1f)),
        ) { fullWidth -> fullWidth } + fadeIn(
            animationSpec = tween(280, easing = CubicBezierEasing(0.34f, 1f, 0.3f, 1f)),
        ),
        exit = slideOutHorizontally(
            animationSpec = tween(300, easing = CubicBezierEasing(0.34f, 1f, 0.3f, 1f)),
        ) { fullWidth -> fullWidth } + fadeOut(
            animationSpec = tween(220),
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (liquidGlassBackdrop == null) {
                        Modifier.layerBackdrop(internalBackdrop)
                    } else Modifier
                ),
        ) {
            // 内容区域
            AppPickerContent(
                state = state,
                strings = strings,
                contentPadding = PaddingValues(
                    top = WindowInsets.statusBars.asPaddingValues()
                        .calculateTopPadding() + 56.dp,
                    bottom = WindowInsets.navigationBars.asPaddingValues()
                        .calculateBottomPadding() + 16.dp,
                    start = 12.dp,
                    end = 12.dp,
                ),
                modifier = Modifier.fillMaxSize(),
                backdrop = backdrop,
            )

            // 顶栏
            FullPageTopBar(
                title = strings.title,
                closeActionDescription = strings.closeActionDescription,
                saveActionDescription = strings.saveActionDescription,
                backdrop = backdrop,
                onClose = onDismiss,
                onSave = { onSave(state.currentSelection()) },
            )
        }
    }
}

/**
 * 全屏页面的顶栏：标题居中，左侧 × 按钮，右侧 √ 按钮。
 *
 * 使用 LiquidTopBarButton 提供液态玻璃材质按钮。
 */
@Composable
private fun FullPageTopBar(
    title: String,
    closeActionDescription: String,
    saveActionDescription: String,
    backdrop: Backdrop?,
    onClose: () -> Unit,
    onSave: () -> Unit,
) {
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val safeBackdrop = rememberSafeBackdrop(backdrop, "AppPickerFullPage[$title]")

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .padding(top = statusBarPadding),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // × 退出按钮
            LiquidTopBarButton(
                onClick = onClose,
                backdrop = safeBackdrop,
                icon = MiuixIcons.Close,
                contentDescription = closeActionDescription,
            )

            // 标题
            Text(
                text = title,
                fontSize = MiuixTheme.textStyles.title4.fontSize,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                color = MiuixTheme.colorScheme.onSurface,
            )

            // √ 保存按钮
            LiquidTopBarButton(
                onClick = onSave,
                backdrop = safeBackdrop,
                icon = MiuixIcons.Ok,
                contentDescription = saveActionDescription,
            )
        }
    }
}
