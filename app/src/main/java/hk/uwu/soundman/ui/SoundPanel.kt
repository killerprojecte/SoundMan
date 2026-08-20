package hk.uwu.soundman.ui

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import hk.uwu.soundman.R
import hk.uwu.soundman.data.APP_SETTINGS_PREFERENCES_NAME
import hk.uwu.soundman.data.ActiveMediaAppsState
import hk.uwu.soundman.data.AudioDeviceScan
import hk.uwu.soundman.data.AudioDevicesSource
import hk.uwu.soundman.data.HostAudioDevicesSource
import hk.uwu.soundman.data.HostPlaybackSource
import hk.uwu.soundman.data.InstalledAppsAccess
import hk.uwu.soundman.data.PermissionCatalog
import hk.uwu.soundman.data.RULE_PREFERENCES_NAME
import hk.uwu.soundman.data.RuleStore
import hk.uwu.soundman.data.SharedPreferencesAppSettingsStore
import hk.uwu.soundman.data.SharedPreferencesRuleStore
import hk.uwu.soundman.ipc.PreferredDeviceSync
import hk.uwu.soundman.log.AppLog
import hk.uwu.soundman.model.AdjustableApp
import hk.uwu.soundman.model.AppAudioRule
import hk.uwu.soundman.model.OutputDeviceType
import hk.uwu.soundman.model.OutputTarget
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.time.Duration.Companion.milliseconds

private const val SOURCE_STATE_LOG_INTERVAL_MILLIS = 2_000L
private val Accent = Color(0xFF3482FF)
private val SecondaryText = Color(0xFF66666D)
private val PrimaryText = Color(0xFF202024)
private val OnBlurText = Color.White.copy(alpha = 0.88f)
private val OnBlurMuted = Color.White.copy(alpha = 0.55f)
private val HyperOsEasing = CubicBezierEasing(0.2f, 0.0f, 0.0f, 1.0f)
private const val HYPEROS_MOVE_MS = 380
private const val PANEL_ENTER_SCALE = 0.94f
private val devicePageRows: DevicePageRows = DevicePageRows()

