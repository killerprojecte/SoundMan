// Copyright 2025, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package hk.uwu.soundman.miuix.overlay

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import com.kyant.backdrop.Backdrop
import hk.uwu.soundman.ui.effects.miuix.DialogContentLayout
import hk.uwu.soundman.ui.effects.miuix.DialogDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.MiuixPopupUtils.Companion.DialogLayout

/**
 * A dialog with a title, a summary, and other contents（S 版本）。
 *
 * @param show Whether the [SOverlayDialog] is shown.
 * @param modifier The modifier to be applied to the [SOverlayDialog].
 * @param title The title of the [SOverlayDialog].
 * @param titleColor The color of the title.
 * @param summary The summary of the [SOverlayDialog].
 * @param summaryColor The color of the summary.
 * @param backgroundColor The background color of the [SOverlayDialog].
 * @param liquidGlassBackdrop The [Backdrop] for liquidGlass blur effect. When non-null, enables blur + edge light.
 * @param enableWindowDim Whether to enable window dimming when the [SOverlayDialog] is shown.
 * @param onDismissRequest Will called when the user tries to dismiss the Dialog by clicking outside or pressing the back button.
 * @param onDismissFinished The callback when the [SOverlayDialog] is completely dismissed.
 * @param outsideMargin The margin outside the [SOverlayDialog].
 * @param insideMargin The margin inside the [SOverlayDialog].
 * @param defaultWindowInsetsPadding Whether to apply default window insets padding to the [SOverlayDialog].
 * @param renderInRootScaffold Whether to render the dialog in the root (outermost) Scaffold.
 *   When true (default), the dialog covers the full screen. When false, it renders within the
 *   current Scaffold's bounds.
 * @param content The [Composable] content of the [SOverlayDialog].
 */
@Composable
fun SOverlayDialog(
    show: Boolean,
    modifier: Modifier = Modifier,
    title: String? = null,
    titleColor: Color = DialogDefaults.titleColor(),
    summary: String? = null,
    summaryColor: Color = DialogDefaults.summaryColor(),
    backgroundColor: Color = DialogDefaults.backgroundColor(),
    liquidGlassBackdrop: Backdrop? = null,
    enableWindowDim: Boolean = true,
    onDismissRequest: (() -> Unit)? = null,
    onDismissFinished: (() -> Unit)? = null,
    outsideMargin: DpSize = DialogDefaults.outsideMargin,
    insideMargin: DpSize = DialogDefaults.insideMargin,
    defaultWindowInsetsPadding: Boolean = true,
    renderInRootScaffold: Boolean = true,
    content: @Composable () -> Unit,
) {
    val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f

    DialogContentLayout(
        show = show,
        titleColor = titleColor,
        summaryColor = summaryColor,
        backgroundColor = backgroundColor,
        liquidGlassBackdrop = liquidGlassBackdrop,
        isDark = isDark,
        outsideMargin = outsideMargin,
        insideMargin = insideMargin,
        popupHost = { visible, hostContent ->
            val visibleState = remember { mutableStateOf(false) }
            visibleState.value = visible
            DialogLayout(
                visible = visibleState,
                enableWindowDim = false,
                enterTransition = EnterTransition.None,
                exitTransition = ExitTransition.None,
                enableAutoLargeScreen = false,
                renderInRootScaffold = renderInRootScaffold,
            ) {
                hostContent()
            }
        },
        modifier = modifier,
        title = title,
        summary = summary,
        enableWindowDim = enableWindowDim,
        onDismissRequest = onDismissRequest,
        onDismissFinished = onDismissFinished,
        defaultWindowInsetsPadding = defaultWindowInsetsPadding,
        content = content,
    )
}

private fun Color.luminance(): Float {
    return 0.299f * red + 0.587f * green + 0.114f * blue
}
