package hk.uwu.soundman.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import hk.uwu.soundman.R
import hk.uwu.soundman.data.APP_BLACKLIST_PREFERENCES_NAME
import hk.uwu.soundman.data.AppSettingsDefaults
import hk.uwu.soundman.data.AppSettingsStore
import hk.uwu.soundman.data.SharedPreferencesAppBlacklistStore
import hk.uwu.soundman.miuix.basic.SColorPicker
import hk.uwu.soundman.miuix.basic.STextButton
import hk.uwu.soundman.ui.basic.SharedScrollBehavior
import hk.uwu.soundman.ui.basic.overScrollVertical
import hk.uwu.soundman.ui.components.SettingColorItem
import hk.uwu.soundman.ui.components.SettingSliderItem
import hk.uwu.soundman.ui.components.SettingSwitchItem
import hk.uwu.soundman.ui.components.apppicker.AppPickerBottomSheet
import hk.uwu.soundman.ui.components.apppicker.AppPickerStrings
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
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
    val context = LocalContext.current
    var settings by remember(settingsStore) { mutableStateOf(settingsStore.read()) }
    var showBlacklistPicker by remember { mutableStateOf(false) }
    var showBlendColorPicker by remember { mutableStateOf(false) }

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

    val density = LocalDensity.current
    val topBarHeightDp = with(density) { scrollBehavior.currentHeightPx.toDp() }

    val blacklistStore = remember(context) {
        SharedPreferencesAppBlacklistStore(
            context.getSharedPreferences(
                APP_BLACKLIST_PREFERENCES_NAME,
                android.content.Context.MODE_PRIVATE
            )
        )
    }
    var blacklistedPackages by remember(blacklistStore) { mutableStateOf(blacklistStore.readAll()) }

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
            item(key = "liquid_glass") {
                SettingSwitchItem(
                    title = stringResource(R.string.settings_liquid_glass),
                    summary = stringResource(R.string.settings_liquid_glass_summary),
                    checked = settings.liquidGlassEnabled,
                    onCheckedChange = { settings = settingsStore.setLiquidGlassEnabled(it) },
                )
            }
            item(key = "liquid_glass_blur_radius") {
                var draftBlurRadius by remember(settings) {
                    mutableStateOf(settings.liquidGlassBlurRadius.toFloat())
                }
                SettingSliderItem(
                    title = stringResource(R.string.settings_liquid_glass_blur_radius),
                    summary = stringResource(R.string.settings_liquid_glass_blur_radius_summary),
                    value = draftBlurRadius,
                    valueRange = AppSettingsDefaults.LIQUID_GLASS_BLUR_RADIUS_MIN.toFloat()..
                            AppSettingsDefaults.LIQUID_GLASS_BLUR_RADIUS_MAX.toFloat(),
                    steps = AppSettingsDefaults.LIQUID_GLASS_BLUR_RADIUS_MAX -
                            AppSettingsDefaults.LIQUID_GLASS_BLUR_RADIUS_MIN - 1,
                    enabled = settings.liquidGlassEnabled && settings.liquidGlassRefractionEnabled,
                    valueLabel = draftBlurRadius.roundToInt().toString(),
                    onValueChange = { draftBlurRadius = it },
                    onValueChangeFinished = {
                        settings = settingsStore.setLiquidGlassBlurRadius(
                            draftBlurRadius.roundToInt()
                        )
                    },
                )
            }
            item(key = "liquid_glass_blend_color") {
                SettingColorItem(
                    title = stringResource(R.string.settings_liquid_glass_blend_color),
                    summary = stringResource(R.string.settings_liquid_glass_blend_color_summary),
                    color = Color(settings.liquidGlassBlendColor),
                    enabled = settings.liquidGlassEnabled && settings.liquidGlassRefractionEnabled,
                    onClick = { showBlendColorPicker = true },
                )
            }
            item(key = "liquid_glass_refraction") {
                SettingSwitchItem(
                    title = stringResource(R.string.settings_liquid_glass_refraction),
                    summary = stringResource(R.string.settings_liquid_glass_refraction_summary),
                    checked = settings.liquidGlassRefractionEnabled,
                    enabled = settings.liquidGlassEnabled,
                    onCheckedChange = {
                        settings = settingsStore.setLiquidGlassRefractionEnabled(it)
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
            item(key = "app_blacklist") {
                Card {
                    ArrowPreference(
                        title = stringResource(R.string.app_blacklist_title),
                        summary = stringResource(
                            R.string.app_blacklist_count,
                            blacklistedPackages.size
                        ),
                        onClick = { showBlacklistPicker = true },
                        holdDownState = showBlacklistPicker,
                    )
                }
            }
        }
    }

    if (showBlacklistPicker) {
        val pickerStrings = AppPickerStrings(
            title = stringResource(R.string.app_blacklist_title),
            closeActionDescription = stringResource(R.string.app_blacklist_close),
            saveActionDescription = stringResource(R.string.app_blacklist_save),
            systemAppLabel = stringResource(R.string.app_blacklist_system_app),
            loadingText = stringResource(R.string.app_blacklist_loading),
            emptyText = stringResource(R.string.app_blacklist_empty),
        )
        AppPickerBottomSheet(
            show = showBlacklistPicker,
            strings = pickerStrings,
            initialSelection = blacklistedPackages,
            hideSystemApps = settings.hideSystemAppsEnabled,
            onDismiss = { showBlacklistPicker = false },
            onSave = { newSelection ->
                blacklistStore.replaceAll(newSelection)
                blacklistedPackages = newSelection
                showBlacklistPicker = false
            },
        )
    }

    if (showBlendColorPicker) {
        var draftBlendColor by remember { mutableStateOf(Color(settings.liquidGlassBlendColor)) }
        var blendColorHex by remember(draftBlendColor) {
            mutableStateOf("%08X".format(draftBlendColor.toArgb()))
        }
        OverlayBottomSheet(
            show = true,
            title = stringResource(R.string.settings_liquid_glass_blend_color),
            onDismissRequest = { showBlendColorPicker = false },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_liquid_glass_blend_color_summary),
                    fontSize = MiuixTheme.textStyles.body1.fontSize,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                SColorPicker(
                    color = draftBlendColor,
                    onColorChanged = { draftBlendColor = it },
                    modifier = Modifier.fillMaxWidth(),
                )
                TextField(
                    value = blendColorHex,
                    onValueChange = { newHex ->
                        if (newHex.length <= 8 &&
                            newHex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
                        ) {
                            val upperHex = newHex.uppercase()
                            val newColor = if (upperHex.length == 8) {
                                Color(upperHex.toUInt(16).toInt())
                            } else {
                                null
                            }
                            blendColorHex = upperHex
                            if (newColor != null) draftBlendColor = newColor
                        }
                    },
                    leadingIcon = {
                        Text(
                            text = stringResource(R.string.settings_liquid_glass_blend_color_hex) + ": #",
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(start = 16.dp),
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    STextButton(
                        text = stringResource(R.string.settings_liquid_glass_blend_color_reset),
                        onClick = {
                            draftBlendColor = Color(AppSettingsDefaults.LIQUID_GLASS_BLEND_COLOR)
                        },
                    )
                    Button(
                        onClick = {
                            settings = settingsStore.setLiquidGlassBlendColor(
                                draftBlendColor.toArgb()
                            )
                            showBlendColorPicker = false
                        },
                    ) {
                        Text(
                            text = stringResource(R.string.settings_liquid_glass_blend_color_done)
                        )
                    }
                }
            }
        }
    }
}
