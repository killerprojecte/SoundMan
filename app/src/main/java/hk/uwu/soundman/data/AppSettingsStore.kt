package hk.uwu.soundman.data

import android.content.Context
import android.content.SharedPreferences
import com.highcapable.yukihookapi.hook.factory.prefs
import hk.uwu.soundman.log.AppLog

internal const val APP_SETTINGS_PREFERENCES_NAME = "soundman_app_settings"
internal const val SYSTEM_UI_SETTINGS_PREFERENCES_NAME = "soundman_systemui_settings"

/** 应用内可持久化的视觉与面板偏好。 */
data class AppSettings(
    val smoothCornersEnabled: Boolean = AppSettingsDefaults.SMOOTH_CORNERS_ENABLED,
    val volumePercentEnabled: Boolean = AppSettingsDefaults.VOLUME_PERCENT_ENABLED,
    val systemUiBuiltinVolumePanelEnabled: Boolean = AppSettingsDefaults.SYSTEM_UI_BUILTIN_VOLUME_PANEL_ENABLED,
    val hideSystemAppsEnabled: Boolean = AppSettingsDefaults.HIDE_SYSTEM_APPS_ENABLED,
    val alarmFirstEnabled: Boolean = AppSettingsDefaults.ALARM_FIRST_ENABLED,
)

/** 设置默认值，供存储实现与纯 JVM 测试共享。 */
object AppSettingsDefaults {
    const val SMOOTH_CORNERS_ENABLED = false
    const val VOLUME_PERCENT_ENABLED = false
    const val SYSTEM_UI_BUILTIN_VOLUME_PANEL_ENABLED = false
    const val HIDE_SYSTEM_APPS_ENABLED = false
    const val ALARM_FIRST_ENABLED = false
}

/** SharedPreferences 键名的唯一来源，避免读写两端发生漂移。 */
object AppSettingsKeys {
    const val SMOOTH_CORNERS = "smooth_corners_enabled"
    const val VOLUME_PERCENT = "volume_percent_enabled"
    const val SYSTEM_UI_BUILTIN_VOLUME_PANEL = "system_ui_builtin_volume_panel_enabled"
    const val HIDE_SYSTEM_APPS = "hide_system_apps_enabled"
    const val ALARM_FIRST = "alarm_first_enabled"

    val all: Set<String> = setOf(
        SMOOTH_CORNERS,
        VOLUME_PERCENT,
        SYSTEM_UI_BUILTIN_VOLUME_PANEL,
        HIDE_SYSTEM_APPS,
        ALARM_FIRST,
    )
}

/**
 * 应用设置读写契约。
 *
 * 动机：主页和悬浮窗使用同一组明确的设置语义，同时隔离 Android 存储细节。
 */
interface AppSettingsStore {
    /** 读取完整设置快照；存储异常会直接抛出。 */
    fun read(): AppSettings

    /** 持久化平滑圆角开关，并返回最新快照。 */
    fun setSmoothCornersEnabled(enabled: Boolean): AppSettings

    /** 持久化音量百分比开关，并返回最新快照。 */
    fun setVolumePercentEnabled(enabled: Boolean): AppSettings

    /** 持久化实验性 SystemUI 内置面板开关，并返回最新快照。 */
    fun setSystemUiBuiltinVolumePanelEnabled(enabled: Boolean): AppSettings

    /** 持久化隐藏系统应用开关，并返回最新快照。 */
    fun setHideSystemAppsEnabled(enabled: Boolean): AppSettings

    /** 持久化闹钟优先开关，并返回最新快照。 */
    fun setAlarmFirstEnabled(enabled: Boolean): AppSettings
}

/**
 * 将 SystemUI 需要的极少量开关写入 YukiHook 跨进程偏好文件。
 *
 * 普通 SharedPreferences 保持应用内设置真值；独立镜像只供被注入的 SystemUI 读取，
 * 避免要求 SystemUI 直接访问模块私有数据目录。
 */
object SystemUiAppSettingsSync {
    fun persistBuiltinPanelEnabled(context: Context, enabled: Boolean) {
        val crossProcessPreferences = context.prefs(SYSTEM_UI_SETTINGS_PREFERENCES_NAME)
        crossProcessPreferences.edit {
            putBoolean(AppSettingsKeys.SYSTEM_UI_BUILTIN_VOLUME_PANEL, enabled)
        }
        AppLog.info(
            "Persisted SystemUI builtin panel setting enabled=$enabled " +
                    "available=${crossProcessPreferences.isPreferencesAvailable}",
        )
    }

    /** 将"隐藏系统应用"设置同步到跨进程偏好，供 SystemUI 内置面板读取。 */
    fun persistHideSystemAppsEnabled(context: Context, enabled: Boolean) {
        val crossProcessPreferences = context.prefs(SYSTEM_UI_SETTINGS_PREFERENCES_NAME)
        crossProcessPreferences.edit {
            putBoolean(AppSettingsKeys.HIDE_SYSTEM_APPS, enabled)
        }
        AppLog.info(
            "Persisted SystemUI hide-system-apps setting enabled=$enabled " +
                    "available=${crossProcessPreferences.isPreferencesAvailable}",
        )
    }

    /** 将"闹钟优先"设置同步到跨进程偏好，供被注入进程读取。 */
    fun persistAlarmFirstEnabled(context: Context, enabled: Boolean) {
        val crossProcessPreferences = context.prefs(SYSTEM_UI_SETTINGS_PREFERENCES_NAME)
        crossProcessPreferences.edit {
            putBoolean(AppSettingsKeys.ALARM_FIRST, enabled)
        }
        val readBack = crossProcessPreferences.getBoolean(AppSettingsKeys.ALARM_FIRST, !enabled)
        AppLog.info(
            "Persisted alarm-first setting enabled=$enabled " +
                    "available=${crossProcessPreferences.isPreferencesAvailable} " +
                    "readBack=$readBack",
        )
    }

