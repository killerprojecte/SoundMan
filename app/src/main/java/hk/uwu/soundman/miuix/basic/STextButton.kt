// Copyright 2025, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package hk.uwu.soundman.miuix.basic

import androidx.compose.foundation.Indication
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import com.kyant.capsule.ContinuousRoundedRectangle
import hk.uwu.soundman.ui.utils.isAppDarkTheme
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * A [STextButton] component with Miuix style.
 *
 * This is an external reimplementation of the modified TextButton from SSchedule-ref.
 * It adds support for alpha-based background coloring, custom textStyle and textColor.
 *
 * @param text The text of the [STextButton].
 * @param onClick The callback when the [STextButton] is clicked.
 * @param modifier The modifier to be applied to the [STextButton].
 * @param enabled Whether the [STextButton] is enabled.
 * @param cornerRadius The corner radius of the [STextButton].
 * @param minWidth The minimum width of the [STextButton].
 * @param minHeight The minimum height of the [STextButton].
 * @param colors The [STextButtonColors] of the [STextButton].
 * @param insideMargin The margin inside the [STextButton].
 * @param textStyle Optional custom text style. Defaults to [MiuixTheme.textStyles.button].
 * @param textColor Optional custom text color. Overrides [STextButtonColors.textColor] when set.
 * @param interactionSource The [MutableInteractionSource] to be used for the [STextButton].
 * @param indication The [Indication] to be used for the [STextButton].
 */
@Composable
fun STextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    cornerRadius: Dp = ButtonDefaults.CornerRadius,
    minWidth: Dp = ButtonDefaults.MinWidth,
    minHeight: Dp = ButtonDefaults.MinHeight,
    colors: STextButtonColors = SButtonDefaults.sTextButtonColors(),
    insideMargin: PaddingValues = ButtonDefaults.InsideMargin,
    textStyle: TextStyle? = null,
    textColor: Color? = null,
    interactionSource: MutableInteractionSource? = null,
    indication: Indication? = LocalIndication.current,
) {
    @Suppress("NAME_SHADOWING")
    val interactionSource = interactionSource ?: remember { MutableInteractionSource() }
    val bgColor = colors.color.copy(alpha = colors.alpha)
    val disabledBgColor = colors.disabledColor
    val containerColor = if (enabled) bgColor else disabledBgColor
    val contentColor = if (enabled) textColor ?: colors.textColor else colors.disabledTextColor
    val rowModifier = remember(minWidth, minHeight, insideMargin) {
        Modifier
            .defaultMinSize(minWidth = minWidth, minHeight = minHeight)
            .padding(insideMargin)
    }
    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Box(
            modifier = modifier
                .semantics { role = Role.Button }
                .background(
                    color = containerColor,
                    shape = ContinuousRoundedRectangle(cornerRadius)
                )
                .clip(ContinuousRoundedRectangle(cornerRadius))
                .clickable(
                    interactionSource = interactionSource,
                    indication = indication,
                    enabled = enabled,
                    onClick = onClick,
                ),
            propagateMinConstraints = true,
        ) {
            Row(
                modifier = rowModifier,
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = text,
                    style = (textStyle
                        ?: MiuixTheme.textStyles.button).copy(fontWeight = FontWeight.Medium),
                )
            }
        }
    }
}

/**
 * Object providing default values and color schemes for [STextButton].
 */
object SButtonDefaults {

    /**
     * The default [STextButtonColors] for all text buttons.
     */
    @Composable
    fun sTextButtonColors(
        color: Color = if (isAppDarkTheme()) Color.White else Color.Black,
        disabledColor: Color = MiuixTheme.colorScheme.disabledSecondaryVariant,
        textColor: Color = MiuixTheme.colorScheme.onSecondaryVariant,
        disabledTextColor: Color = MiuixTheme.colorScheme.disabledOnSecondaryVariant,
        alpha: Float = 0.06f,
    ): STextButtonColors = remember(color, disabledColor, textColor, disabledTextColor, alpha) {
        STextButtonColors(
            color = color,
            disabledColor = disabledColor,
            textColor = textColor,
            disabledTextColor = disabledTextColor,
            alpha = alpha,
        )
    }

    /**
     * The [STextButtonColors] for primary text buttons.
     */
    @Composable
    fun sPrimaryButtonColors(
        color: Color = MiuixTheme.colorScheme.primary,
        disabledColor: Color = MiuixTheme.colorScheme.disabledPrimaryButton,
        textColor: Color = MiuixTheme.colorScheme.onPrimary,
        disabledTextColor: Color = MiuixTheme.colorScheme.disabledOnPrimaryButton,
        alpha: Float = 0.84f,
    ): STextButtonColors = remember(color, disabledColor, textColor, disabledTextColor, alpha) {
        STextButtonColors(
            color = color,
            disabledColor = disabledColor,
            textColor = textColor,
            disabledTextColor = disabledTextColor,
            alpha = alpha,
        )
    }
}

/**
 * Color scheme for [STextButton].
 *
 * @param color The background color (before alpha is applied).
 * @param disabledColor The background color when disabled.
 * @param textColor The text color.
 * @param disabledTextColor The text color when disabled.
 * @param alpha The alpha value applied to [color] for the background.
 */
@Immutable
data class STextButtonColors(
    val color: Color,
    val disabledColor: Color,
    val textColor: Color,
    val disabledTextColor: Color,
    val alpha: Float = 1f,
)