@Composable
fun SoundPanel(
    context: Context,
    onDismiss: () -> Unit,
    onWindowReveal: (Float) -> Unit = {},
    onRequestInstalledAppsPermission: (() -> Unit)? = null,
    installedAppsPermissionRevision: Int = 0,
    fromVolumeSidebar: Boolean = false,
) {
    require(installedAppsPermissionRevision >= 0) { "installedAppsPermissionRevision must not be negative" }
    val applicationContext = context.applicationContext
    val installedAppsAccess = remember(applicationContext) {
        InstalledAppsAccess(PermissionCatalog(applicationContext))
    }
    val hasInstalledAppsAccess = installedAppsAccess.hasAccess(applicationContext)
    val ruleStore = remember(applicationContext) {
        SharedPreferencesRuleStore(applicationContext.getSharedPreferences(RULE_PREFERENCES_NAME, Context.MODE_PRIVATE))
    }
    val appSettingsStore = remember(applicationContext) {
        SharedPreferencesAppSettingsStore(
            applicationContext.getSharedPreferences(
                APP_SETTINGS_PREFERENCES_NAME,
                Context.MODE_PRIVATE
            ),
        )
    }
    val appSettings = remember(appSettingsStore) { appSettingsStore.read() }
    val hostSource = remember(applicationContext, hasInstalledAppsAccess) {
        HostPlaybackSource(applicationContext, ruleStore, installedAppsAccess)
    }
    var mediaState by remember { mutableStateOf<ActiveMediaAppsState>(ActiveMediaAppsState.Available(emptyList())) }
    DisposableEffect(hostSource) {
        var lastStateLogMillis = -SOURCE_STATE_LOG_INTERVAL_MILLIS
        var lastStateSignature: Pair<String, Int>? = null
        val removeObserver = hostSource.observe { state ->
            mediaState = state
            val stateType = when (state) {
                is ActiveMediaAppsState.Available -> "available"
                is ActiveMediaAppsState.Error -> "error"
            }
            val appCount = (state as? ActiveMediaAppsState.Available)?.apps?.size ?: 0
            val signature = stateType to appCount
            val now = android.os.SystemClock.elapsedRealtime()
            if (signature != lastStateSignature && now - lastStateLogMillis >= SOURCE_STATE_LOG_INTERVAL_MILLIS) {
                lastStateSignature = signature
                lastStateLogMillis = now
                AppLog.debug("[source] consumed state=$stateType apps=$appCount")
            }
        }
        onDispose { removeObserver(); hostSource.close() }
    }
    var selectedPackage by rememberSaveable { mutableStateOf<String?>(null) }
    val audioDevicesSource = remember(hostSource) { HostAudioDevicesSource(hostSource) }
    var deviceScan by remember { mutableStateOf(AudioDeviceScan(emptyList(), null)) }
    val rules = remember { mutableStateMapOf<String, AppAudioRule>().apply { putAll(ruleStore.readAll()) } }
    ObserveAudioDevicesAndFallback(
        source = audioDevicesSource,
        store = ruleStore,
        rules = rules,
        onRulesChanged = hostSource::replaceRules,
        onFollowSystem = { uid -> hostSource.setRoute(uid, OutputTarget.FollowSystem) },
        onScan = { deviceScan = it },
    )
    val currentApps = rememberDebouncedPlaybackApps(
        apps = (mediaState as? ActiveMediaAppsState.Available)?.apps.orEmpty(),
        active = mediaState is ActiveMediaAppsState.Available,
    )
    LaunchedEffect(currentApps, selectedPackage) {
        if (selectedPackage != null && currentApps.none { it.packageName == selectedPackage }) selectedPackage = null
    }
    val currentOnWindowReveal by rememberUpdatedState(onWindowReveal)
    val panelReveal = remember { Animatable(0f) }
    var panelEnterConsumed by remember { mutableStateOf(false) }
    var panelDismissing by remember { mutableStateOf(false) }
    LaunchedEffect(panelReveal) {
        snapshotFlow { panelReveal.value }.collect { currentOnWindowReveal(it) }
    }
    LaunchedEffect(Unit) {
        if (panelEnterConsumed) return@LaunchedEffect
        panelEnterConsumed = true
        panelReveal.animateTo(1f, tween(HYPEROS_MOVE_MS, easing = HyperOsEasing))
    }
    fun requestPanelDismiss() {
        if (panelDismissing) return
        panelDismissing = true
    }
    LaunchedEffect(panelDismissing) {
        if (!panelDismissing) return@LaunchedEffect
        panelReveal.animateTo(0f, tween(HYPEROS_MOVE_MS, easing = HyperOsEasing))
        onDismiss()
    }
    BackHandler(enabled = selectedPackage != null) { selectedPackage = null }
    BackHandler(enabled = selectedPackage == null) { requestPanelDismiss() }

    val pageKey = PanelPageKey.of(selectedPackage, currentApps.map(AdjustableApp::packageName))
    val showPermissionHint = onRequestInstalledAppsPermission != null &&
        installedAppsAccess.isRuntimePermissionPresent() &&
        !hasInstalledAppsAccess

    MiuixTheme {
        BlurMaterialHost(
            smoothCornersEnabled = appSettings.smoothCornersEnabled,
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = {
                            if (selectedPackage != null) {
                                selectedPackage = null
                            } else {
                                requestPanelDismiss()
                            }
                        },
                    ),
            )
            // 侧栏打开的悬浮窗靠点空白/返回关掉，不显示右上角关闭。
            if (!fromVolumeSidebar) {
                PanelChrome(
                    onDismiss = { requestPanelDismiss() },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .graphicsLayer { alpha = panelReveal.value },
                )
            }
            val panelMotion = Modifier.graphicsLayer {
                val t = panelReveal.value
                alpha = t
                val scale = PANEL_ENTER_SCALE + (1f - PANEL_ENTER_SCALE) * t
                scaleX = scale
                scaleY = scale
                if (fromVolumeSidebar) {
                    translationX = (1f - t) * size.width
                    transformOrigin = TransformOrigin(1f, 0.5f)
                } else {
                    transformOrigin = TransformOrigin(0.5f, 0.5f)
                }
            }
            Column(
                Modifier
                    .align(Alignment.Center)
                    .widthIn(min = 320.dp, max = 430.dp)
                    .padding(horizontal = 24.dp)
                    .then(panelMotion)
                    .blurMaterial(
                        purpose = BlurMaterialPurpose.Panel,
                        cornerRadius = OverlayGlassRadius,
                        tint = OverlayGlassFill,
                        border = OverlayGlassBorder,
                    )
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = {},
                    )
                    .padding(horizontal = 22.dp, vertical = 24.dp),
            ) {
                AnimatedContent(
                    targetState = pageKey,
                    transitionSpec = {
                        val openingDevicePage = targetState != null && initialState == null
                        val offsetSpec = tween<IntOffset>(HYPEROS_MOVE_MS, easing = HyperOsEasing)
                        val sizeSpec = tween<IntSize>(HYPEROS_MOVE_MS, easing = HyperOsEasing)
                        if (openingDevicePage) {
                            (slideInHorizontally(offsetSpec) { it } + fadeIn(tween(200)))
                                .togetherWith(slideOutHorizontally(offsetSpec) { -it / 5 } + fadeOut(tween(180)))
                        } else {
                            (slideInHorizontally(offsetSpec) { -it / 5 } + fadeIn(tween(200)))
                                .togetherWith(slideOutHorizontally(offsetSpec) { it } + fadeOut(tween(180)))
                        }.using(SizeTransform(clip = false) { _, _ -> sizeSpec })
                    },
                    label = "panelPage",
                ) { pagePackage ->
                    val pageApp = currentApps.firstOrNull { it.packageName == pagePackage }
                    if (pageApp != null) {
                        val ruleResult = runCatching { rules[pageApp.packageName] ?: ruleStore.readOrDefault(pageApp.packageName, pageApp.uid) }
                        if (ruleResult.isFailure) {
                            Column {
                                DevicePageHeader(app = pageApp, onBack = { selectedPackage = null })
                                Spacer(Modifier.height(12.dp))
                                CorruptedRuleRow(pageApp)
                            }
                        } else {
                            DevicePage(
                                app = pageApp,
                                rule = ruleResult.getOrThrow(),
                                deviceScan = deviceScan,
                                onBack = { selectedPackage = null },
                                onTargetSelected = { target ->
                                    val old = rules[pageApp.packageName] ?: ruleStore.readOrDefault(pageApp.packageName, pageApp.uid)
                                    rules[pageApp.packageName] = ruleStore.save(pageApp.packageName, pageApp.uid, old.volumePercent, target)
                                    PreferredDeviceSync.publish(
                                        applicationContext,
                                        pageApp.uid,
                                        target,
                                        hostSource.currentSystemDevice()
                                    )
                                },
                            )
                        }
                    } else {
                        Column {
                            if (showPermissionHint) {
                                InstalledAppsPermissionHint(onClick = onRequestInstalledAppsPermission)
                                Spacer(Modifier.height(12.dp))
                            }
                            when (val currentMediaState = mediaState) {
                                is ActiveMediaAppsState.Error -> PreferencesUnavailable(stringResource(R.string.panel_host_error))
                                is ActiveMediaAppsState.Available -> AppVolumeList(
                                    apps = if (appSettings.hideSystemAppsEnabled) {
                                        currentApps.filter { !it.isSystemApp }
                                    } else {
                                        currentApps
                                    },
                                    rules = rules,
                                    readDefault = ruleStore::readOrDefault,
                                    showVolumePercent = appSettings.volumePercentEnabled,
                                    onSelectOutput = { selectedPackage = it.packageName },
                                    onVolumeChanged = { app, percent ->
                                        rules[app.packageName] = ruleStore.updateVolume(app.packageName, app.uid, percent)
                                        hostSource.replaceRules()
                                        hostSource.setVolume(app.uid, percent)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun rememberDebouncedPlaybackApps(
    apps: List<AdjustableApp>,
    active: Boolean,
): List<AdjustableApp> {
    val debouncer = remember {
        PlaybackPresenceDebouncer(keyOf = AdjustableApp::packageName)
    }
    var generation by remember { mutableIntStateOf(0) }
    val visible = remember(apps, active, generation) {
        if (!active) emptyList() else debouncer.update(apps, android.os.SystemClock.elapsedRealtime())
    }
    val deadline = if (active) debouncer.nextDeadlineMillis() else null
    LaunchedEffect(apps, active, deadline) {
        if (!active || deadline == null) return@LaunchedEffect
        val wait = deadline - android.os.SystemClock.elapsedRealtime()
        if (wait > 0L) delay(wait.milliseconds)
        generation += 1
    }
    return visible
}

@Composable
private fun ObserveAudioDevicesAndFallback(
    source: AudioDevicesSource,
    store: RuleStore,
    rules: MutableMap<String, AppAudioRule>,
    onRulesChanged: () -> Unit,
    onFollowSystem: (uid: Int) -> Unit,
    onScan: (AudioDeviceScan) -> Unit,
) {
    DisposableEffect(source, store) {
        var previousTargets: Set<hk.uwu.soundman.model.AudioDeviceIdentity>? = null
        val removeObserver = source.observe observer@{ scan ->
            onScan(scan)
            if (scan.error != null) return@observer

            val connectedTargets = scan.devices.flatMapTo(mutableSetOf()) { it.candidates }
            if (connectedTargets == previousTargets) return@observer
            previousTargets = connectedTargets

            val persistedRules = try {
                store.readAll()
            } catch (error: RuntimeException) {
                AppLog.error("Unable to reconcile disconnected audio targets", error)
                return@observer
            }
            var rulesChanged = false
            persistedRules.forEach { (packageName, rule) ->
                val fixedTarget = rule.outputTarget as? OutputTarget.Device ?: return@forEach
                if (rule.followsSystemAfterDisconnect || fixedTarget.candidates.any(connectedTargets::contains)) return@forEach
                try {
                    rules[packageName] = store.fallbackToSystem(packageName, rule.uid, fixedTarget)
                    onFollowSystem(rule.uid)
                    rulesChanged = true
                } catch (error: RuntimeException) {
                    AppLog.error("Unable to persist disconnect fallback for $packageName", error)
                }
            }
            if (rulesChanged) onRulesChanged()
        }
        onDispose(removeObserver)
    }
}

@Composable
private fun PanelChrome(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
    ) {
        Text(
            stringResource(R.string.panel_close_symbol),
            modifier = Modifier
                .clickable(onClick = onDismiss)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            color = OnBlurMuted,
            fontSize = 22.sp,
        )
    }
}

@Composable
private fun InstalledAppsPermissionHint(onClick: () -> Unit) {
    Text(
        stringResource(R.string.panel_installed_apps_permission_hint),
        modifier = Modifier
            .fillMaxWidth()
            .blurMaterial(
                purpose = BlurMaterialPurpose.Hint,
                cornerRadius = 16.dp,
                tint = Color(0x24B26A00)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        color = Color(0xFF8A4B00),
        fontSize = 12.sp,
    )
}

@Composable
private fun AppVolumeList(
    apps: List<AdjustableApp>,
    rules: Map<String, AppAudioRule>,
    readDefault: (String, Int) -> AppAudioRule,
    showVolumePercent: Boolean,
    onVolumeChanged: (AdjustableApp, Int) -> Unit,
    onSelectOutput: (AdjustableApp) -> Unit,
) {
    if (apps.isEmpty()) {
        Text(stringResource(R.string.panel_no_playing_apps), color = OnBlurMuted, modifier = Modifier.padding(16.dp))
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        items(apps, key = AdjustableApp::packageName) { app ->
            val itemModifier = Modifier.animateItem()
            val ruleResult = runCatching { rules[app.packageName] ?: readDefault(app.packageName, app.uid) }
            if (ruleResult.isFailure) {
                CorruptedRuleRow(app, itemModifier)
            } else {
                AppVolumeItem(
                    app = app,
                    rule = ruleResult.getOrThrow(),
                    showVolumePercent = showVolumePercent,
                    onVolumeChanged = { onVolumeChanged(app, it) },
                    onSelectOutput = { onSelectOutput(app) },
                    modifier = itemModifier,
                )
            }
        }
    }
}

@Composable
private fun CorruptedRuleRow(app: AdjustableApp, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .blurMaterial(
                purpose = BlurMaterialPurpose.Hint,
                cornerRadius = 20.dp,
                tint = Color(0x18B3261E)
            )
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(app.icon, app.label)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(app.label, fontWeight = FontWeight.Bold, color = Color(0xFF8C1D18))
            Text(stringResource(R.string.rule_corrupted_title), fontSize = 12.sp, color = Color(0xFF8C1D18))
            Text(stringResource(R.string.rule_corrupted_message), fontSize = 11.sp, color = SecondaryText)
        }
    }
}

@Composable
private fun AppVolumeItem(
    app: AdjustableApp,
    rule: AppAudioRule,
    showVolumePercent: Boolean,
    onVolumeChanged: (Int) -> Unit,
    onSelectOutput: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var draftVolume by remember(app.packageName, rule.revision) { mutableStateOf(rule.volumePercent) }
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                app.label,
                color = OnBlurText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (showVolumePercent) {
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(R.string.panel_volume_percent, draftVolume),
                    color = OnBlurMuted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        AppVolumeBar(
            volumePercent = draftVolume,
            appIcon = app.icon,
            onVolumeChange = { draftVolume = it },
            onVolumeChangeFinished = { if (draftVolume != rule.volumePercent) onVolumeChanged(draftVolume) },
            onMoreClick = onSelectOutput,
            moreContentDescription = stringResource(R.string.panel_more_devices),
            contentDescription = stringResource(R.string.panel_volume_bar),
        )
    }
}

@Composable
private fun AppIcon(icon: Drawable, label: String, size: androidx.compose.ui.unit.Dp = 36.dp) {
    Image(
        bitmap = remember(label) { icon.toBitmap(64, 64).asImageBitmap() },
        contentDescription = label,
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp)),
    )
}

@Composable
private fun DevicePage(
    app: AdjustableApp,
    rule: AppAudioRule,
    deviceScan: AudioDeviceScan,
    onBack: () -> Unit,
    onTargetSelected: (OutputTarget) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
    DevicePageHeader(app = app, onBack = onBack)
    Spacer(Modifier.height(16.dp))
    val rows = devicePageRows.build(
        scan = deviceScan,
        rule = rule,
        followSystemName = stringResource(R.string.output_follow_system),
        builtinName = stringResource(R.string.output_device_builtin),
    )
    LazyColumn(Modifier.heightIn(max = 540.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(rows, key = DevicePageRow::key) { row ->
            DeviceRow(
                name = row.name,
                type = row.type,
                selected = row.selected,
                enabled = row.enabled,
                onClick = {
                    val target = checkNotNull(row.clickTarget) { "disconnected device row must not be clickable" }
                    onTargetSelected(target)
                },
            )
        }
    }
    if (deviceScan.error != null) {
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.output_device_scan_denied), color = Color(0xFFB3261E), fontSize = 12.sp)
    }
    }
}

@Composable
private fun DevicePageHeader(app: AdjustableApp, onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onBack,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(app.icon, app.label, size = 44.dp)
        Spacer(Modifier.width(12.dp))
        androidx.compose.material3.Text(
            text = app.label,
            modifier = Modifier
                .weight(1f)
                .background(Color.Transparent),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.SemiBold,
            color = OnBlurText,
            fontSize = 18.sp,
        )
    }
}