    /**
     * 将"重新启用崩溃看门狗"的时间戳同步到跨进程偏好。
     *
     * SystemUI 在禁用态启动时读取该时间戳，晚于禁用触发时刻即恢复完整功能；
     * 写入的是当前时间而非布尔值，天然免疫乱序/回退。
     */
    fun persistCrashGuardReenable(context: Context) {
        val reenableAtMs = System.currentTimeMillis()
        val crossProcessPreferences = context.prefs(SYSTEM_UI_SETTINGS_PREFERENCES_NAME)
        crossProcessPreferences.edit {
            putLong(CrashGuardContract.MIRROR_REENABLE_AT, reenableAtMs)
        }
        CrashGuardStore.recordReenable(context, reenableAtMs)
        AppLog.info(
            "Persisted crash guard reenable at=$reenableAtMs " +
                    "available=${crossProcessPreferences.isPreferencesAvailable}",
        )
    }
}

/** 使用应用独立 SharedPreferences 文件保存设置。 */
class SharedPreferencesAppSettingsStore(
    private val preferences: SharedPreferences,
    private val systemUiBuiltinPanelMirror: ((Boolean) -> Unit)? = null,
    private val hideSystemAppsMirror: ((Boolean) -> Unit)? = null,
    private val alarmFirstMirror: ((Boolean) -> Unit)? = null,
) : AppSettingsStore {
    override fun read(): AppSettings = logged("read app settings") {
        AppSettings(
            smoothCornersEnabled = preferences.getBoolean(
                AppSettingsKeys.SMOOTH_CORNERS,
                AppSettingsDefaults.SMOOTH_CORNERS_ENABLED,
            ),
            volumePercentEnabled = preferences.getBoolean(
                AppSettingsKeys.VOLUME_PERCENT,
                AppSettingsDefaults.VOLUME_PERCENT_ENABLED,
            ),
            systemUiBuiltinVolumePanelEnabled = preferences.getBoolean(
                AppSettingsKeys.SYSTEM_UI_BUILTIN_VOLUME_PANEL,
                AppSettingsDefaults.SYSTEM_UI_BUILTIN_VOLUME_PANEL_ENABLED,
            ),
            hideSystemAppsEnabled = preferences.getBoolean(
                AppSettingsKeys.HIDE_SYSTEM_APPS,
                AppSettingsDefaults.HIDE_SYSTEM_APPS_ENABLED,
            ),
            alarmFirstEnabled = preferences.getBoolean(
                AppSettingsKeys.ALARM_FIRST,
                AppSettingsDefaults.ALARM_FIRST_ENABLED,
            ),
        )
    }

    override fun setSmoothCornersEnabled(enabled: Boolean): AppSettings =
        write(AppSettingsKeys.SMOOTH_CORNERS, enabled)

    override fun setVolumePercentEnabled(enabled: Boolean): AppSettings =
        write(AppSettingsKeys.VOLUME_PERCENT, enabled)

    override fun setSystemUiBuiltinVolumePanelEnabled(enabled: Boolean): AppSettings {
        val previous = read().systemUiBuiltinVolumePanelEnabled
        val updated = write(AppSettingsKeys.SYSTEM_UI_BUILTIN_VOLUME_PANEL, enabled)
        try {
            systemUiBuiltinPanelMirror?.invoke(enabled)
        } catch (error: RuntimeException) {
            try {
                write(AppSettingsKeys.SYSTEM_UI_BUILTIN_VOLUME_PANEL, previous)
            } catch (rollbackError: RuntimeException) {
                error.addSuppressed(rollbackError)
            }
            AppLog.error("Unable to mirror SystemUI builtin panel setting", error)
            throw error
        }
        return updated
    }

    override fun setHideSystemAppsEnabled(enabled: Boolean): AppSettings {
        val previous = read().hideSystemAppsEnabled
        val updated = write(AppSettingsKeys.HIDE_SYSTEM_APPS, enabled)
        try {
            hideSystemAppsMirror?.invoke(enabled)
        } catch (error: RuntimeException) {
            try {
                write(AppSettingsKeys.HIDE_SYSTEM_APPS, previous)
            } catch (rollbackError: RuntimeException) {
                error.addSuppressed(rollbackError)
            }
            AppLog.error("Unable to mirror hide-system-apps setting", error)
            throw error
        }
        return updated
    }

    override fun setAlarmFirstEnabled(enabled: Boolean): AppSettings {
        val previous = read().alarmFirstEnabled
        val updated = write(AppSettingsKeys.ALARM_FIRST, enabled)
        try {
            alarmFirstMirror?.invoke(enabled)
        } catch (error: RuntimeException) {
            try {
                write(AppSettingsKeys.ALARM_FIRST, previous)
            } catch (rollbackError: RuntimeException) {
                error.addSuppressed(rollbackError)
            }
            AppLog.error("Unable to mirror alarm-first setting", error)
            throw error
        }
        return updated
    }

    private fun write(key: String, enabled: Boolean): AppSettings =
        logged("write app setting key=$key") {
            check(preferences.edit().putBoolean(key, enabled).commit()) {
                "SharedPreferences commit failed for key=$key"
            }
            read()
        }

    private inline fun <T> logged(operation: String, block: () -> T): T = try {
        block()
    } catch (error: RuntimeException) {
        AppLog.error("Unable to $operation", error)
        throw error
    }
}
