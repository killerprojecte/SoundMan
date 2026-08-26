package hk.uwu.soundman.ui.screen

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.kyant.capsule.ContinuousRoundedRectangle
import hk.uwu.soundman.R
import hk.uwu.soundman.generated.AppProperties
import hk.uwu.soundman.log.AppLog
import hk.uwu.soundman.repository.contributor.ContributorLoadState
import hk.uwu.soundman.repository.contributor.ContributorProfile
import hk.uwu.soundman.repository.contributor.ContributorRepository
import hk.uwu.soundman.ui.AppAboutInfo
import hk.uwu.soundman.ui.basic.AppTopBar
import hk.uwu.soundman.ui.basic.SharedScrollBehavior
import hk.uwu.soundman.ui.basic.TopBarStyle
import hk.uwu.soundman.ui.basic.overScrollVertical
import hk.uwu.soundman.ui.basic.rememberSharedScrollBehavior
import hk.uwu.soundman.ui.components.LiquidTopBarButton
import hk.uwu.soundman.ui.components.card.SuperCard
import hk.uwu.soundman.ui.components.motion.ArtRevealItem
import hk.uwu.soundman.ui.components.motion.ArtStaggeredReveal
import hk.uwu.soundman.utils.blend.ColorBlendToken
import hk.uwu.soundman.utils.blend.rememberBlurBackdrop
import hk.uwu.soundman.utils.effect.BgEffectBackground
import hk.uwu.soundman.utils.other.DeviceConfigTools
import hk.uwu.soundman.utils.other.LibraryItem
import hk.uwu.soundman.utils.other.OSVersionTools
import hk.uwu.soundman.utils.other.loadLibraries
import hk.uwu.soundman.utils.pageContentPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Create
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

private val AboutPageHorizontalPadding = 12.dp
private val AboutDeviceInfoCardTopPadding = 20.dp
private val AboutDeviceInfoCardBottomPadding = 12.dp
private val AboutDeviceInfoRowVerticalPadding = 8.dp
private val AboutDeviceInfoHeaderBottomSpacing = 8.dp
private val AboutCardSpacing = 8.dp
private val AboutGradientFadeDistance = 389.dp

/** About 根页小标题与滚动渐变共用的、可测试的阈值规则。 */
internal object AboutTopBarPolicy {
    private const val SmallTitleThreshold = 0.5f

    fun scrollProgress(
        firstVisibleItemIndex: Int,
        firstVisibleItemScrollOffsetPx: Int,
        fadeDistancePx: Float,
    ): Float {
        require(fadeDistancePx > 0f) { "fadeDistancePx must be positive" }
        if (firstVisibleItemIndex > 0) return 1f
        return (firstVisibleItemScrollOffsetPx / fadeDistancePx).coerceIn(0f, 1f)
    }

    fun showSmallTitle(scrollProgress: Float): Boolean = scrollProgress > SmallTitleThreshold

    /**
     * 背景渐变（BgEffectBackground）的 alpha 为 `1f - scrollProgress`，
     * 当 scrollProgress 达到 1f 时背景渐变完全消失，此时才启用 TopBar 渐变遮罩。
     */
    fun showGradientOverlay(scrollProgress: Float): Boolean = scrollProgress >= 1f
}

// ==================== Avatar Cache ====================

private object ContributorAvatarCache {
    private val cache = ConcurrentHashMap<String, ImageBitmap>()

    fun peek(url: String?): ImageBitmap? {
        val key = url?.takeIf { it.isNotBlank() } ?: return null
        return cache[key]
    }

    suspend fun preload(urls: List<String>) {
        urls.distinct().forEach { url -> load(url) }
    }

    suspend fun load(url: String?): ImageBitmap? {
        val key = url?.takeIf { it.isNotBlank() } ?: return null
        cache[key]?.let { return it }

        val image = withContext(Dispatchers.IO) {
            runCatching {
                val connection = URL(key).openConnection() as HttpURLConnection
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                connection.inputStream.use { stream ->
                    BitmapFactory.decodeStream(stream)?.asImageBitmap()
                }
            }.getOrNull()
        }

        if (image != null) {
            cache.putIfAbsent(key, image)
        }
        return cache[key] ?: image
    }
}

// ==================== Route ====================

/** About 页本地持有的子路由。 */
internal sealed interface AboutRoute {
    data object Root : AboutRoute
    data object Contributors : AboutRoute
    data object Licenses : AboutRoute
}