@Composable
private fun DeviceRow(
    name: String,
    type: OutputDeviceType?,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colorSpec = tween<Color>(220, easing = HyperOsEasing)
    val selectedBg = Color.White
    val idleBg = Color.White.copy(alpha = 0.22f)
    val targetLabel = when {
        !enabled -> Color.White.copy(alpha = 0.45f)
        selected -> Color(0xFF111111)
        else -> Color.White
    }
    val targetIcon = when {
        !enabled -> Color.White.copy(alpha = 0.45f)
        selected -> Accent
        else -> Color.White
    }
    val container by animateColorAsState(if (selected) selectedBg else idleBg, colorSpec, label = "deviceBg")
    val labelColor by animateColorAsState(targetLabel, colorSpec, label = "deviceLabel")
    val iconColor by animateColorAsState(targetIcon, colorSpec, label = "deviceIcon")
    Box(
        Modifier
            .fillMaxWidth()
            .blurMaterial(
                purpose = if (selected) BlurMaterialPurpose.DeviceSelected else BlurMaterialPurpose.DeviceRow,
                cornerRadius = 22.dp,
                tint = container,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DeviceGlyph(type, iconColor)
            Spacer(Modifier.width(12.dp))
            Text(
                name,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 16.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = labelColor,
            )
        }
    }
}

