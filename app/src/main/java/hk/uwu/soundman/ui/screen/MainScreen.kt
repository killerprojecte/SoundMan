package hk.uwu.soundman.ui.screen

import android.annotation.SuppressLint
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hk.uwu.soundman.R
import hk.uwu.soundman.data.AppSettingsStore
import hk.uwu.soundman.ui.basic.AppTopBar
import hk.uwu.soundman.ui.basic.LocalOverScrollState
import hk.uwu.soundman.ui.basic.OverScrollState
import hk.uwu.soundman.ui.basic.TopBarStyle
import hk.uwu.soundman.ui.basic.rememberSharedScrollBehavior
import hk.uwu.soundman.ui.components.LiquidBottomTab
import hk.uwu.soundman.ui.components.LiquidBottomTabs
import hk.uwu.soundman.ui.components.LiquidGlassDropdownMenu
import hk.uwu.soundman.ui.components.LiquidGlassDropdownMenuItem
import hk.uwu.soundman.ui.components.LiquidTopBarButton
import hk.uwu.soundman.utils.RootHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Forward
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.kyant.backdrop.backdrops.layerBackdrop as liquidGlassLayerBackdrop

private val ScreenOrder = listOf("home", "settings", "about")

/**
 * Main app container: liquid glass bottom navigation + three pages.
 *
 * TopBar 使用 CollapsibleTopAppBar + SharedScrollBehavior（对齐 miuix TopAppBar 的折叠逻辑），
 * 外层用 ProgressiveBlurTopBar 提供液态玻璃模糊层。
 * - Home/Settings：大标题显示，滚动后收起，小标题出现
 * - About：大标题不显示，小标题滚动一定距离后才出现
 */