private data class AboutAnimatedRoute(
    val route: AboutRoute,
    val depth: Int,
)

// ==================== Data ====================

private data class CreditEntry(
    val titleRes: Int,
    val summaryRes: Int,
    val url: String,
)

private data class AboutVisualTokens(
    val isDarkTheme: Boolean,
    val backgroundColor: Color,
    val cardBlendColors: List<BlendColorEntry>,
    val logoBlendColors: List<BlendColorEntry>,
)

@Composable
private fun rememberAboutVisualTokens(): AboutVisualTokens {
    val surface = MiuixTheme.colorScheme.surface
    val isDarkTheme = surface.luminance() < 0.5f

    return remember(surface, isDarkTheme) {
        AboutVisualTokens(
            isDarkTheme = isDarkTheme,
            backgroundColor = surface,
            cardBlendColors = aboutCardBlendColors(isDarkTheme),
            logoBlendColors = aboutLogoBlendColors(isDarkTheme),
        )
    }
}

private fun aboutCardBlendColors(isDarkTheme: Boolean): List<BlendColorEntry> {
    return if (isDarkTheme) {
        ColorBlendToken.Overlay_Thin_Dark
    } else {
        ColorBlendToken.Pured_Regular_Light
    }
}

private fun aboutLogoBlendColors(isDarkTheme: Boolean): List<BlendColorEntry> {
    return if (isDarkTheme) {
        listOf(
            BlendColorEntry(Color(0xe6a1a1a1), BlurBlendMode.ColorDodge),
            BlendColorEntry(Color(0x4de6e6e6), BlurBlendMode.LinearLight),
            BlendColorEntry(Color(0xff1af500), BlurBlendMode.Lab),
        )
    } else {
        listOf(
            BlendColorEntry(Color(0xcc4a4a4a), BlurBlendMode.ColorBurn),
            BlendColorEntry(Color(0xff4f4f4f), BlurBlendMode.LinearLight),
            BlendColorEntry(Color(0xff1af200), BlurBlendMode.Lab),
        )
    }
}

// ==================== Entry Point ====================

/**
 * REAREye 风格的本地 About 子路由。每个路由页面自行持有 Scaffold、滚动状态与 TopBar，
 * 因此返回根页时会重新建立完整的 Logo、名称、版本与内容树。
 */
@Composable
internal fun AboutPage(
    bottomInnerPadding: Dp,
    onRootRouteChanged: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val about = remember(context) { AppAboutInfo.load(context) }
    val versionText =
        "${AppProperties.PROJECT_APP_VERSION_NAME}-${AppProperties.GIT_HASH}-r${AppProperties.BUILD_NUMBER}-${AppProperties.BUILD_CHANNEL}"
    val contributorState by ContributorRepository.state.collectAsState()

    var route by remember { mutableStateOf<AboutRoute>(AboutRoute.Root) }
    val animatedRoute = remember(route) {
        AboutAnimatedRoute(
            route = route,
            depth = if (route is AboutRoute.Root) 0 else 1,
        )
    }
    LaunchedEffect(route) {
        onRootRouteChanged(route is AboutRoute.Root)
    }
    val returnToRoot = { route = AboutRoute.Root }

    val entries = remember {
        listOf(
            CreditEntry(
                titleRes = R.string.credits_afdian_title,
                summaryRes = R.string.credits_afdian_desc,
                url = "https://ifdian.net/a/rgbmc",
            ),
            CreditEntry(
                titleRes = R.string.credits_github_title,
                summaryRes = R.string.credits_github_desc,
                url = about.githubUrl,
            )
        )
    }

    LaunchedEffect(Unit) {
        ContributorRepository.preload()
    }
    LaunchedEffect(route) {
        if (route is AboutRoute.Contributors) {
            ContributorRepository.ensureLoaded(force = false)
        }
    }
    LaunchedEffect(contributorState) {
        val loadedState = contributorState as? ContributorLoadState.Loaded ?: return@LaunchedEffect
        ContributorAvatarCache.preload(
            loadedState.contributors.mapNotNull { it.avatar?.takeIf(String::isNotBlank) }
        )
    }

    BackHandler(enabled = route is AboutRoute.Contributors || route is AboutRoute.Licenses) {
        returnToRoot()
    }

    AnimatedContent(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.surface)
            .graphicsLayer { clip = true },
        targetState = animatedRoute,
        contentKey = { it.route },
        transitionSpec = {
            val forward = targetState.depth >= initialState.depth
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
            ) { fullWidth -> if (forward) fullWidth / 9 else -fullWidth / 9 } togetherWith (
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
                    ) { fullWidth -> if (forward) -fullWidth / 12 else fullWidth / 12 }
                    )
        },
        label = "AboutRouteTransition",
    ) { target ->
        when (target.route) {
            AboutRoute.Root -> AboutRootPage(
                bottomInnerPadding = bottomInnerPadding,
                versionText = versionText,
                entries = entries,
                onOpenContributors = { route = AboutRoute.Contributors },
                onOpenLibraries = { route = AboutRoute.Licenses },
            )

            AboutRoute.Contributors -> AboutSecondaryPage(
                title = stringResource(R.string.credits_contributors_title),
                onBack = returnToRoot,
            ) { paddingValues, scrollBehavior ->
                ContributorListContent(
                    bottomInnerPadding = bottomInnerPadding,
                    paddingValues = paddingValues,
                    scrollBehavior = scrollBehavior,
                    state = contributorState,
                )
            }

            AboutRoute.Licenses -> AboutSecondaryPage(
                title = stringResource(R.string.licenses_name),
                onBack = returnToRoot,
            ) { paddingValues, scrollBehavior ->
                LicenseContent(
                    bottomInnerPadding = bottomInnerPadding,
                    paddingValues = paddingValues,
                    scrollBehavior = scrollBehavior,
                )
            }
        }
    }
}

