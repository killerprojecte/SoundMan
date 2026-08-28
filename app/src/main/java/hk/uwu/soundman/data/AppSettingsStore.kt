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
    val liquidGlassEnabled: Boolean = AppSettingsDefaults.LIQUID_GLASS_ENABLED,
    val liquidGlassRefractionEnabled: Boolean = AppSettingsDefaults.LIQUID_GLASS_REFRACTION_ENABLED,
    val liquidGlassBlurRadius: Int = AppSettingsDefaults.LIQUID_GLASS_BLUR_RADIUS,
    val liquidGlassBlendColor: Int = AppSettingsDefaults.LIQUID_GLASS_BLEND_COLOR,
)

/** 设置默认值，供存储实现与纯 JVM 测试共享。 */
object AppSettingsDefaults {
    const val SMOOTH_CORNERS_ENABLED = false
    const val VOLUME_PERCENT_ENABLED = false
    const val SYSTEM_UI_BUILTIN_VOLUME_PANEL_ENABLED = false
    const val HIDE_SYSTEM_APPS_ENABLED = false
    const val ALARM_FIRST_ENABLED = false
    const val LIQUID_GLASS_ENABLED = false
    const val LIQUID_GLASS_REFRACTION_ENABLED = false
    const val LIQUID_GLASS_BLUR_RADIUS = 20
    const val LIQUID_GLASS_BLUR_RADIUS_MIN = 0
    const val LIQUID_GLASS_BLUR_RADIUS_MAX = 20
    const val LIQUID_GLASS_BLEND_COLOR = 0x20FFFFFF
}

/** SharedPreferences 键名的唯一来源，避免读写两端发生漂移。 */
object AppSettingsKeys {
    const val SMOOTH_CORNERS = "smooth_corners_enabled"
    const val VOLUME_PERCENT = "volume_percent_enabled"
    const val SYSTEM_UI_BUILTIN_VOLUME_PANEL = "system_ui_builtin_volume_panel_enabled"
    const val HIDE_SYSTEM_APPS = "hide_system_apps_enabled"
    const val ALARM_FIRST = "alarm_first_enabled"
    const val LIQUID_GLASS = "liquid_glass_enabled"
    const val LIQUID_GLASS_REFRACTION = "liquid_glass_refraction_enabled"
    const val LIQUID_GLASS_BLUR_RADIUS = "liquid_glass_blur_radius"
    const val LIQUID_GLASS_BLEND_COLOR = "liquid_glass_blend_color"

