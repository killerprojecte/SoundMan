// Copyright 2025, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package hk.uwu.soundman.miuix.basic

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlendModeColorFilter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.capsule.ContinuousRoundedRectangle
import top.yukonga.miuix.kmp.basic.DropdownColors
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.basic.ArrowUpDown
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * A dropdown arrow end action for use in [RowScope].
 */
@Composable
@NonRestartableComposable
fun RowScope.SDropdownArrowEndAction(
    actionColor: Color,
    modifier: Modifier = Modifier,
) {
    val colorFilter = remember(actionColor) { ColorFilter.tint(actionColor) }
    Image(
        modifier = modifier
            .size(
                width = DropdownDefaults.ArrowSize.width,
                height = DropdownDefaults.ArrowSize.height
            )
            .align(Alignment.CenterVertically),
        imageVector = MiuixIcons.Basic.ArrowUpDown,
        colorFilter = colorFilter,
        contentDescription = null,
    )
}

/**
 * The implementation of the dropdown item.
 *
 * 本地覆盖版本：条目使用圆角矩形裁剪 + clickable 点击反馈，外层加 8dp 内边距，
 * 首行顶部/末行底部额外 8dp。
 *
 * @param item The [DropdownItem] to render.
 * @param optionSize The total number of options in the dropdown.
 * @param isSelected Whether this item is selected.
 * @param index The index of this item.
 * @param dropdownColors The [DropdownColors] for styling.
 * @param enabled Whether this item is enabled.
 * @param dialogMode Whether the dropdown is in dialog mode.
 * @param hasSubmenu Whether this item has a submenu.
 * @param isFirst Whether this is the first item.
 * @param isLast Whether this is the last item.
 * @param onSelectedIndexChange Callback when the selected index changes.
 */
@Composable
fun SDropdownImpl(
    item: DropdownItem,
    optionSize: Int,
    isSelected: Boolean,
    index: Int,
    dropdownColors: DropdownColors = DropdownDefaults.dropdownColors(),
    enabled: Boolean = item.enabled,
    dialogMode: Boolean = false,
    hasSubmenu: Boolean = false,
    isFirst: Boolean = index == 0,
    isLast: Boolean = index == optionSize - 1,
    onSelectedIndexChange: (Int) -> Unit,
) {
    val backgroundColor = if (isSelected) {
        dropdownColors.selectedContainerColor
    } else {
        dropdownColors.containerColor
    }
    val backgroundColorState = rememberUpdatedState(backgroundColor)

    val checkColor = when {
        !isSelected -> Color.Transparent
        !enabled -> MiuixTheme.colorScheme.disabledOnSecondaryVariant
        else -> dropdownColors.selectedIndicatorColor
    }

    val titleColor = when {
        !enabled -> MiuixTheme.colorScheme.disabledOnSecondaryVariant
        isSelected -> dropdownColors.selectedContentColor
        else -> dropdownColors.contentColor
    }

    val summaryColor = when {
        !enabled -> MiuixTheme.colorScheme.disabledOnSecondaryVariant
        isSelected -> dropdownColors.selectedSummaryColor
        else -> dropdownColors.summaryColor
    }

    val containerModifier = remember(dialogMode) {
        if (dialogMode) {
            Modifier
                .heightIn(min = DropdownDefaults.MinHeight)
                .widthIn(min = DropdownDefaults.MinWidth)
                .fillMaxWidth()
        } else {
            Modifier.fillMaxWidth()
        }
    }
    val innerRowModifier = remember(dialogMode) {
        if (dialogMode) Modifier else Modifier.widthIn(max = DropdownDefaults.MaxItemTextWidth)
    }

    val currentOnSelectedIndexChange by rememberUpdatedState(onSelectedIndexChange)
    val role = if (hasSubmenu) Role.Button else Role.RadioButton
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .padding(
                start = 8.dp,
                end = 8.dp,
                top = if (isFirst) 8.dp else 0.dp,
                bottom = if (isLast) 8.dp else 0.dp,
            )
            .clip(ContinuousRoundedRectangle(17.dp))
            .drawBehind { drawRect(backgroundColorState.value) }
            .clickable(
                enabled = enabled,
                role = role,
                onClick = { currentOnSelectedIndexChange(index) },
            )
            .then(containerModifier)
            .padding(horizontal = 14.dp, vertical = 10.5.dp),
    ) {
        Row(
            modifier = innerRowModifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            item.icon?.let { it(SIconCellModifier) }
            Column {
                Text(
                    text = item.text,
                    fontSize = 15.6.sp,
                    fontWeight = FontWeight.Medium,
                    color = titleColor,
                )
                item.summary?.let {
                    Text(
                        text = it,
                        fontSize = MiuixTheme.textStyles.body2.fontSize,
                        color = summaryColor,
                    )
                }
            }
        }

        if (hasSubmenu) {
            val chevronColor = when {
                !enabled -> MiuixTheme.colorScheme.disabledOnSecondaryVariant
                isSelected -> dropdownColors.selectedContentColor
                else -> dropdownColors.summaryColor
            }
            val chevronColorFilter = remember(chevronColor) {
                BlendModeColorFilter(chevronColor, BlendMode.SrcIn)
            }
            Image(
                modifier = SChevronIconBaseModifier,
                imageVector = MiuixIcons.Basic.ArrowRight,
                colorFilter = chevronColorFilter,
                contentDescription = null,
            )
        } else {
            val checkColorFilter = remember(checkColor) {
                BlendModeColorFilter(checkColor, BlendMode.SrcIn)
            }
            Image(
                modifier = SCheckIconBaseModifier,
                imageVector = MiuixIcons.Basic.Check,
                colorFilter = checkColorFilter,
                contentDescription = null,
            )
        }
    }
}

/**
 * Overload of [SDropdownImpl] that accepts a text string instead of a [DropdownItem].
 */
@Composable
@NonRestartableComposable
fun SDropdownImpl(
    text: String,
    optionSize: Int,
    isSelected: Boolean,
    index: Int,
    dropdownColors: DropdownColors = DropdownDefaults.dropdownColors(),
    enabled: Boolean = true,
    dialogMode: Boolean = false,
    onSelectedIndexChange: (Int) -> Unit,
) {
    val item = remember(text, enabled) { DropdownItem(text = text, enabled = enabled) }
    SDropdownImpl(
        item = item,
        optionSize = optionSize,
        isSelected = isSelected,
        index = index,
        dropdownColors = dropdownColors,
        enabled = enabled,
        dialogMode = dialogMode,
        onSelectedIndexChange = onSelectedIndexChange,
    )
}

// Private modifier constants (mirroring the original miuix internal modifiers)

private val SCheckIconBaseModifier = Modifier
    .padding(start = DropdownDefaults.CheckIconStartPadding)
    .size(DropdownDefaults.CheckIconSize)

private val SChevronIconBaseModifier = Modifier
    .padding(start = DropdownDefaults.CheckIconStartPadding)
    .size(width = DropdownDefaults.ChevronSize.width, height = DropdownDefaults.ChevronSize.height)

private val SIconCellModifier = Modifier
    .sizeIn(minWidth = DropdownDefaults.IconMinSize, minHeight = DropdownDefaults.IconMinSize)
    .padding(end = DropdownDefaults.IconEndPadding)