@SuppressLint("LocalContextGetResourceValueCall")
@Composable
fun MainScreen(
    settingsStore: AppSettingsStore,
    onOpenOverlay: () -> Unit,
) {
    var currentScreen by rememberSaveable { mutableStateOf("home") }
    var aboutIsRoot by rememberSaveable { mutableStateOf(true) }
    var showMorePopup by remember { mutableStateOf(false) }
    val overScrollState = OverScrollState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    MiuixTheme {
        CompositionLocalProvider(LocalOverScrollState provides overScrollState) {
            val liquidGlassBackdrop = com.kyant.backdrop.backdrops.rememberLayerBackdrop()
            // About 根页也参与同一 backdrop 采样；只有 About 子页隐藏导航栏。
            val chromeBackdrop = liquidGlassBackdrop
            val showBottomBar = currentScreen != "about" || aboutIsRoot

            val homeScrollBehavior = rememberSharedScrollBehavior()
            val settingsScrollBehavior = rememberSharedScrollBehavior()

            val selectedIndex = ScreenOrder.indexOf(currentScreen).coerceAtLeast(0)

            val hapticFeedback = LocalHapticFeedback.current
            val onTabSelect: (Int) -> Unit = { idx ->
                hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                currentScreen = ScreenOrder[idx]
            }

            val iconTint = MiuixTheme.colorScheme.onSurfaceContainer.copy(alpha = 0.8f)

            val navLabels = listOf(
                stringResource(R.string.nav_home),
                stringResource(R.string.nav_settings),
                stringResource(R.string.nav_about),
            )

            val topBarTitle = when (currentScreen) {
                "settings" -> stringResource(R.string.nav_settings)
                else -> stringResource(R.string.app_name)
            }
            val activeScrollBehavior = if (currentScreen == "settings") {
                settingsScrollBehavior
            } else {
                homeScrollBehavior
            }

            Scaffold(
                topBar = {},
                bottomBar = {
                    if (showBottomBar) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 28.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            var liquidSelectedTab by remember { mutableIntStateOf(selectedIndex) }
                            LaunchedEffect(selectedIndex) { liquidSelectedTab = selectedIndex }

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                LiquidBottomTabs(
                                    selectedTabIndex = { liquidSelectedTab },
                                    onTabSelected = { onTabSelect(it) },
                                    backdrop = chromeBackdrop,
                                    tabsCount = 3,
                                    modifier = Modifier
                                        .fillMaxWidth(0.63f)
                                        .height(56.dp)
                                ) {
                                    LiquidBottomTab({ onTabSelect(0) }) {
                                        Icon(
                                            modifier = Modifier.size(24.dp),
                                            imageVector = Icons.Rounded.Home,
                                            contentDescription = navLabels[0],
                                            tint = iconTint
                                        )
                                        Text(navLabels[0], fontSize = 11.sp, color = iconTint)
                                    }
                                    LiquidBottomTab({ onTabSelect(1) }) {
                                        Icon(
                                            modifier = Modifier.size(24.dp),
                                            imageVector = Icons.Rounded.Settings,
                                            contentDescription = navLabels[1],
                                            tint = iconTint
                                        )
                                        Text(navLabels[1], fontSize = 11.sp, color = iconTint)
                                    }
                                    LiquidBottomTab({ onTabSelect(2) }) {
                                        Icon(
                                            modifier = Modifier.size(24.dp),
                                            imageVector = Icons.Rounded.Info,
                                            contentDescription = navLabels[2],
                                            tint = iconTint
                                        )
                                        Text(navLabels[2], fontSize = 11.sp, color = iconTint)
                                    }
                                }
                                LiquidTopBarButton(
                                    onClick = onOpenOverlay,
                                    backdrop = chromeBackdrop,
                                    icon = MiuixIcons.Medium.Forward,
                                    contentDescription = stringResource(R.string.home_open_overlay),
                                    buttonHeight = 56.dp,
                                )
                            }
                        }
                    }
                },
            ) { paddingValues ->
                Box(
                    modifier = Modifier.fillMaxSize()
                ) {
                    AnimatedContent(
                        modifier = Modifier.fillMaxSize(),
                        targetState = currentScreen,
                        contentKey = { it },
                        transitionSpec = {
                            val initialIndex = ScreenOrder.indexOf(initialState).coerceAtLeast(0)
                            val targetIndex = ScreenOrder.indexOf(targetState).coerceAtLeast(0)
                            val forward = targetIndex >= initialIndex

                            fadeIn(
                                animationSpec = tween(
                                    durationMillis = 210,
                                    delayMillis = 50,
                                    easing = LinearOutSlowInEasing,
                                )
                            ) + slideInHorizontally(
                                animationSpec = tween(
                                    durationMillis = 280,
                                    easing = FastOutSlowInEasing,
                                )
                            ) { fullWidth ->
                                if (forward) fullWidth / 9 else -fullWidth / 9
                            } togetherWith (
                                    fadeOut(
                                        animationSpec = tween(
                                            durationMillis = 110,
                                            easing = FastOutLinearInEasing,
                                        )
                                    ) + slideOutHorizontally(
                                        animationSpec = tween(
                                            durationMillis = 190,
                                            easing = FastOutLinearInEasing,
                                        )
                                    ) { fullWidth ->
                                        if (forward) -fullWidth / 12 else fullWidth / 12
                                    }
                                    )
                        },
                        label = "MainScreenTransition",
                    ) { screen ->
                        when (screen) {
                            "home" -> Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MiuixTheme.colorScheme.surface)
                                    .liquidGlassLayerBackdrop(liquidGlassBackdrop)
                            ) {
                                HomePage(
                                    paddingValues = paddingValues,
                                    scrollBehavior = homeScrollBehavior,
                                )
                            }

                            "settings" -> Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MiuixTheme.colorScheme.surface)
                                    .liquidGlassLayerBackdrop(liquidGlassBackdrop)
                            ) {
                                SettingsPage(
                                    paddingValues = paddingValues,
                                    scrollBehavior = settingsScrollBehavior,
                                    settingsStore = settingsStore,
                                    liquidGlassBackdrop = liquidGlassBackdrop,
                                )
                            }

                            "about" -> Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MiuixTheme.colorScheme.surface)
                                    .liquidGlassLayerBackdrop(liquidGlassBackdrop),
                            ) {
                                AboutPage(
                                    bottomInnerPadding = paddingValues.calculateBottomPadding(),
                                    onRootRouteChanged = { aboutIsRoot = it },
                                )
                            }
                        }
                    }
                    // TopBar 作为 overlay 渲染在内容之上，不放在 Scaffold topBar slot 中。
                    // 这样 Scaffold 的 paddingValues 不包含 TopBar 高度，避免 SubcomposeLayout
                    // 在 TopBar 折叠/展开时重新测量导致帧延迟。
                    if (currentScreen != "about") {
                        AppTopBar(
                            title = topBarTitle,
                            style = TopBarStyle.LargeGlass,
                            scrollBehavior = activeScrollBehavior,
                            backdrop = liquidGlassBackdrop,
                            endAction = if (currentScreen == "home") {
                                { backdropAlpha, shadowAlpha ->
                                    LiquidTopBarButton(
                                        onClick = { showMorePopup = true },
                                        backdrop = liquidGlassBackdrop,
                                        icon = MiuixIcons.More,
                                        contentDescription = stringResource(R.string.home_more),
                                        backdropAlpha = backdropAlpha,
                                        shadowAlpha = shadowAlpha,
                                    )
                                }
                            } else null,
                        )
                    }
                    if (showMorePopup) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { showMorePopup = false },
                        )
                    }
                    val statusBarHeight =
                        WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { clip = false }
                            .padding(
                                top = statusBarHeight + 42.dp,
                            )
                            .offset(x = 9.dp),
                        contentAlignment = Alignment.TopEnd,
                    ) {
                        LiquidGlassDropdownMenu(
                            show = showMorePopup,
                            backdrop = liquidGlassBackdrop,
                            onDismiss = { showMorePopup = false },
                        ) {
                            LiquidGlassDropdownMenuItem(
                                text = stringResource(R.string.home_more_kill_systemui),
                                onClick = {
                                    showMorePopup = false
                                    val successMsg =
                                        context.getString(R.string.home_more_kill_systemui_success)
                                    val failedMsg =
                                        context.getString(R.string.home_more_kill_systemui_failed)
                                    coroutineScope.launch(Dispatchers.IO) {
                                        val (exitCode, _) = RootHelper.executeRootCommand(
                                            "pkill -f com.android.systemui",
                                        )
                                        withContext(Dispatchers.Main) {
                                            if (exitCode == 0) {
                                                Toast.makeText(
                                                    context,
                                                    successMsg,
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            } else {
                                                Toast.makeText(
                                                    context,
                                                    failedMsg,
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
