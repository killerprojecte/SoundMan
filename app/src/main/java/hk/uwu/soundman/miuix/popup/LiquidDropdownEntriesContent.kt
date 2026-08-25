// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package hk.uwu.soundman.miuix.popup

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.DropdownColors
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.HorizontalDivider

/**
 * 渲染弹窗中的 [DropdownEntry] 组。
 */
@Composable
fun DropdownEntriesPopupContent(
    entries: List<DropdownEntry>,
    dropdownColors: DropdownColors,
    onItemClick: (entryIdx: Int, itemIdx: Int) -> Unit,
) {
    val lastEntryIdx = entries.lastIndex
    entries.forEachIndexed { entryIdx, entry ->
        val lastItemIdx = entry.items.lastIndex
        val isFirstEntry = entryIdx == 0
        val isLastEntry = entryIdx == lastEntryIdx
        entry.items.forEachIndexed { itemIdx, option ->
            key(entryIdx, itemIdx) {
                DropdownImpl(
                    item = option,
                    optionSize = entry.items.size,
                    isSelected = option.selected,
                    index = itemIdx,
                    dropdownColors = dropdownColors,
                    enabled = entry.enabled && option.enabled,
                    isFirst = isFirstEntry && itemIdx == 0,
                    isLast = isLastEntry && itemIdx == lastItemIdx,
                    onSelectedIndexChange = { idx -> onItemClick(entryIdx, idx) },
                )
            }
        }
        if (entryIdx != lastEntryIdx) {
            HorizontalDivider(
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                thickness = 1.5.dp,
            )
        }
    }
}

/**
 * 向 [LazyListScope] 添加 [DropdownEntry] 组，用于对话框。
 */
fun LazyListScope.dropdownEntriesDialogItems(
    entries: List<DropdownEntry>,
    dropdownColors: DropdownColors,
    onItemClick: (entryIdx: Int, itemIdx: Int) -> Unit,
) {
    val lastEntryIdx = entries.lastIndex
    entries.forEachIndexed { entryIdx, entry ->
        items(entry.items.size, key = { itemIdx -> "$entryIdx-$itemIdx" }) { itemIdx ->
            val item = entry.items[itemIdx]
            DropdownImpl(
                item = item,
                optionSize = entry.items.size,
                isSelected = item.selected,
                index = itemIdx,
                dropdownColors = dropdownColors,
                enabled = entry.enabled && item.enabled,
                dialogMode = true,
                onSelectedIndexChange = { idx -> onItemClick(entryIdx, idx) },
            )
        }
        if (entryIdx != lastEntryIdx) {
            item(key = "divider-$entryIdx") {
                HorizontalDivider(
                    modifier = Modifier
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                    thickness = 1.5.dp,
                )
            }
        }
    }
}
