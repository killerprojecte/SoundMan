package hk.uwu.soundman.ui.components.apppicker

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import hk.uwu.soundman.data.InstalledAppsAccess
import hk.uwu.soundman.data.PermissionCatalog
import hk.uwu.soundman.log.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 应用选择器中单个应用的展示数据。
 *
 * @param packageName 包名，作为稳定主键
 * @param label 用户可见的应用名称
 * @param icon 应用图标 Drawable
 * @param isSystemApp 是否为系统应用
 */
data class AppPickerEntry(
    val packageName: String,
    val label: String,
    val icon: Drawable,
    val isSystemApp: Boolean,
)

/**
 * 应用选择器的加载状态。
 */
sealed interface AppPickerLoadState {
    /** 正在加载已安装应用列表。 */
    data object Loading : AppPickerLoadState

    /** 加载完成。 */
    data class Loaded(val apps: List<AppPickerEntry>) : AppPickerLoadState

    /** 加载失败。 */
    data class Error(val message: String) : AppPickerLoadState
}

/**
 * 应用选择器共享状态：负责加载已安装应用、搜索过滤和选择跟踪。
 *
 * 动机：BottomSheet 和 FullPage 两种展示形式共享同一套应用加载、搜索和选择逻辑，
 * 避免重复实现。状态在 Compose 层通过 [rememberAppPickerState] 创建。
 *
 * @param context 应用上下文，用于访问 PackageManager
 * @param initialSelection 初始选中的包名集合（当前黑名单）
 * @param hideSystemApps 是否在列表中隐藏系统应用
 */
@Stable
class AppPickerState(
    private val context: Context,
    private val initialSelection: Set<String>,
    private val hideSystemApps: Boolean = false,
) {
    private val applicationContext = context.applicationContext
    private val packageManager = applicationContext.packageManager
    private val installedAppsAccess = InstalledAppsAccess(PermissionCatalog(applicationContext))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 加载状态流。 */
    private val _loadState = MutableStateFlow<AppPickerLoadState>(AppPickerLoadState.Loading)
    val loadState: StateFlow<AppPickerLoadState> = _loadState.asStateFlow()

    /** 搜索关键词。 */
    var searchQuery by mutableStateOf("")
        private set

    /** 当前选中的包名集合（可变副本，保存时写回存储）。 */
    var selection by mutableStateOf(initialSelection.toMutableSet())
        private set

    /** 过滤后的应用列表：根据搜索关键词和系统应用过滤设置派生。 */
    val filteredApps by derivedStateOf {
        val current = _loadState.value
        if (current !is AppPickerLoadState.Loaded) return@derivedStateOf emptyList()
        val query = searchQuery.trim().lowercase()
        current.apps.filter { entry ->
            (query.isBlank() ||
                    entry.label.lowercase().contains(query) ||
                    entry.packageName.lowercase().contains(query)) &&
                    (!hideSystemApps || !entry.isSystemApp)
        }
    }

    /** 选中数量。 */
    val selectedCount by derivedStateOf { selection.size }

    /** 是否有未保存的更改。 */
    val hasUnsavedChanges by derivedStateOf { selection != initialSelection }

    /** 启动异步加载已安装应用列表。 */
    fun load() {
        if (_loadState.value is AppPickerLoadState.Loading) {
            scope.launch { loadApps() }
        }
    }

    /** 刷新应用列表：强制重新加载，选中项重新置顶排序。 */
    fun refresh() {
        scope.launch { loadApps() }
    }

    /** 更新搜索关键词。 */
    fun updateSearchQuery(query: String) {
        searchQuery = query
    }

    /** 切换指定包名的选中状态。 */
    fun toggleSelection(packageName: String) {
        selection = selection.toMutableSet().apply {
            if (!add(packageName)) remove(packageName)
        }
    }

    /** 判断指定包名是否已选中。 */
    fun isSelected(packageName: String): Boolean = packageName in selection

    /** 获取当前选中的包名集合（不可变副本）。 */
    fun currentSelection(): Set<String> = selection.toSet()

    private suspend fun loadApps() {
        if (!installedAppsAccess.hasAccess(applicationContext)) {
            _loadState.value = AppPickerLoadState.Error("Installed apps permission not granted")
            AppLog.warn("AppPickerState: installed-apps access denied")
            return
        }
        try {
            val apps = withContext(Dispatchers.IO) {
                val all = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                    .filter { info ->
                        // 过滤掉没有启动入口的应用
                        packageManager.getLaunchIntentForPackage(info.packageName) != null
                    }
                    .map { info ->
                        AppPickerEntry(
                            packageName = info.packageName,
                            label = info.loadLabel(packageManager).toString(),
                            icon = info.loadIcon(packageManager),
                            isSystemApp = info.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                        )
                    }
                    .sortedBy { it.label.lowercase() }

                // 选中项置顶，各自组内保持字母排序
                val currentSelection = selection
                all.partition { it.packageName in currentSelection }
                    .let { (selected, unselected) -> selected + unselected }
            }
            _loadState.value = AppPickerLoadState.Loaded(apps)
            AppLog.info("AppPickerState: loaded ${apps.size} apps")
        } catch (error: Throwable) {
            AppLog.error("AppPickerState: failed to load installed apps", error)
            _loadState.value = AppPickerLoadState.Error(error.message ?: "Unknown error")
        }
    }
}
