// Copyright 2025, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package hk.uwu.soundman.miuix.basic

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import top.yukonga.miuix.kmp.basic.SmallTitleDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * A [SSmallTitle] with S-customized Miuix style.
 *
 * Differences from the original miuix SmallTitle:
 * - textColor defaults to a hardcoded Color(0xFF8F9CAE) instead of
 *   MiuixTheme.colorScheme.onBackgroundVariant.
 * - The text style uses FontWeight.Medium.
 *
 * @param text The text to be displayed in the [SSmallTitle].
 * @param modifier The modifier to be applied to the [SSmallTitle].
 * @param textColor The color of the [SSmallTitle].
 * @param insideMargin The margin inside the [SSmallTitle].
 */
@Composable
@NonRestartableComposable
fun SSmallTitle(
    text: String,
    modifier: Modifier = Modifier,
    textColor: Color = Color(0xFF8F9CAE),
    insideMargin: PaddingValues = SmallTitleDefaults.InsideMargin,
) {
    Text(
        modifier = modifier.padding(insideMargin),
        text = text,
        style = MiuixTheme.textStyles.subtitle.copy(fontWeight = FontWeight.Medium),
        color = textColor,
    )
}
