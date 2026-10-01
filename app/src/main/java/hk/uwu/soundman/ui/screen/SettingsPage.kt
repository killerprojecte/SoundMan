package hk.uwu.soundman.ui.screen

import android.annotation.SuppressLint
import android.os.Build
import android.view.RoundedCorner
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import hk.uwu.soundman.R
import hk.uwu.soundman.data.APP_BLACKLIST_PREFERENCES_NAME
import hk.uwu.soundman.data.AppSettings
import hk.uwu.soundman.data.AppSettingsDefaults
import hk.uwu.soundman.data.AppSettingsStore
import hk.uwu.soundman.data.SharedPreferencesAppBlacklistStore
import hk.uwu.soundman.miuix.basic.SColorPicker
import hk.uwu.soundman.miuix.basic.STextButton
import hk.uwu.soundman.model.EntryMaterial
import hk.uwu.soundman.model.EntryPosition
import hk.uwu.soundman.model.PanelMaterial
import hk.uwu.soundman.ui.basic.SharedScrollBehavior
import hk.uwu.soundman.ui.basic.overScrollVertical
import hk.uwu.soundman.ui.components.SettingColorItem
import hk.uwu.soundman.ui.components.SettingSliderItem
import hk.uwu.soundman.ui.components.SettingSwitchItem
import hk.uwu.soundman.ui.components.apppicker.AppPickerBottomSheet
import hk.uwu.soundman.ui.components.apppicker.AppPickerStrings
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/** 二级页路由：`null` 是设置首页，另外两个是音量按钮 / 面板子页。 */
object SettingsSubRoute {
    const val VOLUME_ENTRY = "volume_entry"
    const val VOLUME_PANEL = "volume_panel"
}

/** 二级页转场时长，与 miuix 导航程序化 push/pop 的 `PROGRAMMATIC_DURATION_MILLIS` 一致。 */
private const val SubPageTransitionMillis = 500

/**
 * 被压住的那一层退让后的不透明度。
 *
 * miuix 的 `NavTransitions.MiuixDefault` 给被覆盖层做 `1 - 0.1 * coverProgress`，
 * 这里取它的终值，让子页进出时的层次感和官方导航一致。
 */
private const val CoveredPageAlpha = 0.9f

/** 被压住的那一层向左视差退让的距离，占屏宽的比例（同上：`width * 0.25f`）。 */
private const val CoveredPageParallax = 0.25f

/**
 * 手势松手后收敛用的弹簧，参数照抄 miuix `NavDriverSpec`（临界阻尼、stiffness 146，
 * 单位同样是"一层"）。只有整步的程序化 push/pop 才走 [SubPageEasing] 那条固定时长曲线；
 * 从手指手里接过来的半程必须交给 live spring，否则会看到一次速度断档。
 */
private val SubPageCommitSpring = spring<Float>(
    dampingRatio = 1f,
    stiffness = 146f,
    visibilityThreshold = 0.0001f,
)

/**
 * 复制 miuix 导航的 `NavSettleEasing(response = 0.8, damping = 0.95)`：
 * 把一段欠阻尼弹簧的阶跃响应烘成 [Easing]，中段走得快、尾巴长而缓，整体不过冲。
 *
 * 用固定时长的 tween 而不是 live spring，是为了让每次开合都恰好在
 * [SubPageTransitionMillis] 内结束，不会因滑动距离不同而时长漂移。
 */
private class MiuixSettleEasing(response: Float, damping: Float) : Easing {
    private val r: Float
    private val w: Float
    private val c2: Float

    init {
        val omega = 2.0 * PI / response
        val k = omega * omega
        val c = damping * 4.0 * PI / response
        w = (sqrt(4.0 * k - c * c) / 2.0).toFloat()
        r = (-c / 2.0).toFloat()
        c2 = r / w
    }

    override fun transform(fraction: Float): Float {
        val t = fraction.toDouble()
        val decay = exp(r * t)
        return (decay * (-cos(w * t) + c2 * sin(w * t)) + 1.0).toFloat()
    }
}

private val SubPageEasing: Easing = MiuixSettleEasing(response = 0.8f, damping = 0.95f)

/**
 * 设置页：使用移植的 NexioSchedule 组件风格。
 *
 * 每个设置项使用 Card + BasicComponent + Switch。
 * 多应用音量按钮与多应用音量面板各自收进二级页（[subRoute]）：这两组的条目最多、
 * 又都只影响 SystemUI 侧的表现，摊在主页里会把真正的全局开关挤下去。
 */
