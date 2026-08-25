package hk.uwu.soundman.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * 模块状态卡片的配色方案。
 */
@Immutable
data class StatusCardPalette(
    val container: Color,
    val icon: Color,
    val title: Color,
    val summary: Color,
)

/**
 * 硬编码的状态卡片配色，对齐 REAREye 非 Monet 主题下的配色。
 */
private val ActivatedPalette = StatusCardPalette(
    container = Color(0xFFDFFAE4),
    icon = Color(0xFF36D167),
    title = Color(0xFF1E5A31),
    summary = Color(0xFF2C7D45),
)

private val DeactivatedPalette = StatusCardPalette(
    container = Color(0xFFF8E2E2),
    icon = Color(0xFFE06767),
    title = Color(0xFF7A2A2A),
    summary = Color(0xFF9A4D4D),
)

/**
 * 基于 Monet 主题色动态生成状态卡片配色。
 *
 * 动机：与 REAREye 的 rememberStatusCardPalette 一致，在 Monet 主题下使用 lerp
 * 从 surface/onSurface 混合出配色，而非硬编码。
 */
@Composable
fun rememberStatusCardPalette(
    accent: Color,
    containerStrength: Float = 0.22f,
    iconStrength: Float = 0.86f,
    titleStrength: Float = 0.46f,
    summaryStrength: Float = 0.28f,
): StatusCardPalette {
    val colorScheme = MiuixTheme.colorScheme

    return androidx.compose.runtime.remember(
        accent,
        colorScheme.surface,
        colorScheme.onSurface,
        colorScheme.onSurfaceVariantSummary,
        containerStrength,
        iconStrength,
        titleStrength,
        summaryStrength,
    ) {
        StatusCardPalette(
            container = lerp(colorScheme.surface, accent, containerStrength),
            icon = lerp(colorScheme.onSurfaceVariantSummary, accent, iconStrength),
            title = lerp(colorScheme.onSurface, accent, titleStrength),
            summary = lerp(colorScheme.onSurfaceVariantSummary, accent, summaryStrength),
        )
    }
}

/**
 * 模块激活状态卡片，参考 REAREye 的 WorkingStatusCard 设计。
 *
 * 大卡片展示模块是否已激活，右下角装饰性大图标。
 * 支持硬编码配色和 Monet 动态配色两种模式。
 */
@Composable
fun ModuleStatusCard(
    title: String,
    summary: String,
    activated: Boolean,
    modifier: Modifier = Modifier,
    palette: StatusCardPalette? = null,
    statusIcon: ImageVector? = null,
    onLongPress: (() -> Unit)? = null,
) {
    val resolvedPalette = palette ?: if (activated) ActivatedPalette else DeactivatedPalette
    val resolvedIcon = statusIcon ?: if (activated) {
        Icons.Outlined.CheckCircle
    } else {
        Icons.Outlined.Warning
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(190.dp),
        colors = CardDefaults.defaultColors(color = resolvedPalette.container),
        insideMargin = PaddingValues(14.dp),
        pressFeedbackType = PressFeedbackType.Tilt,
        showIndication = false,
        onLongPress = if (onLongPress != null) {
            { onLongPress() }
        } else {
            null
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Icon(
                imageVector = resolvedIcon,
                contentDescription = null,
                tint = resolvedPalette.icon,
                modifier = Modifier
                    .size(198.dp)
                    .align(Alignment.BottomEnd)
                    .offset(x = 72.dp, y = 56.dp),
            )
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = title,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = resolvedPalette.title,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = summary,
                    style = MiuixTheme.textStyles.body2,
                    color = resolvedPalette.summary,
                )
            }
        }
    }
}
