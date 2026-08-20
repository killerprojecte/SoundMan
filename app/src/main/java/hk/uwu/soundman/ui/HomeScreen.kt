package hk.uwu.soundman.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import hk.uwu.soundman.R
import hk.uwu.soundman.data.AppSettings
import hk.uwu.soundman.data.AppSettingsStore
import hk.uwu.soundman.log.AppLog
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.SwitchDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.VolumeUp
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val HomeHyperOsEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private const val HOME_PAGE_MOVE_MS = 360
private val HomeOnGlass = Color.White.copy(alpha = 0.88f)
private val HomeMuted = Color.White.copy(alpha = 0.55f)
private val InactiveBannerFill = Color(0xB8E53935)
private val InactiveBannerTitle = Color.White.copy(alpha = 0.95f)
private val InactiveBannerHint = Color.White.copy(alpha = 0.82f)

private enum class HomePage {
    HOME,
    SETTINGS,
}

@Composable
fun HomeScreen(
    settingsStore: AppSettingsStore,
    onOpenOverlay: () -> Unit,
) {
    val context = LocalContext.current
    val about = remember(context) { AppAboutInfo.load(context) }
    var xposed by remember { mutableStateOf(XposedStatusInfo.load()) }
    var settings by remember(settingsStore) { mutableStateOf(settingsStore.read()) }
    var pageName by rememberSaveable { mutableStateOf(HomePage.HOME.name) }
    val page = HomePage.valueOf(pageName)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                xposed = XposedStatusInfo.load()
                settings = settingsStore.read()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    BackHandler(enabled = page == HomePage.SETTINGS) { pageName = HomePage.HOME.name }

    MiuixTheme {
        BlurMaterialHost(
            smoothCornersEnabled = settings.smoothCornersEnabled,
            modifier = Modifier.fillMaxSize(),
        ) {
            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    val enteringSettings = targetState == HomePage.SETTINGS
                    if (enteringSettings) {
                        (slideInHorizontally(
                            tween(
                                HOME_PAGE_MOVE_MS,
                                easing = HomeHyperOsEasing
                            )
                        ) { it / 4 } + fadeIn(tween(220)))
                            .togetherWith(
                                slideOutHorizontally(
                                    tween(
                                        HOME_PAGE_MOVE_MS,
                                        easing = HomeHyperOsEasing
                                    )
                                ) { -it / 5 } + fadeOut(tween(180)))
                    } else {
                        (slideInHorizontally(
                            tween(
                                HOME_PAGE_MOVE_MS,
                                easing = HomeHyperOsEasing
                            )
                        ) { -it / 5 } + fadeIn(tween(220)))
                            .togetherWith(
                                slideOutHorizontally(
                                    tween(
                                        HOME_PAGE_MOVE_MS,
                                        easing = HomeHyperOsEasing
                                    )
                                ) { it / 4 } + fadeOut(tween(180)))
                    }
                },
                label = "homePage",
            ) { targetPage ->
                when (targetPage) {
                    HomePage.HOME -> HomePageContent(
                        context = context,
                        about = about,
                        xposed = xposed,
                        onOpenOverlay = onOpenOverlay,
                        onOpenSettings = { pageName = HomePage.SETTINGS.name },
                    )

                    HomePage.SETTINGS -> SettingsPage(
                        settings = settings,
                        onBack = { pageName = HomePage.HOME.name },
                        onSmoothCornersChanged = {
                            settings = settingsStore.setSmoothCornersEnabled(it)
                        },
                        onVolumePercentChanged = {
                            settings = settingsStore.setVolumePercentEnabled(it)
                        },
                        onSystemUiBuiltinVolumePanelChanged = {
                            settings = settingsStore.setSystemUiBuiltinVolumePanelEnabled(it)
                        },
                        onHideSystemAppsChanged = {
                            settings = settingsStore.setHideSystemAppsEnabled(it)
                        },
                        onAlarmFirstChanged = {
                            settings = settingsStore.setAlarmFirstEnabled(it)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun HomePageContent(
    context: Context,
    about: AppAboutInfo,
    xposed: XposedStatusInfo,
    onOpenOverlay: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        if (!xposed.active) {
            InactiveBanner(
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .padding(top = 16.dp),
            )
        }
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = maxHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                IdentityCard(context = context, about = about)
                Spacer(Modifier.height(14.dp))
                VersionCard(about = about)
                Spacer(Modifier.height(14.dp))
                OverlayEntryButton(onClick = onOpenOverlay)
                Spacer(Modifier.height(14.dp))
                SettingsEntryButton(onClick = onOpenSettings)
                Spacer(Modifier.height(14.dp))
                GithubEntryButton(url = about.githubUrl)
            }
        }
    }
}

@Composable
private fun InactiveBanner(modifier: Modifier = Modifier) {
    GlassSurface(
        modifier = modifier.fillMaxWidth(),
        fill = InactiveBannerFill,
        border = Color.White.copy(alpha = 0.18f),
        purpose = BlurMaterialPurpose.Hint,
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Text(
                stringResource(R.string.home_xposed_inactive),
                color = InactiveBannerTitle,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.home_xposed_inactive_hint),
                color = InactiveBannerHint,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun IdentityCard(
    context: Context,
    about: AppAboutInfo,
) {
    val icon = remember(context.packageName) {
        context.packageManager.getApplicationIcon(context.packageName).toBitmap(128, 128)
            .asImageBitmap()
    }
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        fill = OverlayGlassFill,
        border = OverlayGlassBorder,
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                bitmap = icon,
                contentDescription = about.label,
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(16.dp)),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    about.label,
                    color = HomeOnGlass,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.home_author, about.author),
                    color = HomeMuted,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

@Composable
private fun VersionCard(about: AppAboutInfo) {
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        fill = OverlayGlassFill,
        border = OverlayGlassBorder,
    ) {
        Column(
            Modifier.padding(horizontal = 18.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AboutInfoLine(
                title = stringResource(R.string.version_codename_label),
                value = about.versionCodename,
            )
            AboutInfoLine(
                title = stringResource(R.string.module_version_label),
                value = about.moduleVersion,
            )
            AboutInfoLine(
                title = stringResource(R.string.home_status_channel),
                value = about.buildChannel,
            )
            AboutInfoLine(
                title = stringResource(R.string.home_git_branch),
                value = about.gitBranch,
            )
        }
    }
}

@Composable
private fun AboutInfoLine(
    title: String,
    value: String,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, color = HomeMuted, fontSize = 12.sp)
        Spacer(Modifier.height(2.dp))
        Text(
            value,
            color = HomeOnGlass,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun OverlayEntryButton(onClick: () -> Unit) {
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = HomeButtonRadius,
        fill = OverlayGlassFill,
        border = OverlayGlassBorder,
        purpose = BlurMaterialPurpose.Action,
        onClick = onClick,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = MiuixIcons.Regular.VolumeUp,
                contentDescription = null,
                tint = HomeOnGlass,
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.home_open_overlay),
                color = HomeOnGlass,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun SettingsEntryButton(onClick: () -> Unit) {
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = HomeButtonRadius,
        fill = OverlayGlassFill,
        border = OverlayGlassBorder,
        purpose = BlurMaterialPurpose.Action,
        onClick = onClick,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = MiuixIcons.Regular.Settings,
                contentDescription = null,
                tint = HomeOnGlass,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.home_settings),
                color = HomeOnGlass,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun SettingsPage(
    settings: AppSettings,
    onBack: () -> Unit,
    onSmoothCornersChanged: (Boolean) -> Unit,
    onVolumePercentChanged: (Boolean) -> Unit,
    onSystemUiBuiltinVolumePanelChanged: (Boolean) -> Unit,
    onHideSystemAppsChanged: (Boolean) -> Unit,
    onAlarmFirstChanged: (Boolean) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        GlassSurface(
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = HomeButtonRadius,
            fill = OverlayGlassFill,
            border = OverlayGlassBorder,
            purpose = BlurMaterialPurpose.Action,
            onClick = onBack,
        ) {
            androidx.compose.foundation.layout.Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 16.dp),
            ) {
                Icon(
                    imageVector = MiuixIcons.Regular.Back,
                    contentDescription = stringResource(R.string.settings_back),
                    tint = HomeMuted,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .size(24.dp),
                )
                Text(
                    stringResource(R.string.settings_title),
                    modifier = Modifier.align(Alignment.Center),
                    color = HomeOnGlass,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        SettingsToggleCard(
            title = stringResource(R.string.settings_smooth_corners),
            summary = stringResource(R.string.settings_smooth_corners_summary),
            checked = settings.smoothCornersEnabled,
            onCheckedChange = onSmoothCornersChanged,
        )
        SettingsToggleCard(
            title = stringResource(R.string.settings_volume_percent),
            summary = stringResource(R.string.settings_volume_percent_summary),
            checked = settings.volumePercentEnabled,
            onCheckedChange = onVolumePercentChanged,
        )
        SettingsToggleCard(
            title = stringResource(R.string.settings_systemui_builtin_volume_panel),
            summary = stringResource(R.string.settings_systemui_builtin_volume_panel_summary),
            checked = settings.systemUiBuiltinVolumePanelEnabled,
            onCheckedChange = onSystemUiBuiltinVolumePanelChanged,
        )
        SettingsToggleCard(
            title = stringResource(R.string.settings_hide_system_apps),
            summary = stringResource(R.string.settings_hide_system_apps_summary),
            checked = settings.hideSystemAppsEnabled,
            onCheckedChange = onHideSystemAppsChanged,
        )
        SettingsToggleCard(
            title = stringResource(R.string.settings_alarm_first),
            summary = stringResource(R.string.settings_alarm_first_summary),
            checked = settings.alarmFirstEnabled,
            onCheckedChange = onAlarmFirstChanged,
        )
    }
}

@Composable
private fun SettingsToggleCard(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        fill = OverlayGlassFill,
        border = OverlayGlassBorder,
        onClick = { onCheckedChange(!checked) },
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = HomeOnGlass,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    summary,
                    color = HomeMuted,
                    fontSize = 12.sp,
                )
            }
            Spacer(Modifier.width(16.dp))
            Switch(
                checked = checked,
                onCheckedChange = null,
                colors = SwitchDefaults.switchColors(
                    checkedThumbColor = Color(0xFFEAF2FF),
                    uncheckedThumbColor = Color(0xFF9A9BA1),
                    disabledCheckedThumbColor = Color(0x99EAF2FF),
                    disabledUncheckedThumbColor = Color(0x889A9BA1),
                    checkedTrackColor = Color(0xFF3482FF),
                    uncheckedTrackColor = Color(0x4D2D2F35),
                    disabledCheckedTrackColor = Color(0x993482FF),
                    disabledUncheckedTrackColor = Color(0x443F4148),
                ),
            )
        }
    }
}

@Composable
private fun GithubEntryButton(url: String) {
    val context = LocalContext.current
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = HomeButtonRadius,
        fill = OverlayGlassFill,
        border = OverlayGlassBorder,
        onClick = { openGithub(context, url) },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_github),
                contentDescription = null,
                tint = HomeOnGlass,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.home_github),
                color = HomeOnGlass,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

private fun openGithub(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    }.onFailure { error ->
        AppLog.error("Unable to open GitHub url=$url", error)
    }
}