@Composable
fun SettingsPage(
    paddingValues: PaddingValues,
    scrollBehavior: SharedScrollBehavior,
    settingsStore: AppSettingsStore,
    subRoute: String? = null,
    onSubRouteChange: (String?) -> Unit = {},
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    // 拿一个 MutableState 出来而不是只留委托变量：两层页面要共用同一份快照，
    // 条目表需要能写回它（Kotlin 参数不可重新赋值，只能传 state 对象）。
    val settingsState: MutableState<AppSettings> =
        remember(settingsStore) { mutableStateOf(settingsStore.read()) }
    var settings by settingsState
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

    // 二级页的转场进度：0 = 只看见设置首页，1 = 子页完全压住首页。
    //
    // 这里刻意不用 AnimatedContent：它由 enter/exit 两条各自独立的动画拼出来，
    // 中间没有可被手指接管的状态，做不出预测性返回。改成 miuix 导航那样的写法——
    // 一个共享的进度值，程序化开合走固定时长曲线，返回手势则 1:1 把进度 snap 到手指上。
    val progress = remember { Animatable(0f, visibilityThreshold = 0.0001f) }
    // pop 时子页要等动画跑完才能撤，所以渲染用的路由和 [subRoute] 分开记。
    var renderedRoute by remember { mutableStateOf<String?>(null) }
    val animationScope = rememberCoroutineScope()

    val navigationEventState = rememberNavigationEventState(
        currentInfo = NavigationEventInfo.None
    )
    NavigationBackHandler(
        state = navigationEventState,
        isBackEnabled = subRoute != null,
        onBackCancelled = {
            animationScope.launch { progress.animateTo(1f, SubPageCommitSpring) }
        },
        onBackCompleted = { onSubRouteChange(null) },
    )
    // 返回手势期间让页面跟着手指走：系统给的 progress 是"已经返回了多少"，
    // 我们的进度是"子页还在多少"，所以取 1 - progress。
    LaunchedEffect(Unit) {
        snapshotFlow { navigationEventState.transitionState }.collect { state ->
            if (
                state is NavigationEventTransitionState.InProgress &&
                state.direction == NavigationEventTransitionState.TRANSITIONING_BACK
            ) {
                progress.snapTo(1f - state.latestEvent.progress)
            }
        }
    }

    LaunchedEffect(subRoute) {
        if (subRoute != null) {
            renderedRoute = subRoute
            progress.snapTo(0f)
            progress.animateTo(
                1f,
                tween(SubPageTransitionMillis, easing = SubPageEasing),
            )
        } else if (renderedRoute != null) {
            // 从手指手里接过来的半程用弹簧收尾；只有整步返回才走那条 500ms 曲线。
            val settle = if (progress.value >= 0.999f) {
                tween<Float>(SubPageTransitionMillis, easing = SubPageEasing)
            } else {
                SubPageCommitSpring
            }
            progress.animateTo(0f, settle)
            renderedRoute = null
        }
    }

    val configuration = LocalConfiguration.current
    val pageWidthPx = with(LocalDensity.current) { configuration.screenWidthDp.dp.toPx() }
    val cornerRadius = rememberSystemCornerRadius()
    val pageBackground = MiuixTheme.colorScheme.surface

    Box(modifier = Modifier.fillMaxSize().clipToBounds()) {
        // 被压住的设置首页：向左视差退让四分之一屏宽并轻微变淡。
        SettingsPageLayer(
            progress = progress.value,
            covered = true,
            pageWidthPx = pageWidthPx,
            cornerRadius = cornerRadius,
            backgroundColor = pageBackground,
        ) {
            SettingsList(
                paddingValues = paddingValues,
                topBarHeightDp = topBarHeightDp,
                scrollBehavior = scrollBehavior,
            ) {
                settingsItems(
                    route = null,
                    settings = settingsState,
                    settingsStore = settingsStore,
                    blacklistedPackages = blacklistedPackages,
                    onSubRouteChange = onSubRouteChange,
                    onPickBlacklist = { showBlacklistPicker = true },
                    onPickBlendColor = { showBlendColorPicker = true },
                    blacklistSheetShown = showBlacklistPicker,
                )
            }
        }
        if (renderedRoute != null) {
            SettingsPageLayer(
                progress = progress.value,
                covered = false,
                pageWidthPx = pageWidthPx,
                cornerRadius = cornerRadius,
                backgroundColor = pageBackground,
            ) {
                SettingsList(
                    paddingValues = paddingValues,
                    topBarHeightDp = topBarHeightDp,
                    scrollBehavior = scrollBehavior,
                ) {
                    settingsItems(
                        route = renderedRoute,
                        settings = settingsState,
                        settingsStore = settingsStore,
                        blacklistedPackages = blacklistedPackages,
                        onSubRouteChange = onSubRouteChange,
                        onPickBlacklist = { showBlacklistPicker = true },
                        onPickBlendColor = { showBlendColorPicker = true },
                        blacklistSheetShown = showBlacklistPicker,
                    )
                }
            }
        }
        // 手势进行中吞掉点击，避免在页面跟着手指走的时候误触到下面的条目。
        if (navigationEventState.transitionState is NavigationEventTransitionState.InProgress) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {},
            )
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

/**
 * 设置列表容器。
 *
 * 三个层级（首页 / 按钮 / 面板）共用同一套内距与 overscroll 参数，
 * 换页时只换内容，避免每页重复一遍滚动配置。
 */
@Composable
private fun SettingsList(
    paddingValues: PaddingValues,
    topBarHeightDp: androidx.compose.ui.unit.Dp,
    scrollBehavior: SharedScrollBehavior,
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(
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
        content = content,
    )
}

/** 入口落位的显示名；顺序与 [EntryPosition.values] 对应。 */
@Composable
private fun entryPositionLabel(position: EntryPosition): String = when (position) {
    EntryPosition.ABOVE -> stringResource(R.string.settings_entry_position_above)
    EntryPosition.BELOW -> stringResource(R.string.settings_entry_position_below)
}

/** 入口材质的显示名；顺序与 [EntryMaterial.values] 对应。 */
@Composable
private fun entryMaterialLabel(material: EntryMaterial): String = when (material) {
    EntryMaterial.HYPERLIGHT -> stringResource(R.string.settings_entry_material_hyperlight)
    EntryMaterial.LIQUID -> stringResource(R.string.settings_entry_material_liquid)
    EntryMaterial.COMPONENT -> stringResource(R.string.settings_entry_material_component)
}

/** 面板材质的显示名；顺序与 [PanelMaterial.values] 对应。 */
@Composable
private fun panelMaterialLabel(material: PanelMaterial): String = when (material) {
    PanelMaterial.HYPERLIGHT -> stringResource(R.string.settings_panel_material_hyperlight)
    PanelMaterial.LIQUID -> stringResource(R.string.settings_panel_material_liquid)
    PanelMaterial.OFFICIAL -> stringResource(R.string.settings_panel_material_official)
}

/**
 * 三个层级共用的条目表。
 *
 * 抽成 [LazyListScope] 扩展而不是三个独立 composable，是因为设置首页和二级页在转场期间
 * 是**同时**渲染的两层（被压住的那一层要跟着视差退让），内容得由外层容器来分派。
 *
 * @param route 要渲染的层级；null 是设置首页
 */
private fun LazyListScope.settingsItems(
    route: String?,
    settings: MutableState<AppSettings>,
    settingsStore: AppSettingsStore,
    blacklistedPackages: Set<String>,
    onSubRouteChange: (String?) -> Unit,
    onPickBlacklist: () -> Unit,
    onPickBlendColor: () -> Unit,
    blacklistSheetShown: Boolean,
) {
    when (route) {
        SettingsSubRoute.VOLUME_ENTRY -> {
            item(key = "entry_playback_only") {
                SettingSwitchItem(
                    title = stringResource(R.string.settings_entry_playback_only),
                    summary = stringResource(R.string.settings_entry_playback_only_summary),
                    checked = settings.value.entryPlaybackOnlyEnabled,
                    onCheckedChange = {
                        settings.value = settingsStore.setEntryPlaybackOnlyEnabled(it)
                    },
                )
            }
            item(key = "entry_position") {
                val options = EntryPosition.values().toList()
                val labels = options.map { entryPositionLabel(it) }
                Card {
                OverlayDropdownPreference(
                    title = stringResource(R.string.settings_entry_position),
                    summary = stringResource(R.string.settings_entry_position_summary),
                    items = labels,
                    selectedIndex = options
                        .indexOf(settings.value.entryPosition)
                        .coerceAtLeast(0),
                    onSelectedIndexChange = { index: Int ->
                        settings.value = settingsStore.setEntryPosition(options[index])
                    },
                )
                }
            }
            item(key = "entry_material") {
                val options = EntryMaterial.values().toList()
                val labels = options.map { entryMaterialLabel(it) }
                Card {
                OverlayDropdownPreference(
                    title = stringResource(R.string.settings_entry_material),
                    summary = stringResource(R.string.settings_entry_material_summary),
                    items = labels,
                    selectedIndex = options
                        .indexOf(settings.value.entryMaterial)
                        .coerceAtLeast(0),
                    onSelectedIndexChange = { index: Int ->
                        settings.value = settingsStore.setEntryMaterial(options[index])
                    },
                )
                }
            }
        }

        SettingsSubRoute.VOLUME_PANEL -> {
            item(key = "builtin_panel") {
                SettingSwitchItem(
                    title = stringResource(R.string.settings_systemui_builtin_volume_panel),
                    summary = stringResource(
                        R.string.settings_systemui_builtin_volume_panel_summary
                    ),
                    checked = settings.value.systemUiBuiltinVolumePanelEnabled,
                    onCheckedChange = {
                        settings.value = settingsStore.setSystemUiBuiltinVolumePanelEnabled(it)
                    },
                )
            }
            item(key = "volume_percent") {
                SettingSwitchItem(
                    title = stringResource(R.string.settings_volume_percent),
                    summary = stringResource(R.string.settings_volume_percent_summary),
                    checked = settings.value.volumePercentEnabled,
                    onCheckedChange = { settings.value = settingsStore.setVolumePercentEnabled(it) },
                )
            }
            item(key = "panel_material") {
                val options = PanelMaterial.values().toList()
                val labels = options.map { panelMaterialLabel(it) }
                Card {
                    OverlayDropdownPreference(
                        title = stringResource(R.string.settings_panel_material),
                        summary = stringResource(
                            R.string.settings_panel_material_summary
                        ),
                        items = labels,
                        selectedIndex = options
                            .indexOf(settings.value.panelMaterial)
                            .coerceAtLeast(0),
                        onSelectedIndexChange = { index: Int ->
                            settings.value = settingsStore.setPanelMaterial(options[index])
                        },
                    )
                }
            }
            // 折射 / 模糊 / 混色只作用于自研玻璃，选了别的材质就没有可调的参数。
            if (settings.value.panelMaterial == PanelMaterial.LIQUID) {
                item(key = "liquid_glass_refraction") {
                    SettingSwitchItem(
                        title = stringResource(R.string.settings_liquid_glass_refraction),
                        summary = stringResource(
                            R.string.settings_liquid_glass_refraction_summary
                        ),
                        checked = settings.value.liquidGlassRefractionEnabled,
                        onCheckedChange = {
                            settings.value = settingsStore.setLiquidGlassRefractionEnabled(it)
                        },
                    )
                }
                item(key = "liquid_glass_blur_radius") {
                    var draftBlurRadius by remember(settings) {
                        mutableStateOf(settings.value.liquidGlassBlurRadius.toFloat())
                    }
                    SettingSliderItem(
                        title = stringResource(R.string.settings_liquid_glass_blur_radius),
                        summary = stringResource(
                            R.string.settings_liquid_glass_blur_radius_summary
                        ),
                        value = draftBlurRadius,
                        valueRange = AppSettingsDefaults.LIQUID_GLASS_BLUR_RADIUS_MIN.toFloat()..
                                AppSettingsDefaults.LIQUID_GLASS_BLUR_RADIUS_MAX.toFloat(),
                        steps = AppSettingsDefaults.LIQUID_GLASS_BLUR_RADIUS_MAX -
                                AppSettingsDefaults.LIQUID_GLASS_BLUR_RADIUS_MIN - 1,
                        enabled = settings.value.liquidGlassRefractionEnabled,
                        valueLabel = draftBlurRadius.roundToInt().toString(),
                        onValueChange = { draftBlurRadius = it },
                        onValueChangeFinished = {
                            settings.value = settingsStore.setLiquidGlassBlurRadius(
                                draftBlurRadius.roundToInt()
                            )
                        },
                    )
                }
                item(key = "liquid_glass_blend_color") {
                    SettingColorItem(
                        title = stringResource(R.string.settings_liquid_glass_blend_color),
                        summary = stringResource(
                            R.string.settings_liquid_glass_blend_color_summary
                        ),
                        color = Color(settings.value.liquidGlassBlendColor),
                        enabled = settings.value.liquidGlassRefractionEnabled,
                        onClick = { onPickBlendColor() },
                    )
                }
            }
        }

        else -> {
            item(key = "smooth_corners") {
                SettingSwitchItem(
                    title = stringResource(R.string.settings_smooth_corners),
                    summary = stringResource(R.string.settings_smooth_corners_summary),
                    checked = settings.value.smoothCornersEnabled,
                    onCheckedChange = { settings.value = settingsStore.setSmoothCornersEnabled(it) },
                )
            }
            item(key = "volume_entry_page") {
                Card {
                    ArrowPreference(
                        title = stringResource(R.string.settings_volume_entry_title),
                        summary = stringResource(R.string.settings_volume_entry_summary),
                        onClick = { onSubRouteChange(SettingsSubRoute.VOLUME_ENTRY) },
                    )
                }
            }
            item(key = "volume_panel_page") {
                Card {
                    ArrowPreference(
                        title = stringResource(R.string.settings_volume_panel_title),
                        summary = stringResource(R.string.settings_volume_panel_summary),
                        onClick = { onSubRouteChange(SettingsSubRoute.VOLUME_PANEL) },
                    )
                }
            }
            item(key = "hide_system_apps") {
                SettingSwitchItem(
                    title = stringResource(R.string.settings_hide_system_apps),
                    summary = stringResource(R.string.settings_hide_system_apps_summary),
                    checked = settings.value.hideSystemAppsEnabled,
                    onCheckedChange = { settings.value = settingsStore.setHideSystemAppsEnabled(it) },
                )
            }
            item(key = "alarm_first") {
                SettingSwitchItem(
                    title = stringResource(R.string.settings_alarm_first),
                    summary = stringResource(R.string.settings_alarm_first_summary),
                    checked = settings.value.alarmFirstEnabled,
                    onCheckedChange = { settings.value = settingsStore.setAlarmFirstEnabled(it) },
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
                        onClick = { onPickBlacklist() },
                        holdDownState = blacklistSheetShown,
                    )
                }
            }
        }
    }
}

