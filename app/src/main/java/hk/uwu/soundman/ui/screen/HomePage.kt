package hk.uwu.soundman.ui.screen

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DoNotDisturb
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.core.net.toUri
import com.highcapable.yukihookapi.YukiHookAPI
import hk.uwu.soundman.R
import hk.uwu.soundman.generated.AppProperties
import hk.uwu.soundman.ui.AppVersionCopy
import hk.uwu.soundman.ui.basic.SharedScrollBehavior
import hk.uwu.soundman.ui.basic.overScrollVertical
import hk.uwu.soundman.ui.components.InfoLine
import hk.uwu.soundman.ui.components.ModuleInfoCard
import hk.uwu.soundman.ui.components.ModuleStatusCard
import hk.uwu.soundman.ui.components.rememberModuleStatusCardPalette
import hk.uwu.soundman.ui.components.rememberStatusCardPalette
import hk.uwu.soundman.utils.RootHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import java.net.HttpURLConnection
import java.net.URL

private object UpdateInfoCache {
    val lock = Mutex()
    var latestCommitHash: String? = null
}

private const val STAR_CARD_PREFS_NAME = "soundman_home_cards"
private const val STAR_CARD_DISMISSED_KEY = "star_card_dismissed"

/**
 * 首页：模块状态 + 模块信息 + 更新信息 + 打开音量面板按钮。
 *
 * 使用移植的 NexioSchedule 组件：CollapsibleTopAppBar + ProgressiveBlurTopBar。
 * 模块状态通过 YukiHookAPI 获取，对齐 REAREye 的 HomeScreen 设计。
 */
@Composable
fun HomePage(
    paddingValues: PaddingValues,
    scrollBehavior: SharedScrollBehavior,
) {
    val context = LocalContext.current

    // 与 REAREye 当前 HomeScreen 一致：状态卡直接读取 YukiHookAPI，避免 remember/lifecycle
    // 将首次 inactive 快照长期保留。
    val isActivated = YukiHookAPI.Status.isModuleActive
    var hasRoot by remember { mutableStateOf<Boolean?>(null) }

    var latestCommitHash by remember { mutableStateOf<String?>(null) }
    var isCheckingUpdate by remember { mutableStateOf(false) }

    // Star 卡片：dev 通道始终显示，其他通道点击后持久化 dismiss 状态
    val isDevChannel = AppProperties.BUILD_CHANNEL == "dev"
    val starCardPrefs = remember {
        context.getSharedPreferences(
            STAR_CARD_PREFS_NAME,
            android.content.Context.MODE_PRIVATE
        )
    }
    var starCardDismissed by remember {
        mutableStateOf(
            !isDevChannel && starCardPrefs.getBoolean(
                STAR_CARD_DISMISSED_KEY,
                false
            )
        )
    }
    val showStarCard = isDevChannel || !starCardDismissed

    LaunchedEffect(Unit) {
        hasRoot = withContext(Dispatchers.IO) {
            RootHelper.hasRootAccess()
        }
    }

    LaunchedEffect(Unit) {
        if (!UpdateInfoCache.latestCommitHash.isNullOrBlank()) {
            latestCommitHash = UpdateInfoCache.latestCommitHash
            return@LaunchedEffect
        }

        isCheckingUpdate = true
        latestCommitHash = UpdateInfoCache.lock.withLock {
            if (UpdateInfoCache.latestCommitHash.isNullOrBlank()) {
                val fetchedHash = fetchLatestCommitHashFromNetwork()
                if (!fetchedHash.isNullOrBlank()) {
                    UpdateInfoCache.latestCommitHash = fetchedHash
                }
            }
            UpdateInfoCache.latestCommitHash
        }
        isCheckingUpdate = false
    }

    val lazyListState = rememberLazyListState()

    // 对齐 NexioSchedule-ref：paddingValues 来自 Scaffold(topBar={})，只包含状态栏 inset（稳定值）。
    // topBarHeightDp 来自 scrollBehavior.currentHeightPx（由 onSizeChanged 直接更新，与滚动同步）。
    // 两者叠加 = 状态栏高度 + TopBar 高度，避免 SubcomposeLayout 帧延迟。
    val density = LocalDensity.current
    val topBarHeightDp = with(density) { scrollBehavior.currentHeightPx.toDp() }

    // 状态卡配色：对齐 SukiSU-Ultra 的三层策略（Monet → 暗黑 → 亮色）
    val statusPalette = rememberModuleStatusCardPalette(activated = isActivated)

    val statusTitle = if (isActivated) {
        stringResource(R.string.home_status_working)
    } else {
        stringResource(R.string.home_status_inactive)
    }
    val statusSummary = stringResource(
        R.string.home_working_version,
        AppProperties.BUILD_NUMBER.toString(),
    )

    // 更新检查
    val normalizedCurrentHash = AppProperties.GIT_HASH.take(7).lowercase()
    val normalizedLatestHash = latestCommitHash?.take(7)?.lowercase()
    val showUpdateWarning =
        !isCheckingUpdate && !normalizedLatestHash.isNullOrBlank() && normalizedLatestHash != normalizedCurrentHash

    // 模块版本串
    val moduleVersion = AppVersionCopy.moduleVersion(
        versionName = AppProperties.PROJECT_APP_VERSION_NAME,
        gitHash = AppProperties.GIT_HASH,
        buildNumber = AppProperties.BUILD_NUMBER,
        channel = AppProperties.BUILD_CHANNEL,
    )

    // 模块信息卡首行：状态
    val statusValue = if (isActivated) {
        stringResource(R.string.module_is_activated)
    } else {
        stringResource(R.string.module_not_activated)
    }

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
            verticalArrangement = Arrangement.spacedBy(12.dp),
            overscrollEffect = null,
        ) {
            item(key = "status") {
                ModuleStatusCard(
                    title = statusTitle,
                    summary = statusSummary,
                    activated = isActivated,
                    palette = statusPalette,
                )
            }

            if (hasRoot == false) {
                item(key = "root_warning") {
                    RootWarningCard()
                }
            }

            if (showUpdateWarning) {
                item(key = "update_warning") {
                    UpdateWarningCard(
                        currentHash = AppProperties.GIT_HASH.take(7),
                        latestHash = latestCommitHash?.take(7).orEmpty(),
                    )
                }
            }

            @Suppress("KotlinConstantConditions")
            if (showStarCard) {
                item(key = "star_card") {
                    StarCard(
                        onDismissed = {
                            if (!isDevChannel) {
                                starCardPrefs.edit { putBoolean(STAR_CARD_DISMISSED_KEY, true) }
                                starCardDismissed = true
                            }
                        },
                    )
                }
            }

            item(key = "info") {
                ModuleInfoCard(
                    items = listOf(
                        stringResource(R.string.status_card) to statusValue,
                        stringResource(R.string.module_version_label) to moduleVersion,
                        stringResource(R.string.version_codename_label) to AppProperties.PROJECT_APP_VERSION_CODENAME,
                        stringResource(R.string.home_status_channel) to AppProperties.BUILD_CHANNEL,
                    ),
                )
            }

            item(key = "update_info") {
                UpdateInfoCard(
                    currentHash = AppProperties.GIT_HASH,
                    latestHash = latestCommitHash,
                    checking = isCheckingUpdate,
                )
            }
        }
    }
}

