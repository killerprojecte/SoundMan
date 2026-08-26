package hk.uwu.soundman.ui.components.apppicker

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import hk.uwu.soundman.miuix.basic.SInputField
import hk.uwu.soundman.ui.basic.OverScrollState
import hk.uwu.soundman.ui.basic.overScrollVertical
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 应用选择器的共享内容区域：搜索栏 + 应用列表。
 *
 * BottomSheet 和 FullPage 两种展示形式共享此内容，确保交互一致。
 * 搜索栏使用 [SInputField]（支持液态玻璃），位于标题下方。
 * 应用列表使用 [CheckboxPreference] 行，支持下拉刷新。
 *
 * 搜索栏和应用列表的水平 padding 为 0，由外部容器（OverlayBottomSheet 的 insideMargin
 * 或 FullPage 的 contentPadding）统一控制水平对齐。
 *
 * @param state 应用选择器状态
 * @param strings 文案集合，由外部传入
 * @param contentPadding 内容内边距，适配不同容器的 inset 需求
 * @param backdrop 液态玻璃 backdrop，传入时搜索框获得液态玻璃效果
 * @param modifier 修饰符
 */
@Composable
fun AppPickerContent(
    state: AppPickerState,
    strings: AppPickerStrings,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    backdrop: com.kyant.backdrop.Backdrop? = null,
) {
    val loadState by state.loadState.collectAsStateLifecycleAware()
    var searchExpanded by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        // 搜索栏 — 紧贴标题下方，使用 SInputField（支持液态玻璃）
        // 水平 padding 为 0，与 OverlayBottomSheet 的 insideMargin 对齐
        SInputField(
            query = state.searchQuery,
            onQueryChange = state::updateSearchQuery,
            onSearch = { /* dismiss keyboard */ },
            expanded = searchExpanded,
            onExpandedChange = { searchExpanded = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    top = contentPadding.calculateTopPadding() + 8.dp,
                    bottom = 8.dp,
                ),
            backdrop = backdrop,
            backdropAlpha = if (backdrop != null) 1f else 0f,
        )

        // 应用列表 / 加载状态 / 空状态
        Box(modifier = Modifier.fillMaxSize()) {
            when (val current = loadState) {
                is AppPickerLoadState.Loading -> LoadingState(strings.loadingText)
                is AppPickerLoadState.Error -> ErrorState(current.message)
                is AppPickerLoadState.Loaded -> {
                    if (state.filteredApps.isEmpty()) {
                        EmptyState(strings.emptyText)
                    } else {
                        AppList(
                            state = state,
                            strings = strings,
                            contentPadding = PaddingValues(
                                start = contentPadding.calculateStartPadding(LayoutDirection.Ltr),
                                end = contentPadding.calculateEndPadding(LayoutDirection.Ltr),
                                bottom = contentPadding.calculateBottomPadding(),
                            ),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 应用列表：使用 [CheckboxPreference] 行，支持选中复选框 + 应用图标 + 名称/包名。
 * 支持下拉刷新：列表顶部过度滚动时触发 [AppPickerState.refresh]。
 */
@Composable
private fun AppList(
    state: AppPickerState,
    strings: AppPickerStrings,
    contentPadding: PaddingValues,
) {
    val apps = state.filteredApps
    val overScrollState = remember { OverScrollState() }
    val hapticFeedback = LocalHapticFeedback.current

    // 监听过度滚动偏移，超过阈值时触发刷新
    LaunchedEffect(overScrollState) {
        snapshotFlow { overScrollState.offset }
            .collect { offset ->
                if (offset > 300f && overScrollState.isOverScrollActive) {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                    state.refresh()
                }
            }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .overScrollVertical(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(
            items = apps,
            key = { it.packageName },
        ) { entry ->
            AppRow(
                entry = entry,
                isSelected = state.isSelected(entry.packageName),
                onToggle = { state.toggleSelection(entry.packageName) },
                systemAppLabel = strings.systemAppLabel,
            )
        }
    }
}

/**
 * 单个应用行：使用 [BasicComponent] + 应用图标 + 末尾 [Checkbox]。
 *
 * 左侧为应用图标（参照 REAREye 的 PackageSelectionItem 布局），
 * 标题显示应用名称，摘要显示包名和系统应用标签，末尾为复选框。
 */
@Composable
private fun AppRow(
    entry: AppPickerEntry,
    isSelected: Boolean,
    onToggle: () -> Unit,
    systemAppLabel: String,
) {
    // 应用图标 — 加载高分辨率以适配大尺寸显示
    val iconBitmap = remember(entry.packageName, entry.icon) {
        entry.icon.toBitmap(128, 128).asImageBitmap()
    }

    val summary = if (entry.isSystemApp) {
        "${entry.packageName}  ·  $systemAppLabel"
    } else {
        entry.packageName
    }

    BasicComponent(
        title = entry.label,
        summary = summary,
        startAction = {
            Image(
                bitmap = iconBitmap,
                contentDescription = entry.label,
                modifier = Modifier
                    .size(56.dp)
                    .padding(end = 12.dp),
            )
        },
        endActions = {
            Checkbox(
                state = ToggleableState(isSelected),
                onClick = { onToggle() },
            )
        },
        onClick = { onToggle() },
    )
}

/**
 * 加载中状态。
 */
@Composable
private fun LoadingState(text: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 14.sp,
        )
    }
}

/**
 * 加载失败状态。
 */
@Composable
private fun ErrorState(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = message,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 14.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

/**
 * 空状态（搜索无结果）。
 */
@Composable
private fun EmptyState(text: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 14.sp,
        )
    }
}

// === 辅助函数 ===

/**
 * collectAsState 的封装。
 */
@Composable
private fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectAsStateLifecycleAware(): androidx.compose.runtime.State<T> {
    return this.collectAsState(initial = value)
}
