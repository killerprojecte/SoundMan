// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package hk.uwu.soundman.miuix.popup

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import hk.uwu.soundman.miuix.basic.SPopupPositionProvider
import hk.uwu.soundman.miuix.overlay.SOverlayDialog
import hk.uwu.soundman.miuix.overlay.SOverlayListPopup
import top.yukonga.miuix.kmp.basic.DropdownColors
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.TextButton

/**
 * 单个 [DropdownEntry] 的弹窗。
 *
 * @param entry 下拉条目
 * @param show 是否显示
 * @param onDismiss 关闭回调
 * @param onDismissFinished 关闭动画完成回调
 * @param maxHeight 最大高度
 * @param dropdownColors 下拉颜色
 * @param renderInRootScaffold 是否在根Scaffold中渲染
 * @param collapseOnSelection 选中后是否关闭
 * @param liquidGlassBackdrop 液体玻璃模糊效果的Backdrop
 */
@Composable
fun OverlayDropdownPopup(
    entry: DropdownEntry,
    show: Boolean,
    onDismiss: () -> Unit,
    onDismissFinished: () -> Unit,
    maxHeight: Dp?,
    dropdownColors: DropdownColors,
    renderInRootScaffold: Boolean,
    collapseOnSelection: Boolean = true,
    liquidGlassBackdrop: Backdrop? = null,
    onFractionProgress: ((Float) -> Unit)? = null,
    revealLimitHeight: Dp = 0.dp,
) {
    val entries = remember(entry) { listOf(entry) }
    OverlayDropdownPopup(
        entries = entries,
        show = show,
        onDismiss = onDismiss,
        onDismissFinished = onDismissFinished,
        maxHeight = maxHeight,
        dropdownColors = dropdownColors,
        renderInRootScaffold = renderInRootScaffold,
        collapseOnSelection = collapseOnSelection,
        liquidGlassBackdrop = liquidGlassBackdrop,
        onFractionProgress = onFractionProgress,
        revealLimitHeight = revealLimitHeight,
    )
}

/**
 * 多个 [DropdownEntry] 组的弹窗。
 *
 * @param entries 下拉条目组
 * @param show 是否显示
 * @param onDismiss 关闭回调
 * @param onDismissFinished 关闭动画完成回调
 * @param maxHeight 最大高度
 * @param dropdownColors 下拉颜色
 * @param renderInRootScaffold 是否在根Scaffold中渲染
 * @param collapseOnSelection 选中后是否关闭
 * @param liquidGlassBackdrop 液体玻璃模糊效果的Backdrop
 */
@Composable
fun OverlayDropdownPopup(
    entries: List<DropdownEntry>,
    show: Boolean,
    onDismiss: () -> Unit,
    onDismissFinished: () -> Unit,
    maxHeight: Dp?,
    dropdownColors: DropdownColors,
    renderInRootScaffold: Boolean,
    collapseOnSelection: Boolean = entries.size <= 1,
    liquidGlassBackdrop: Backdrop? = null,
    onFractionProgress: ((Float) -> Unit)? = null,
    revealLimitHeight: Dp = 0.dp,
) {
    val hapticFeedback = LocalHapticFeedback.current
    val currentEntries by rememberUpdatedState(entries)
    val currentCollapseOnSelection by rememberUpdatedState(collapseOnSelection)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val currentHapticFeedback by rememberUpdatedState(hapticFeedback)
    val onItemClicked: (Int, Int) -> Unit = remember {
        { entryIdx, itemIdx ->
            currentHapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
            currentEntries.getOrNull(entryIdx)?.let { entry ->
                entry.items.getOrNull(itemIdx)?.onClick?.invoke()
            }
            if (currentCollapseOnSelection) {
                currentOnDismiss()
            }
        }
    }
    SOverlayListPopup(
        show = show,
        alignment = SPopupPositionProvider.Align.End,
        onDismissRequest = onDismiss,
        onDismissFinished = onDismissFinished,
        maxHeight = maxHeight,
        renderInRootScaffold = renderInRootScaffold,
        liquidGlassBackdrop = liquidGlassBackdrop,
        onFractionProgress = onFractionProgress,
        revealLimitHeight = revealLimitHeight,
    ) {
        ListPopupColumn {
            DropdownEntriesPopupContent(
                entries = entries,
                dropdownColors = dropdownColors,
                onItemClick = onItemClicked,
            )
        }
    }
}

