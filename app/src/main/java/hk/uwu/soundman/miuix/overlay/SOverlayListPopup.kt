// Copyright 2025, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package hk.uwu.soundman.miuix.overlay

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import hk.uwu.soundman.miuix.basic.SPopupPositionProvider
import hk.uwu.soundman.miuix.layout.SListPopupLayout
import hk.uwu.soundman.miuix.layout.sLiquidDropdownPositionProvider
import top.yukonga.miuix.kmp.utils.MiuixPopupUtils.Companion.PopupLayout

private val LocalMinWidth = 200.dp

/**
 * 带列表的弹窗（S 版本）。
 *
 * @param show 是否显示弹窗
 * @param popupModifier 弹窗修饰符
 * @param popupPositionProvider 弹窗位置提供者
 * @param alignment 弹窗对齐方式
 * @param enableWindowDim 是否启用背景变暗
 * @param onDismissRequest 关闭弹窗的回调
 * @param onDismissFinished 关闭动画完成后的回调
 * @param maxHeight 弹窗最大高度
 * @param minWidth 弹窗最小宽度
 * @param renderInRootScaffold 是否在根Scaffold中渲染
 * @param liquidGlassBackdrop 液体玻璃模糊效果的Backdrop
 * @param onFractionProgress 缩放进度回调
 * @param revealLimitHeight 揭示限制高度
 * @param content 弹窗内容
 */
@Composable
fun SOverlayListPopup(
    show: Boolean,
    popupModifier: Modifier = Modifier,
    popupPositionProvider: SPopupPositionProvider = sLiquidDropdownPositionProvider(),
    alignment: SPopupPositionProvider.Align = SPopupPositionProvider.Align.Start,
    enableWindowDim: Boolean = true,
    onDismissRequest: (() -> Unit)? = null,
    onDismissFinished: (() -> Unit)? = null,
    maxHeight: Dp? = null,
    minWidth: Dp = LocalMinWidth,
    renderInRootScaffold: Boolean = true,
    liquidGlassBackdrop: Backdrop? = null,
    onFractionProgress: ((Float) -> Unit)? = null,
    revealLimitHeight: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    SListPopupLayout(
        show = show,
        popupHost = { visible, hostContent ->
            val visibleState = remember { mutableStateOf(false) }
            visibleState.value = visible
            PopupLayout(
                visible = visibleState,
                enableWindowDim = false,
                enableBackHandler = false,
                enterTransition = EnterTransition.None,
                exitTransition = ExitTransition.None,
                renderInRootScaffold = renderInRootScaffold,
            ) {
                hostContent()
            }
        },
        popupModifier = popupModifier,
        popupPositionProvider = popupPositionProvider,
        alignment = alignment,
        enableWindowDim = enableWindowDim,
        onDismissRequest = onDismissRequest,
        onDismissFinished = onDismissFinished,
        maxHeight = maxHeight,
        minWidth = minWidth,
        liquidGlassBackdrop = liquidGlassBackdrop,
        onFractionProgress = onFractionProgress,
        revealLimitHeight = revealLimitHeight,
        content = content,
    )
}
