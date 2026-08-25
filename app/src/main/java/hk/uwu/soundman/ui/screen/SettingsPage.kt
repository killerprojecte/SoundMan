package hk.uwu.soundman.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import hk.uwu.soundman.R
import hk.uwu.soundman.data.AppSettingsStore
import hk.uwu.soundman.ui.basic.SharedScrollBehavior
import hk.uwu.soundman.ui.basic.overScrollVertical
import hk.uwu.soundman.ui.components.SettingSwitchItem
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 设置页：使用移植的 NexioSchedule 组件风格。
 *
 * 每个设置项使用 Card + BasicComponent + Switch。
 */
@Composable
fun SettingsPage(
    paddingValues: PaddingValues,
    scrollBehavior: SharedScrollBehavior,
    settingsStore: AppSettingsStore,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var settings by remember(settingsStore) { mutableStateOf(settingsStore.read()) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                settings = settingsStore.read()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val lazyListState = rememberLazyListState()

    // 对齐 NexioSchedule-ref：paddingValues 来自 Scaffold(topBar={})，只包含状态栏 inset（稳定值）。
    // topBarHeightDp 来自 scrollBehavior.currentHeightPx（由 onSizeChanged 直接更新，与滚动同步）。
    // 两者叠加 = 状态栏高度 + TopBar 高度，避免 SubcomposeLayout 帧延迟。
    val density = LocalDensity.current
    val topBarHeightDp = with(density) { scrollBehavior.currentHeightPx.toDp() }

    // TopBar 在 MainScreen 统一渲染，页面只需提供 scrollBehavior 和内容
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .fillMaxSize()
                .overScrollVertical()
                .scrollEndHaptic()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .padding(horizontal = 12.dp),
            contentPadding = PaddingValues(
                top = paddingValues.calculateTopPadding() + topBarHeightDp + 12.dp,
                bottom = paddingValues.calculateBottomPadding() + 12.dp,
                start = WindowInsets.displayCutout.asPaddingValues()
                    .calculateStartPadding(LayoutDirection.Ltr),
                end = WindowInsets.displayCutout.asPaddingValues()
                    .calculateEndPadding(LayoutDirection.Ltr),
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            overscrollEffect = null,
        ) {
            item(key = "smooth_corners") {
                SettingSwitchItem(
                    title = stringResource(R.string.settings_smooth_corners),
                    summary = stringResource(R.string.settings_smooth_corners_summary),
                    checked = settings.smoothCornersEnabled,
                    onCheckedChange = { settings = settingsStore.setSmoothCornersEnabled(it) },
                )
            }
            item(key = "volume_percent") {
                SettingSwitchItem(
                    title = stringResource(R.string.settings_volume_percent),
                    summary = stringResource(R.string.settings_volume_percent_summary),
                    checked = settings.volumePercentEnabled,
                    onCheckedChange = { settings = settingsStore.setVolumePercentEnabled(it) },
                )
            }
            item(key = "builtin_panel") {
                SettingSwitchItem(
                    title = stringResource(R.string.settings_systemui_builtin_volume_panel),
                    summary = stringResource(R.string.settings_systemui_builtin_volume_panel_summary),
                    checked = settings.systemUiBuiltinVolumePanelEnabled,
                    onCheckedChange = {
                        settings = settingsStore.setSystemUiBuiltinVolumePanelEnabled(it)
                    },
                )
            }
            item(key = "hide_system_apps") {
                SettingSwitchItem(
                    title = stringResource(R.string.settings_hide_system_apps),
                    summary = stringResource(R.string.settings_hide_system_apps_summary),
                    checked = settings.hideSystemAppsEnabled,
                    onCheckedChange = { settings = settingsStore.setHideSystemAppsEnabled(it) },
                )
            }
            item(key = "alarm_first") {
                SettingSwitchItem(
                    title = stringResource(R.string.settings_alarm_first),
                    summary = stringResource(R.string.settings_alarm_first_summary),
                    checked = settings.alarmFirstEnabled,
                    onCheckedChange = { settings = settingsStore.setAlarmFirstEnabled(it) },
                )
            }
        }
    }
}