/**
 * [DropdownEntry] 的弹窗对话框。
 *
 * @param entry 下拉条目
 * @param title 标题
 * @param dialogButtonString 对话框按钮文字
 * @param show 是否显示
 * @param onDismiss 关闭回调
 * @param onDismissFinished 关闭动画完成回调
 * @param dropdownColors 下拉颜色
 * @param popupModifier 弹窗修饰符
 * @param renderInRootScaffold 是否在根Scaffold中渲染
 * @param collapseOnSelection 选中后是否关闭
 * @param liquidGlassBackdrop 液体玻璃模糊效果的Backdrop
 */
@Composable
fun OverlayDropdownDialog(
    entry: DropdownEntry,
    title: String,
    dialogButtonString: String,
    show: Boolean,
    onDismiss: () -> Unit,
    onDismissFinished: () -> Unit,
    dropdownColors: DropdownColors,
    popupModifier: Modifier = Modifier,
    renderInRootScaffold: Boolean = true,
    collapseOnSelection: Boolean = true,
    liquidGlassBackdrop: Backdrop? = null,
) {
    val entries = remember(entry) { listOf(entry) }
    OverlayDropdownDialog(
        entries = entries,
        title = title,
        dialogButtonString = dialogButtonString,
        show = show,
        onDismiss = onDismiss,
        onDismissFinished = onDismissFinished,
        dropdownColors = dropdownColors,
        popupModifier = popupModifier,
        renderInRootScaffold = renderInRootScaffold,
        collapseOnSelection = collapseOnSelection,
        liquidGlassBackdrop = liquidGlassBackdrop,
    )
}

/**
 * 多个 [DropdownEntry] 组的弹窗对话框。
 *
 * @param entries 下拉条目组
 * @param title 标题
 * @param dialogButtonString 对话框按钮文字
 * @param show 是否显示
 * @param onDismiss 关闭回调
 * @param onDismissFinished 关闭动画完成回调
 * @param dropdownColors 下拉颜色
 * @param popupModifier 弹窗修饰符
 * @param renderInRootScaffold 是否在根Scaffold中渲染
 * @param collapseOnSelection 选中后是否关闭
 * @param liquidGlassBackdrop 液体玻璃模糊效果的Backdrop
 */
@Composable
fun OverlayDropdownDialog(
    entries: List<DropdownEntry>,
    title: String,
    dialogButtonString: String,
    show: Boolean,
    onDismiss: () -> Unit,
    onDismissFinished: () -> Unit,
    dropdownColors: DropdownColors,
    popupModifier: Modifier = Modifier,
    renderInRootScaffold: Boolean = true,
    collapseOnSelection: Boolean = entries.size <= 1,
    liquidGlassBackdrop: Backdrop? = null,
) {
    val hapticFeedback = LocalHapticFeedback.current
    val currentEntries by rememberUpdatedState(entries)
    val currentCollapseOnSelection by rememberUpdatedState(collapseOnSelection)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val currentHapticFeedback by rememberUpdatedState(hapticFeedback)
    val onItemClicked: (Int, Int) -> Unit = remember {
        { entryIdx, itemIdx ->
            currentHapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
            currentEntries.getOrNull(entryIdx)?.let { entry ->
                entry.items.getOrNull(itemIdx)?.onClick?.invoke()
            }
            if (currentCollapseOnSelection) {
                currentOnDismiss()
            }
        }
    }
    SOverlayDialog(
        show = show,
        modifier = popupModifier,
        title = title,
        onDismissRequest = onDismiss,
        onDismissFinished = onDismissFinished,
        insideMargin = DpSize(0.dp, 24.dp),
        renderInRootScaffold = renderInRootScaffold,
        liquidGlassBackdrop = liquidGlassBackdrop,
        content = {
            Layout(
                content = {
                    LazyColumn {
                        dropdownEntriesDialogItems(
                            entries = entries,
                            dropdownColors = dropdownColors,
                            onItemClick = onItemClicked,
                        )
                    }
                    TextButton(
                        modifier = Modifier
                            .padding(start = 24.dp, top = 12.dp, end = 24.dp)
                            .fillMaxWidth(),
                        text = dialogButtonString,
                        minHeight = 50.dp,
                        onClick = onDismiss,
                    )
                },
            ) { measurables, constraints ->
                if (measurables.size != 2) {
                    layout(0, 0) { }
                } else {
                    val button = measurables[1].measure(constraints)
                    val lazyList = measurables[0].measure(
                        constraints.copy(
                            maxHeight = constraints.maxHeight - button.height,
                        ),
                    )
                    layout(constraints.maxWidth, lazyList.height + button.height) {
                        lazyList.place(0, 0)
                        button.place(0, lazyList.height)
                    }
                }
            }
        },
    )
}