/**
 * 转场里的一个"页面层"。
 *
 * 对齐 miuix 导航的 [NavTransitions.MiuixDefault]：
 * - 顶层（[covered] = false）整屏从右侧滑入，位移 `(1 - progress) * width`；
 * - 被压住的那层向左视差退让 `0.25 * progress * width`，同时不透明度降到 0.9。
 *
 * 关键是**每层都自带不透明背景**：没有它，滑动的只是一列透明卡片，
 * 看起来就是卡片在飘而不是整页在走。再叠一层只圆前缘的裁剪（跟系统屏幕圆角一致），
 * 页面推进来时才是"整页"而不是一块直角矩形。
 */
@Composable
private fun SettingsPageLayer(
    progress: Float,
    covered: Boolean,
    pageWidthPx: Float,
    cornerRadius: Dp,
    backgroundColor: Color,
    content: @Composable () -> Unit,
) {
    val shape = if (covered || cornerRadius <= 0.dp) {
        RectangleShape
    } else {
        RoundedCornerShape(topStart = cornerRadius, bottomStart = cornerRadius)
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationX = if (covered) {
                    -CoveredPageParallax * progress * pageWidthPx
                } else {
                    (1f - progress) * pageWidthPx
                }
                alpha = if (covered) 1f - (1f - CoveredPageAlpha) * progress else 1f
            }
            .clip(shape)
            .background(backgroundColor),
    ) {
        content()
    }
}

/**
 * 屏幕圆角半径，用于把滑入的页面裁成和系统圆角一致。
 *
 * 照抄 miuix 的 `rememberNavSystemCornerRadius`：优先用 API 31+ 的 [RoundedCorner] 窗口 inset，
 * 取不到再回退 framework 的 `rounded_corner_radius_bottom`，平角屏返回 0。
 */
@Composable
@SuppressLint("DiscouragedApi")
private fun rememberSystemCornerRadius(): Dp {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val view = LocalView.current
    val insets = view.rootWindowInsets
    val radiusPx = remember(context, view, insets) {
        val fromInsets = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            insets?.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.radius?.takeIf { it > 0 }
        } else {
            null
        }
        fromInsets ?: run {
            val id = context.resources
                .getIdentifier("rounded_corner_radius_bottom", "dimen", "android")
            if (id > 0) context.resources.getDimensionPixelSize(id) else 0
        }
    }
    return (radiusPx / density).dp
}