// ==================== Root Page ====================

@Composable
private fun AboutRootPage(
    bottomInnerPadding: Dp,
    versionText: String,
    entries: List<CreditEntry>,
    onOpenContributors: () -> Unit,
    onOpenLibraries: () -> Unit,
) {
    val context = LocalContext.current
    val visualTokens = rememberAboutVisualTokens()
    val blurBackdrop = rememberBlurBackdrop()
    val runtimeShaderSupported = remember { isRuntimeShaderSupported() }
    val scrollBehavior = rememberSharedScrollBehavior()
    val lazyListState = rememberLazyListState()
    val gradientFadeDistancePx = with(LocalDensity.current) {
        AboutGradientFadeDistance.toPx().coerceAtLeast(1f)
    }
    val scrollProgress by remember(lazyListState, gradientFadeDistancePx) {
        derivedStateOf {
            AboutTopBarPolicy.scrollProgress(
                firstVisibleItemIndex = lazyListState.firstVisibleItemIndex,
                firstVisibleItemScrollOffsetPx = lazyListState.firstVisibleItemScrollOffset,
                fadeDistancePx = gradientFadeDistancePx,
            )
        }
    }

    val density = LocalDensity.current
    var logoHeightDp by remember { mutableStateOf(200.dp) }
    var logoAreaY by remember { mutableFloatStateOf(0f) }
    var iconY by remember { mutableFloatStateOf(0f) }
    var projectNameY by remember { mutableFloatStateOf(0f) }
    var versionCodeY by remember { mutableFloatStateOf(0f) }
    var iconProgress by remember { mutableFloatStateOf(0f) }
    var projectNameProgress by remember { mutableFloatStateOf(0f) }
    var versionCodeProgress by remember { mutableFloatStateOf(0f) }
    var initialLogoAreaY by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(lazyListState) {
        snapshotFlow { lazyListState.firstVisibleItemScrollOffset }
            .onEach { offset ->
                if (lazyListState.firstVisibleItemIndex > 0) {
                    if (iconProgress != 1f) iconProgress = 1f
                    if (projectNameProgress != 1f) projectNameProgress = 1f
                    if (versionCodeProgress != 1f) versionCodeProgress = 1f
                    return@onEach
                }
                if (initialLogoAreaY == 0f && logoAreaY > 0f) initialLogoAreaY = logoAreaY
                val refLogoAreaY = if (initialLogoAreaY > 0f) initialLogoAreaY else logoAreaY
                val stage1TotalLength = refLogoAreaY - versionCodeY
                val stage2TotalLength = versionCodeY - projectNameY
                val stage3TotalLength = projectNameY - iconY
                val versionCodeDelay = stage1TotalLength * 0.5f
                versionCodeProgress = ((offset - versionCodeDelay) /
                        (stage1TotalLength - versionCodeDelay).coerceAtLeast(1f)).coerceIn(0f, 1f)
                projectNameProgress = ((offset - stage1TotalLength) /
                        stage2TotalLength.coerceAtLeast(1f)).coerceIn(0f, 1f)
                iconProgress = ((offset - stage1TotalLength - stage2TotalLength) /
                        stage3TotalLength.coerceAtLeast(1f)).coerceIn(0f, 1f)
            }
            .collect { }
    }

    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val topBarHeight = if (statusBarPadding > 0.dp) 80.dp + statusBarPadding else 120.dp
    val contentPaddingValues = PaddingValues(
        top = topBarHeight,
        bottom = bottomInnerPadding,
    )
    val scrollPadding = pageContentPadding(
        contentPaddingValues,
        contentPaddingValues,
        false,
        extraStart = WindowInsets.displayCutout.asPaddingValues()
            .calculateStartPadding(LayoutDirection.Ltr),
        extraEnd = WindowInsets.displayCutout.asPaddingValues()
            .calculateEndPadding(LayoutDirection.Ltr),
    )
    val logoPadding = pageContentPadding(
        contentPaddingValues,
        contentPaddingValues,
        false,
        extraTop = 10.dp,
        extraStart = WindowInsets.displayCutout.asPaddingValues()
            .calculateStartPadding(LayoutDirection.Ltr),
        extraEnd = WindowInsets.displayCutout.asPaddingValues()
            .calculateEndPadding(LayoutDirection.Ltr),
    )
    Box(modifier = Modifier.fillMaxSize()) {
        AboutRootContent(
            bottomInnerPadding = bottomInnerPadding,
            paddingValues = contentPaddingValues,
            scrollBehavior = scrollBehavior,
            backdrop = blurBackdrop,
            visualTokens = visualTokens,
            versionText = versionText,
            entries = entries,
            onOpenContributors = onOpenContributors,
            scrollProgress = scrollProgress,
            lazyListState = lazyListState,
            onOpenLibraries = onOpenLibraries,
            animateEnter = true,
            runtimeShaderSupported = runtimeShaderSupported,
            logoPadding = logoPadding,
            scrollPadding = scrollPadding,
            density = density,
            logoHeightDp = logoHeightDp,
            onLogoHeightChanged = { logoHeightDp = it },
            logoAreaY = logoAreaY,
            onLogoAreaYChanged = { logoAreaY = it },
            iconY = iconY,
            onIconYChanged = { iconY = it },
            projectNameY = projectNameY,
            onProjectNameYChanged = { projectNameY = it },
            versionCodeY = versionCodeY,
            onVersionCodeYChanged = { versionCodeY = it },
            iconProgress = iconProgress,
            projectNameProgress = projectNameProgress,
            versionCodeProgress = versionCodeProgress,
            context = context,
        )
        AppTopBar(
            title = stringResource(R.string.nav_about),
            style = TopBarStyle.Transparent,
            showSmallTitle = AboutTopBarPolicy.showSmallTitle(scrollProgress),
            showGradientOverlay = AboutTopBarPolicy.showGradientOverlay(scrollProgress),
            scrollBehavior = scrollBehavior,
        )
    }
}