/**
 * 从 GitHub API 获取最新提交哈希。
 *
 * 动机：对齐 REAREye 的 fetchLatestCommitHashFromNetwork，使用 HttpURLConnection
 * 替代 OkHttp 以避免引入额外依赖。
 */
private suspend fun fetchLatestCommitHashFromNetwork(): String? {
    return withContext(Dispatchers.IO) {
        runCatching {
            val branchParts = AppProperties.GIT_BRANCH.split("/")
            val owner = branchParts.getOrNull(0) ?: "killerprojecte"
            val repo = branchParts.getOrNull(1) ?: "SoundMan"
            val branch = branchParts.getOrNull(2)?.takeIf { it.isNotBlank() && it != "unknown" }
                ?: "master"
            val url = URL("https://api.github.com/repos/$owner/$repo/commits/$branch")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            try {
                if (connection.responseCode in 200..299) {
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    JSONObject(body).optString("sha", "").take(7).ifBlank { null }
                } else {
                    null
                }
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }
}

/**
 * Root 权限缺失警告卡片，对齐 REAREye 非 Monet 主题下的硬编码配色。
 */
@Composable
private fun RootWarningCard() {
    val palette = rememberStatusCardPalette(
        accent = Color(0xFFD94B4B),
        darkContainer = Color(0xFF310808),
        lightContainer = Color(0xFFFDE9E9),
        darkTitle = Color(0xFFE08080),
        lightTitle = Color(0xFF8C1F1F),
        darkSummary = Color(0xFFCC6A6A),
        lightSummary = Color(0xFFA63737),
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(108.dp),
        colors = CardDefaults.defaultColors(color = palette.container),
        insideMargin = PaddingValues(14.dp),
        pressFeedbackType = PressFeedbackType.Tilt,
        showIndication = false,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Icon(
                imageVector = Icons.Outlined.DoNotDisturb,
                contentDescription = null,
                tint = palette.icon,
                modifier = Modifier
                    .size(108.dp)
                    .align(Alignment.BottomEnd)
                    .offset(x = 50.dp, y = 42.dp),
            )
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.home_root_warning_title),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.title,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.home_root_warning_desc),
                    style = MiuixTheme.textStyles.body2,
                    color = palette.summary,
                )
            }
        }
    }
}

