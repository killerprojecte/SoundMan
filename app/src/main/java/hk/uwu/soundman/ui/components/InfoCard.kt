package hk.uwu.soundman.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 通用信息行：标题 + 描述值。
 *
 * 参考 REAREye 的 InfoLine 组件。
 */
@Composable
fun InfoLine(title: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(text = title, style = MiuixTheme.textStyles.headline1)
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

/**
 * 模块信息卡片，展示多行 InfoLine。
 *
 * 参考 REAREye 的 ModuleInfoCard 设计。
 */
@Composable
fun ModuleInfoCard(
    items: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        insideMargin = PaddingValues(16.dp),
    ) {
        items.forEachIndexed { index, (title, value) ->
            InfoLine(title = title, value = value)
            if (index < items.lastIndex) {
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}
