package hk.uwu.soundman.ui.components.apppicker

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * BottomSheet 形式的应用选择器。
 *
 * 使用 MIUIX [OverlayBottomSheet] 提供标准 MIUIX 风格的底部抽屉，
 * 顶栏左侧 × 按钮退出不保存，右侧 √ 按钮保存选中结果。
 *
 * 按钮为纯图标（无圆形背景），背景色使用 MIUIX 默认 surface 色。
 *
 * @param show 是否显示
 * @param strings 文案集合，由外部传入
 * @param initialSelection 初始选中的包名集合
 * @param hideSystemApps 是否隐藏系统应用
 * @param onDismiss 请求关闭（不保存）
 * @param onSave 保存选中包名集合并关闭
 */
@Composable
fun AppPickerBottomSheet(
    show: Boolean,
    strings: AppPickerStrings,
    initialSelection: Set<String>,
    hideSystemApps: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (Set<String>) -> Unit,
) {
    val context = LocalContext.current
    val state = remember(initialSelection, hideSystemApps) {
        AppPickerState(context, initialSelection, hideSystemApps)
    }

    LaunchedEffect(show) {
        if (show) state.load()
    }

    OverlayBottomSheet(
        show = show,
        title = strings.title,
        onDismissRequest = onDismiss,
        startAction = {
            SheetIconAction(
                icon = MiuixIcons.Close,
                contentDescription = strings.closeActionDescription,
                onClick = onDismiss,
            )
        },
        endAction = {
            SheetIconAction(
                icon = MiuixIcons.Ok,
                contentDescription = strings.saveActionDescription,
                onClick = { onSave(state.currentSelection()) },
            )
        },
    ) {
        AppPickerContent(
            state = state,
            strings = strings,
            contentPadding = PaddingValues(bottom = 24.dp),
        )
    }
}

/**
 * BottomSheet 顶栏纯图标按钮：无背景，仅图标 + 点击。
 *
 * @param icon 图标
 * @param contentDescription 无障碍描述
 * @param onClick 点击回调
 */
@Composable
private fun SheetIconAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        modifier = Modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        tint = MiuixTheme.colorScheme.onSurface,
    )
}