@Composable
private fun AboutSecondaryPage(
    title: String,
    onBack: () -> Unit,
    content: @Composable (PaddingValues, SharedScrollBehavior) -> Unit,
) {
    val scrollBehavior = rememberSharedScrollBehavior()
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val topBarHeight = if (statusBarPadding > 0.dp) 80.dp + statusBarPadding else 120.dp
    val contentPaddingValues = PaddingValues(
        top = topBarHeight,
        bottom = 0.dp,
    )
    Box(modifier = Modifier.fillMaxSize()) {
        content(contentPaddingValues, scrollBehavior)
        AppTopBar(
            title = title,
            style = TopBarStyle.Transparent,
            showGradientOverlay = false,
            scrollBehavior = scrollBehavior,
            startAction = { backdropAlpha, shadowAlpha ->
                LiquidTopBarButton(
                    onClick = onBack,
                    backdrop = null,
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.settings_back),
                    backdropAlpha = backdropAlpha,
                    shadowAlpha = shadowAlpha,
                )
            },
        )
    }
}

@Composable
private fun AboutRootContent(
    bottomInnerPadding: Dp,
    paddingValues: PaddingValues,
    scrollBehavior: SharedScrollBehavior,
    backdrop: top.yukonga.miuix.kmp.blur.LayerBackdrop?,
    visualTokens: AboutVisualTokens,
    versionText: String,
    entries: List<CreditEntry>,
    onOpenContributors: () -> Unit,
    scrollProgress: Float,
    lazyListState: LazyListState,
    onOpenLibraries: () -> Unit,
    animateEnter: Boolean,
    runtimeShaderSupported: Boolean,
    logoPadding: PaddingValues,
    scrollPadding: PaddingValues,
    density: androidx.compose.ui.unit.Density,
    logoHeightDp: Dp,
    onLogoHeightChanged: (Dp) -> Unit,
    logoAreaY: Float,
    onLogoAreaYChanged: (Float) -> Unit,
    iconY: Float,
    onIconYChanged: (Float) -> Unit,
    projectNameY: Float,
    onProjectNameYChanged: (Float) -> Unit,
    versionCodeY: Float,
    onVersionCodeYChanged: (Float) -> Unit,
    iconProgress: Float,
    projectNameProgress: Float,
    versionCodeProgress: Float,
    context: android.content.Context,
) {
    BgEffectBackground(
        dynamicBackground = runtimeShaderSupported,
        modifier = Modifier.fillMaxSize(),
        bgModifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier,
        backgroundColor = visualTokens.backgroundColor,
        isDarkTheme = visualTokens.isDarkTheme,
        effectBackground = runtimeShaderSupported,
        alpha = { 1f - scrollProgress },
    ) {
        // Logo 固定悬浮在 LazyColumn 下方，与 REAREye 结构一致
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    top = logoPadding.calculateTopPadding() + 52.dp,
                    start = logoPadding.calculateStartPadding(LayoutDirection.Ltr),
                    end = logoPadding.calculateEndPadding(LayoutDirection.Ltr),
                )
                .onSizeChanged { size ->
                    with(density) { onLogoHeightChanged(size.height.toDp()) }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 图标
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(90.dp)
                    .graphicsLayer {
                        alpha = 1 - iconProgress
                        scaleX = 1 - (iconProgress * 0.05f)
                        scaleY = 1 - (iconProgress * 0.05f)
                    }
                    .onGloballyPositioned { coordinates ->
                        if (iconY != 0f) return@onGloballyPositioned
                        val y = coordinates.positionInWindow().y
                        val size = coordinates.size
                        onIconYChanged(y + size.height)
                    },
            ) {
                Image(
                    modifier = Modifier
                        .size(90.dp)
                        .then(
                            if (backdrop != null) {
                                Modifier.textureBlur(
                                    backdrop = backdrop,
                                    shape = ContinuousRoundedRectangle(24.dp),
                                    blurRadius = 150f,
                                    colors = BlurColors(
                                        blendColors = visualTokens.logoBlendColors,
                                    ),
                                    contentBlendMode = BlendMode.DstIn,
                                    enabled = true,
                                )
                            } else {
                                Modifier
                            },
                        ),
                    painter = painterResource(R.drawable.ic_soundman_hollow),
                    contentDescription = null,
                )
            }

            // 应用名称（带 textureBlur + 消失动画）
            top.yukonga.miuix.kmp.basic.Text(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 5.dp)
                    .onGloballyPositioned { coordinates ->
                        if (projectNameY != 0f) return@onGloballyPositioned
                        val y = coordinates.positionInWindow().y
                        val size = coordinates.size
                        onProjectNameYChanged(y + size.height)
                    }
                    .graphicsLayer {
                        alpha = 1 - projectNameProgress
                        scaleX = 1 - (projectNameProgress * 0.05f)
                        scaleY = 1 - (projectNameProgress * 0.05f)
                    }
                    .then(
                        if (backdrop != null) {
                            Modifier.textureBlur(
                                backdrop = backdrop,
                                shape = ContinuousRoundedRectangle(16.dp),
                                blurRadius = 150f,
                                colors = BlurColors(
                                    blendColors = visualTokens.logoBlendColors,
                                ),
                                contentBlendMode = BlendMode.DstIn,
                                enabled = true,
                            )
                        } else {
                            Modifier
                        },
                    ),
                text = stringResource(R.string.app_name),
                color = MiuixTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold,
                fontSize = 35.sp,
            )

            // 版本号
            top.yukonga.miuix.kmp.basic.Text(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = 1 - versionCodeProgress
                        scaleX = 1 - (versionCodeProgress * 0.05f)
                        scaleY = 1 - (versionCodeProgress * 0.05f)
                    }
                    .onGloballyPositioned { coordinates ->
                        if (versionCodeY != 0f) return@onGloballyPositioned
                        val y = coordinates.positionInWindow().y
                        val size = coordinates.size
                        onVersionCodeYChanged(y + size.height)
                    },
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                text = "v$versionText",
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
        }

        LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .fillMaxSize()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .padding(horizontal = AboutPageHorizontalPadding),
            contentPadding = PaddingValues(
                top = scrollPadding.calculateTopPadding(),
                bottom = paddingValues.calculateBottomPadding() + bottomInnerPadding,
            ),
        ) {
            // 透明占位，高度与 Logo 区域匹配，LazyColumn 滑过它时 Logo 淡出
            item(key = "logoSpacer") {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(
                            logoHeightDp + 52.dp + logoPadding.calculateTopPadding() - scrollPadding.calculateTopPadding() + 126.dp,
                        )
                        .onGloballyPositioned { coordinates ->
                            val y = coordinates.positionInWindow().y
                            val size = coordinates.size
                            onLogoAreaYChanged(y + size.height)
                        },
                    contentAlignment = Alignment.TopCenter,
                    content = { },
                )
            }

            item(key = "content") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = scrollPadding.calculateBottomPadding()),
                    verticalArrangement = Arrangement.spacedBy(AboutCardSpacing),
                ) {
                    AboutDeviceInfoCard(
                        context = context,
                        backdrop = backdrop,
                        visualTokens = visualTokens,
                        animateEnter = animateEnter,
                    )

                    Column(
                        verticalArrangement = Arrangement.spacedBy(AboutCardSpacing),
                    ) {
                        AboutReveal(
                            enabled = animateEnter,
                            revealKey = "contributors",
                            delayMillis = 36,
                        ) {
                            AboutBlurCard(backdrop, visualTokens) {
                                SuperCard(
                                    title = stringResource(R.string.credits_contributors_title),
                                    summary = stringResource(R.string.credits_contributors_desc),
                                    onClick = onOpenContributors,
                                    endActions = {
                                        Icon(
                                            imageVector = MiuixIcons.Create,
                                            tint = MiuixTheme.colorScheme.onSurface,
                                            contentDescription = null
                                        )
                                    }
                                )
                            }
                        }

                        entries.forEachIndexed { index, entry ->
                            AboutReveal(
                                enabled = animateEnter,
                                revealKey = entry.url,
                                delayMillis = (54 + index * 18).coerceAtMost(150),
                            ) {
                                AboutBlurCard(backdrop, visualTokens) {
                                    SuperCard(
                                        title = stringResource(entry.titleRes),
                                        summary = stringResource(entry.summaryRes),
                                        onClick = {
                                            runCatching {
                                                context.startActivity(
                                                    Intent(
                                                        Intent.ACTION_VIEW,
                                                        entry.url.toUri()
                                                    )
                                                )
                                            }.onFailure { error ->
                                                AppLog.error(
                                                    "Unable to open url=${entry.url}",
                                                    error
                                                )
                                            }
                                        },
                                        endActions = {
                                            Icon(
                                                imageVector = MiuixIcons.Link,
                                                tint = MiuixTheme.colorScheme.onSurface,
                                                contentDescription = null
                                            )
                                        }
                                    )
                                }
                            }
                        }

                        AboutReveal(
                            enabled = animateEnter,
                            revealKey = "licenses",
                            delayMillis = (54 + entries.size * 18).coerceAtMost(150),
                        ) {
                            AboutBlurCard(backdrop, visualTokens) {
                                SuperCard(
                                    title = stringResource(R.string.licenses_name),
                                    summary = stringResource(R.string.licenses_name_summary),
                                    onClick = onOpenLibraries,
                                    endActions = {
                                        Icon(
                                            imageVector = MiuixIcons.Info,
                                            tint = MiuixTheme.colorScheme.onSurface,
                                            contentDescription = null
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 带液态玻璃 textureBlur 效果的卡片容器。
 * 当 backdrop 不可用时降级为普通 Card。
 */
@Composable
private fun AboutBlurCard(
    backdrop: top.yukonga.miuix.kmp.blur.LayerBackdrop?,
    visualTokens: AboutVisualTokens,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier
            .then(
                if (backdrop != null) {
                    Modifier.textureBlur(
                        backdrop = backdrop,
                        shape = ContinuousRoundedRectangle(16.dp),
                        blurRadius = 60f,
                        noiseCoefficient = 0.001f,
                        colors = BlurColors(
                            blendColors = visualTokens.cardBlendColors,
                            brightness = 0f,
                            contrast = 1f,
                            saturation = 1f,
                        ),
                        enabled = true,
                    )
                } else {
                    Modifier
                },
            ),
        colors = CardDefaults.defaultColors(
            if (backdrop != null) Color.Transparent else MiuixTheme.colorScheme.background,
            Color.Transparent,
        ),
    ) {
        content()
    }
}

// ==================== Device Info Card ====================

@Composable
private fun AboutDeviceInfoCard(
    context: android.content.Context,
    backdrop: top.yukonga.miuix.kmp.blur.LayerBackdrop?,
    visualTokens: AboutVisualTokens,
    animateEnter: Boolean,
) {
    AboutReveal(
        enabled = animateEnter,
        revealKey = "device_info_card",
        delayMillis = 0,
    ) {
        val layoutDirection = LocalLayoutDirection.current
        val deviceInfoCardPadding = PaddingValues(
            start = BasicComponentDefaults.InsideMargin.calculateStartPadding(layoutDirection),
            top = AboutDeviceInfoCardTopPadding,
            end = BasicComponentDefaults.InsideMargin.calculateEndPadding(layoutDirection),
            bottom = AboutDeviceInfoCardBottomPadding,
        )
        val deviceInfoRowPadding = PaddingValues(vertical = AboutDeviceInfoRowVerticalPadding)
        AboutBlurCard(backdrop, visualTokens) {
            Column(
                modifier = Modifier.padding(deviceInfoCardPadding),
            ) {
                top.yukonga.miuix.kmp.basic.Text(
                    text = DeviceConfigTools.deviceName,
                    modifier = Modifier.padding(bottom = AboutDeviceInfoHeaderBottomSpacing),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Medium,
                    color = BasicComponentDefaults.titleColor().color,
                )

                BasicComponent(
                    title = stringResource(R.string.about_device_name),
                    summary = DeviceConfigTools.marketName,
                    insideMargin = deviceInfoRowPadding,
                )

                BasicComponent(
                    title = stringResource(R.string.about_android_version),
                    summary = DeviceConfigTools.androidVersion,
                    insideMargin = deviceInfoRowPadding,
                )

                BasicComponent(
                    title = stringResource(R.string.about_system_version),
                    summary = OSVersionTools.addVersionSuffix(context),
                    insideMargin = deviceInfoRowPadding,
                )

                BasicComponent(
                    title = stringResource(R.string.module_version_label),
                    summary = rememberVersionText(),
                    insideMargin = deviceInfoRowPadding,
                )

                BasicComponent(
                    title = stringResource(R.string.version_codename_label),
                    summary = AppProperties.PROJECT_APP_VERSION_CODENAME,
                    insideMargin = deviceInfoRowPadding,
                )
            }
        }
    }
}

// ==================== Reveal Wrapper ====================

@Composable
private fun AboutReveal(
    enabled: Boolean,
    revealKey: Any,
    delayMillis: Int,
    content: @Composable () -> Unit,
) {
    if (enabled) {
        ArtStaggeredReveal(
            visible = true,
            revealKey = revealKey,
            delayMillis = delayMillis,
            content = content,
        )
    } else {
        content()
    }
}

// ==================== Contributors ====================

@Composable
private fun rememberSkeletonPulseAlpha(label: String): Float {
    val infiniteTransition = rememberInfiniteTransition(label = label)
    val alpha = infiniteTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "$label-alpha",
    )
    return alpha.value
}

@Composable
private fun ContributorListContent(
    bottomInnerPadding: Dp,
    paddingValues: PaddingValues,
    scrollBehavior: SharedScrollBehavior,
    state: ContributorLoadState,
) {
    val avatarPlaceholderAlpha = rememberSkeletonPulseAlpha("contributor-avatar-skeleton")

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .scrollEndHaptic()
            .overScrollVertical()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .padding(horizontal = AboutPageHorizontalPadding),
        contentPadding = PaddingValues(
            top = paddingValues.calculateTopPadding(),
            bottom = paddingValues.calculateBottomPadding() + bottomInnerPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(AboutCardSpacing),
        overscrollEffect = null,
    ) {
        item { Spacer(modifier = Modifier.height(12.dp)) }

        when (state) {
            ContributorLoadState.Idle,
            ContributorLoadState.Loading -> item {
                ArtRevealItem(visible = true, delayMillis = 40) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            InfiniteProgressIndicator()
                            top.yukonga.miuix.kmp.basic.Text(
                                text = stringResource(R.string.credits_contributors_loading)
                            )
                        }
                    }
                }
            }

            ContributorLoadState.Failed -> item {
                ArtRevealItem(visible = true, delayMillis = 40) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        SuperCard(
                            title = stringResource(R.string.credits_contributors_title),
                            summary = stringResource(R.string.credits_contributors_load_failed),
                            bottomAction = {
                                Button(
                                    onClick = { ContributorRepository.ensureLoaded(force = true) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    top.yukonga.miuix.kmp.basic.Text(
                                        text = stringResource(R.string.credits_contributors_retry)
                                    )
                                }
                            },
                        )
                    }
                }
            }

            is ContributorLoadState.Loaded -> {
                if (state.contributors.isEmpty()) {
                    item {
                        ArtRevealItem(visible = true, delayMillis = 40) {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                top.yukonga.miuix.kmp.basic.Text(
                                    text = stringResource(R.string.credits_contributors_empty),
                                    modifier = Modifier.padding(16.dp),
                                )
                            }
                        }
                    }
                } else {
                    itemsIndexed(
                        state.contributors,
                        key = { _, item -> item.link?.takeIf { it.isNotBlank() } ?: item.name },
                    ) { index, item ->
                        val revealKey = item.link?.takeIf { it.isNotBlank() } ?: item.name
                        ArtStaggeredReveal(
                            visible = true,
                            revealKey = revealKey,
                            delayMillis = (36 + index * 18).coerceAtMost(150),
                        ) {
                            ContributorCard(
                                item = item,
                                avatarPlaceholderAlpha = avatarPlaceholderAlpha,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContributorCard(
    item: ContributorProfile,
    avatarPlaceholderAlpha: Float,
) {
    val context = LocalContext.current
    val link = item.link?.takeIf { it.isNotBlank() }
    val hasLink = link != null

    Card(modifier = Modifier.fillMaxWidth()) {
        SuperCard(
            title = item.name,
            summary = item.description.takeIf { it.isNotBlank() },
            startAction = {
                ContributorAvatar(
                    avatarUrl = item.avatar,
                    placeholderAlpha = avatarPlaceholderAlpha,
                )
            },
            onClick = link?.let { targetLink ->
                {
                    context.startActivity(Intent(Intent.ACTION_VIEW, targetLink.toUri()))
                }
            },
            endActions = {
                if (hasLink) {
                    Icon(
                        imageVector = MiuixIcons.Link,
                        tint = MiuixTheme.colorScheme.onSurface,
                        contentDescription = null,
                    )
                }
            },
        )
    }
}

@Composable
private fun ContributorAvatar(
    avatarUrl: String?,
    placeholderAlpha: Float,
    modifier: Modifier = Modifier,
) {
    var imageBitmap by remember(avatarUrl) {
        mutableStateOf(ContributorAvatarCache.peek(avatarUrl))
    }

    LaunchedEffect(avatarUrl) {
        if (avatarUrl.isNullOrBlank()) return@LaunchedEffect
        imageBitmap = ContributorAvatarCache.load(avatarUrl)
    }

    if (imageBitmap != null) {
        Image(
            bitmap = imageBitmap!!,
            contentDescription = null,
            modifier = modifier
                .size(42.dp)
                .clip(CircleShape),
        )
    } else {
        Box(
            modifier = modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(MiuixTheme.colorScheme.secondaryContainer.copy(alpha = placeholderAlpha)),
        )
    }
}

// ==================== Licenses ====================

@Composable
private fun LicenseContent(
    bottomInnerPadding: Dp,
    paddingValues: PaddingValues,
    scrollBehavior: SharedScrollBehavior,
) {
    val context = LocalContext.current
    val data = remember { loadLibraries(context) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .scrollEndHaptic()
            .overScrollVertical()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .padding(horizontal = AboutPageHorizontalPadding),
        contentPadding = PaddingValues(
            top = paddingValues.calculateTopPadding(),
            bottom = paddingValues.calculateBottomPadding() + bottomInnerPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(AboutCardSpacing),
        overscrollEffect = null,
    ) {
        item { Spacer(modifier = Modifier.height(12.dp)) }
        items(data.libraries) { lib ->
            LibraryItem(lib, data.licenses)
        }
    }
}

// ==================== Utils ====================

@Composable
private fun rememberVersionText(): String {
    return "${AppProperties.PROJECT_APP_VERSION_NAME}-${AppProperties.GIT_HASH}-r${AppProperties.BUILD_NUMBER}-${AppProperties.BUILD_CHANNEL}"
}