@Composable
private fun DeviceGlyph(type: OutputDeviceType?, color: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(26.dp)) {
        val stroke = Stroke(width = 2.2.dp.toPx())
        when (type) {
            null -> drawCircle(color, radius = size.minDimension * 0.34f, style = stroke)
            OutputDeviceType.BUILT_IN -> {
                val phoneWidth = size.width * 0.46f
                val phoneHeight = size.height * 0.82f
                val origin = androidx.compose.ui.geometry.Offset(
                    (size.width - phoneWidth) / 2f,
                    (size.height - phoneHeight) / 2f,
                )
                drawRoundRect(
                    color = color,
                    topLeft = origin,
                    size = androidx.compose.ui.geometry.Size(phoneWidth, phoneHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()),
                    style = stroke,
                )
                drawCircle(
                    color,
                    radius = 1.3.dp.toPx(),
                    center = androidx.compose.ui.geometry.Offset(
                        size.width / 2f,
                        origin.y + phoneHeight * 0.82f,
                    ),
                )
            }
            OutputDeviceType.WIRED_HEADSET -> {
                val path = Path().apply {
                    moveTo(size.width * .18f, size.height * .62f)
                    cubicTo(size.width * .18f, size.height * .18f, size.width * .82f, size.height * .18f, size.width * .82f, size.height * .62f)
                }
                drawPath(path, color, style = stroke)
                drawLine(color, center.copy(x = size.width * .18f, y = size.height * .58f), center.copy(x = size.width * .18f, y = size.height * .86f), stroke.width)
                drawLine(color, center.copy(x = size.width * .82f, y = size.height * .58f), center.copy(x = size.width * .82f, y = size.height * .86f), stroke.width)
            }
            OutputDeviceType.BLUETOOTH -> {
                val path = Path().apply {
                    moveTo(size.width * .48f, size.height * .08f)
                    lineTo(size.width * .78f, size.height * .32f)
                    lineTo(size.width * .24f, size.height * .72f)
                    lineTo(size.width * .48f, size.height * .92f)
                    close()
                    moveTo(size.width * .48f, size.height * .08f)
                    lineTo(size.width * .48f, size.height * .92f)
                }
                drawPath(path, color, style = stroke)
            }
            OutputDeviceType.USB -> drawLine(color, center.copy(y = size.height * .12f), center.copy(y = size.height * .82f), stroke.width)
            OutputDeviceType.OTHER -> {
                drawCircle(color, radius = size.minDimension * .34f, style = stroke)
                drawLine(color, center.copy(y = size.height * .37f), center.copy(y = size.height * .63f), stroke.width)
            }
        }
    }
}

@Composable
private fun PreferencesUnavailable(message: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .blurMaterial(
                purpose = BlurMaterialPurpose.Hint,
                cornerRadius = 20.dp,
                tint = Color(0xB8FFFFFF)
            )
            .padding(16.dp),
    ) {
        Text(stringResource(R.string.rule_storage_unavailable_title), fontWeight = FontWeight.Bold, color = Color(0xFFB3261E))
        Spacer(Modifier.height(6.dp))
        Text(message, fontSize = 12.sp, color = SecondaryText)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.rule_storage_no_fallback), fontSize = 11.sp, color = SecondaryText)
    }
}
