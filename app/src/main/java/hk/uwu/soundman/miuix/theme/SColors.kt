// Copyright 2025, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package hk.uwu.soundman.miuix.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * S-customized light color scheme.
 *
 * Overrides 3 color values from the original miuix lightColorScheme:
 * - secondary: 0xFFE6E6E6 -> 0xFFF4F4F4
 * - secondaryContainer: 0xFFF0F0F0 -> 0xFFECECEC
 * - surface: 0xFFF7F7F7 -> 0xFFF4F4F4
 */
@Composable
fun sLightColorScheme() = lightColorScheme(
    secondary = Color(0xFFF4F4F4),
    secondaryContainer = Color(0xFFECECEC),
    surface = Color(0xFFF4F4F4),
)

/**
 * S-customized dark color scheme.
 *
 * Overrides 2 color values from the original miuix darkColorScheme:
 * - secondary: 0xFF505050 -> 0xFF363636
 * - secondaryContainerVariant: 0xFF4F4F4F -> 0xFFF2F2F2
 */
@Composable
fun sDarkColorScheme() = darkColorScheme(
    secondary = Color(0xFF363636),
    secondaryContainerVariant = Color(0xFFF2F2F2),
)