/**
 * 版本更新警告卡片，当当前提交与最新提交不一致时显示。
 *
 * 对齐 REAREye 的 UpdateWarningCard 设计。
 */
@Composable
private fun UpdateWarningCard(currentHash: String, latestHash: String) {
    val context = LocalContext.current
    val palette = rememberStatusCardPalette(
        accent = Color(0xFFE0A100),
        darkContainer = Color(0xFF332808),
        lightContainer = Color(0xFFFFF3CD),
        darkTitle = Color(0xFFE8C87A),
        lightTitle = Color(0xFF7A5A00),
        darkSummary = Color(0xFFD4B264),
        lightSummary = Color(0xFF8A6B00),
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(108.dp),
        colors = CardDefaults.defaultColors(color = palette.container),
        insideMargin = PaddingValues(14.dp),
        onClick = {
            val targetUrl =
                if (AppProperties.BUILD_CHANNEL == "canary" || AppProperties.BUILD_CHANNEL == "dev") {
                    "https://github.com/killerprojecte/SoundMan/actions"
                } else {
                    "https://github.com/killerprojecte/SoundMan/releases"
                }
            context.startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    targetUrl.toUri(),
                ),
            )
        },
        pressFeedbackType = PressFeedbackType.Tilt,
        showIndication = false,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Icon(
                imageVector = Icons.Outlined.Sync,
                contentDescription = null,
                tint = palette.icon,
                modifier = Modifier
                    .size(108.dp)
                    .align(Alignment.BottomEnd)
                    .offset(x = 50.dp, y = 44.dp),
            )
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.home_update_warning_title),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.title,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(
                        R.string.home_update_warning_desc,
                        currentHash,
                        latestHash,
                    ),
                    style = MiuixTheme.textStyles.body2,
                    color = palette.summary,
                )
            }
        }
    }
}

/**
 * 更新信息卡片，展示当前提交和最新提交哈希。
 *
 * 对齐 REAREye 的 UpdateInfoCard 设计。
 */
@Composable
private fun UpdateInfoCard(
    currentHash: String,
    latestHash: String?,
    checking: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(16.dp),
    ) {
        Text(
            text = stringResource(R.string.home_update_title),
            style = MiuixTheme.textStyles.title3,
        )
        Spacer(modifier = Modifier.height(8.dp))
        InfoLine(
            title = stringResource(R.string.home_update_current),
            value = currentHash,
        )
        Spacer(modifier = Modifier.height(8.dp))
        InfoLine(
            title = stringResource(R.string.home_update_latest),
            value = when {
                checking -> stringResource(R.string.home_update_checking)
                latestHash.isNullOrBlank() -> stringResource(R.string.home_update_unknown)
                else -> latestHash
            },
        )
    }
}

/**
 * Star 引导卡片，请求用户在 GitHub 上给项目点 Star。
 *
 * 点击后跳转 GitHub 主页并标记 dismiss；dev 通道始终显示。
 */
@Composable
private fun StarCard(onDismissed: () -> Unit) {
    val context = LocalContext.current
    val palette = rememberStatusCardPalette(
        accent = Color(0xFFF5A623),
        darkContainer = Color(0xFF332B08),
        lightContainer = Color(0xFFFFF8E1),
        darkTitle = Color(0xFFE8C87A),
        lightTitle = Color(0xFF7A5A00),
        darkSummary = Color(0xFFD4B264),
        lightSummary = Color(0xFF8A6B00),
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(108.dp),
        colors = CardDefaults.defaultColors(color = palette.container),
        insideMargin = PaddingValues(14.dp),
        onClick = {
            context.startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    "https://github.com/killerprojecte/SoundMan".toUri(),
                ),
            )
            onDismissed()
        },
        pressFeedbackType = PressFeedbackType.Tilt,
        showIndication = false,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Icon(
                imageVector = Icons.Outlined.StarBorder,
                contentDescription = null,
                tint = palette.icon,
                modifier = Modifier
                    .size(108.dp)
                    .align(Alignment.BottomEnd)
                    .offset(x = 50.dp, y = 44.dp),
            )
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.home_star_title),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.title,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.home_star_desc),
                    style = MiuixTheme.textStyles.body2,
                    color = palette.summary,
                )
            }
        }
    }
}