    val all: Set<String> = setOf(
        SMOOTH_CORNERS,
        VOLUME_PERCENT,
        SYSTEM_UI_BUILTIN_VOLUME_PANEL,
        HIDE_SYSTEM_APPS,
        ALARM_FIRST,
        LIQUID_GLASS,
        LIQUID_GLASS_REFRACTION,
        LIQUID_GLASS_BLUR_RADIUS,
        LIQUID_GLASS_BLEND_COLOR,
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

    /** 持久化液态玻璃开关，并返回最新快照。 */
    fun setLiquidGlassEnabled(enabled: Boolean): AppSettings

    /** 持久化真实折射开关，并返回最新快照。 */
    fun setLiquidGlassRefractionEnabled(enabled: Boolean): AppSettings

    /** 持久化液态玻璃模糊半径（0–20），并返回最新快照。 */
    fun setLiquidGlassBlurRadius(radius: Int): AppSettings

    /** 持久化液态玻璃混色颜色（ARGB），并返回最新快照。 */
    fun setLiquidGlassBlendColor(color: Int): AppSettings
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

    /** 将"音量百分比"设置同步到跨进程偏好，供 SystemUI 内置面板读取。 */
    fun persistVolumePercentEnabled(context: Context, enabled: Boolean) {
        val crossProcessPreferences = context.prefs(SYSTEM_UI_SETTINGS_PREFERENCES_NAME)
        crossProcessPreferences.edit {
            putBoolean(AppSettingsKeys.VOLUME_PERCENT, enabled)
        }
        AppLog.info(
            "Persisted volume-percent setting enabled=$enabled " +
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

    /** 将"液态玻璃"开关同步到跨进程偏好，供 SystemUI 内置面板读取。 */
    fun persistLiquidGlassEnabled(context: Context, enabled: Boolean) {
        val crossProcessPreferences = context.prefs(SYSTEM_UI_SETTINGS_PREFERENCES_NAME)
        crossProcessPreferences.edit {
            putBoolean(AppSettingsKeys.LIQUID_GLASS, enabled)
        }
        AppLog.info(
            "Persisted liquid glass setting enabled=$enabled " +
                    "available=${crossProcessPreferences.isPreferencesAvailable}",
        )
    }

    /** 将"真实折射"开关同步到跨进程偏好，供 SystemUI 内置面板读取。 */
    fun persistLiquidGlassRefractionEnabled(context: Context, enabled: Boolean) {
        val crossProcessPreferences = context.prefs(SYSTEM_UI_SETTINGS_PREFERENCES_NAME)
        crossProcessPreferences.edit {
            putBoolean(AppSettingsKeys.LIQUID_GLASS_REFRACTION, enabled)
        }
        AppLog.info(
            "Persisted liquid glass refraction setting enabled=$enabled " +
                    "available=${crossProcessPreferences.isPreferencesAvailable}",
        )
    }

    /** 将液态玻璃模糊半径同步到跨进程偏好，供 SystemUI 内置面板读取。 */
    fun persistLiquidGlassBlurRadius(context: Context, radius: Int) {
        val crossProcessPreferences = context.prefs(SYSTEM_UI_SETTINGS_PREFERENCES_NAME)
        crossProcessPreferences.edit {
            putInt(AppSettingsKeys.LIQUID_GLASS_BLUR_RADIUS, radius)
        }
        AppLog.info(
            "Persisted liquid glass blur radius radius=$radius " +
                    "available=${crossProcessPreferences.isPreferencesAvailable}",
        )
    }

    /** 将液态玻璃混色颜色（ARGB）同步到跨进程偏好，供 SystemUI 内置面板读取。 */
    fun persistLiquidGlassBlendColor(context: Context, color: Int) {
        val crossProcessPreferences = context.prefs(SYSTEM_UI_SETTINGS_PREFERENCES_NAME)
        crossProcessPreferences.edit {
            putInt(AppSettingsKeys.LIQUID_GLASS_BLEND_COLOR, color)
        }
        AppLog.info(
            "Persisted liquid glass blend color color=0x${Integer.toHexString(color)} " +
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
    private val volumePercentMirror: ((Boolean) -> Unit)? = null,
    private val liquidGlassMirror: ((Boolean) -> Unit)? = null,
    private val liquidGlassRefractionMirror: ((Boolean) -> Unit)? = null,
    private val liquidGlassBlurRadiusMirror: ((Int) -> Unit)? = null,
    private val liquidGlassBlendColorMirror: ((Int) -> Unit)? = null,
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
            liquidGlassEnabled = preferences.getBoolean(
                AppSettingsKeys.LIQUID_GLASS,
                AppSettingsDefaults.LIQUID_GLASS_ENABLED,
            ),
            liquidGlassRefractionEnabled = preferences.getBoolean(
                AppSettingsKeys.LIQUID_GLASS_REFRACTION,
                AppSettingsDefaults.LIQUID_GLASS_REFRACTION_ENABLED,
            ),
            liquidGlassBlurRadius = preferences.getInt(
                AppSettingsKeys.LIQUID_GLASS_BLUR_RADIUS,
                AppSettingsDefaults.LIQUID_GLASS_BLUR_RADIUS,
            ),
            liquidGlassBlendColor = preferences.getInt(
                AppSettingsKeys.LIQUID_GLASS_BLEND_COLOR,
                AppSettingsDefaults.LIQUID_GLASS_BLEND_COLOR,
            ),
        )
    }

    override fun setSmoothCornersEnabled(enabled: Boolean): AppSettings =
        write(AppSettingsKeys.SMOOTH_CORNERS, enabled)

    override fun setVolumePercentEnabled(enabled: Boolean): AppSettings {
        val previous = read().volumePercentEnabled
        val updated = write(AppSettingsKeys.VOLUME_PERCENT, enabled)
        try {
            volumePercentMirror?.invoke(enabled)
        } catch (error: RuntimeException) {
            try {
                write(AppSettingsKeys.VOLUME_PERCENT, previous)
            } catch (rollbackError: RuntimeException) {
                error.addSuppressed(rollbackError)
            }
            AppLog.error("Unable to mirror volume-percent setting", error)
            throw error
        }
        return updated
    }

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

    override fun setLiquidGlassEnabled(enabled: Boolean): AppSettings {
        val previous = read().liquidGlassEnabled
        val updated = write(AppSettingsKeys.LIQUID_GLASS, enabled)
        try {
            liquidGlassMirror?.invoke(enabled)
        } catch (error: RuntimeException) {
            try {
                write(AppSettingsKeys.LIQUID_GLASS, previous)
            } catch (rollbackError: RuntimeException) {
                error.addSuppressed(rollbackError)
            }
            AppLog.error("Unable to mirror liquid glass setting", error)
            throw error
        }
        return updated
    }

    override fun setLiquidGlassRefractionEnabled(enabled: Boolean): AppSettings {
        val previous = read().liquidGlassRefractionEnabled
        val updated = write(AppSettingsKeys.LIQUID_GLASS_REFRACTION, enabled)
        try {
            liquidGlassRefractionMirror?.invoke(enabled)
        } catch (error: RuntimeException) {
            try {
                write(AppSettingsKeys.LIQUID_GLASS_REFRACTION, previous)
            } catch (rollbackError: RuntimeException) {
                error.addSuppressed(rollbackError)
            }
            AppLog.error("Unable to mirror liquid glass refraction setting", error)
            throw error
        }
        return updated
    }

    override fun setLiquidGlassBlurRadius(radius: Int): AppSettings {
        val clamped = radius.coerceIn(
            AppSettingsDefaults.LIQUID_GLASS_BLUR_RADIUS_MIN,
            AppSettingsDefaults.LIQUID_GLASS_BLUR_RADIUS_MAX,
        )
        val previous = read().liquidGlassBlurRadius
        val updated = writeInt(AppSettingsKeys.LIQUID_GLASS_BLUR_RADIUS, clamped)
        try {
            liquidGlassBlurRadiusMirror?.invoke(clamped)
        } catch (error: RuntimeException) {
            try {
                writeInt(AppSettingsKeys.LIQUID_GLASS_BLUR_RADIUS, previous)
            } catch (rollbackError: RuntimeException) {
                error.addSuppressed(rollbackError)
            }
            AppLog.error("Unable to mirror liquid glass blur radius", error)
            throw error
        }
        return updated
    }

    override fun setLiquidGlassBlendColor(color: Int): AppSettings {
        val previous = read().liquidGlassBlendColor
        val updated = writeInt(AppSettingsKeys.LIQUID_GLASS_BLEND_COLOR, color)
        try {
            liquidGlassBlendColorMirror?.invoke(color)
        } catch (error: RuntimeException) {
            try {
                writeInt(AppSettingsKeys.LIQUID_GLASS_BLEND_COLOR, previous)
            } catch (rollbackError: RuntimeException) {
                error.addSuppressed(rollbackError)
            }
            AppLog.error("Unable to mirror liquid glass blend color", error)
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

    private fun writeInt(key: String, value: Int): AppSettings =
        logged("write app setting key=$key") {
            check(preferences.edit().putInt(key, value).commit()) {
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
