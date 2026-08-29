package hk.uwu.soundman.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hk.uwu.soundman.miuix.basic.SSlider
import hk.uwu.soundman.miuix.basic.sDrawCheckerboard
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置项开关组件：Card 内嵌 BasicComponent + Switch。
 *
 * 参考 NexioSchedule 设置页设计。
 */
@Composable
fun SettingSwitchItem(
    title: String,
    summary: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.45f),
    ) {
        BasicComponent(
            onClick = { if (enabled) onCheckedChange(!checked) },
            endActions = {
                Switch(
                    checked = checked,
                    onCheckedChange = { if (enabled) onCheckedChange(it) },
                    enabled = enabled,
                )
            },
            insideMargin = PaddingValues(16.dp),
        ) {
            Text(
                text = title,
                fontSize = MiuixTheme.textStyles.headline1.fontSize,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
            )
            if (summary != null) {
                Text(
                    text = summary,
                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

/**
 * 设置项滑杆组件：Card 内嵌标题/摘要与 SSlider。
 *
 * 拖动过程中只更新调用方的草稿值，[onValueChangeFinished] 时再落盘，
 * 避免每一帧拖动都触发 SharedPreferences commit 与跨进程镜像。
 */
@Composable
fun SettingSliderItem(
    title: String,
    summary: String? = null,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    enabled: Boolean = true,
    valueLabel: String? = null,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.45f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = title,
                    fontSize = MiuixTheme.textStyles.headline1.fontSize,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                if (valueLabel != null) {
                    Text(
                        text = valueLabel,
                        fontSize = MiuixTheme.textStyles.headline1.fontSize,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.primary,
                    )
                }
            }
            if (summary != null) {
                Text(
                    text = summary,
                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            SSlider(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                valueRange = valueRange,
                steps = steps,
                onValueChangeFinished = onValueChangeFinished,
            )
        }
    }
}

/**
 * 设置项按钮组件：Card 内嵌 BasicComponent，可点击。
 */
@Composable
fun SettingActionItem(
    title: String,
    summary: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        BasicComponent(
            onClick = onClick,
            insideMargin = PaddingValues(16.dp),
        ) {
            Text(
                text = title,
                fontSize = MiuixTheme.textStyles.headline1.fontSize,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
            )
            if (summary != null) {
                Text(
                    text = summary,
                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

/**
 * 设置项颜色组件：Card 内嵌 BasicComponent + 当前颜色色块。
 *
 * 色块铺在棋盘格底上，半透明颜色也能直观预览；点击整项触发颜色选择器。
 */
@Composable
fun SettingColorItem(
    title: String,
    summary: String? = null,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.45f),
    ) {
        BasicComponent(
            onClick = { if (enabled) onClick() },
            endActions = {
                Box(
                    modifier = Modifier
                        .size(width = 42.dp, height = 26.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .sDrawCheckerboard()
                        .background(color)
                        .border(
                            width = 0.5.dp,
                            color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(13.dp),
                        ),
                )
            },
            insideMargin = PaddingValues(16.dp),
        ) {
            Text(
                text = title,
                fontSize = MiuixTheme.textStyles.headline1.fontSize,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
            )
            if (summary != null) {
                Text(
                    text = summary,
                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}
