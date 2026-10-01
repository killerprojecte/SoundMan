package hk.uwu.soundman.data

import android.content.Context
import android.content.SharedPreferences
import com.highcapable.yukihookapi.hook.factory.prefs
import hk.uwu.soundman.log.AppLog
import hk.uwu.soundman.model.EntryMaterial
import hk.uwu.soundman.model.EntryPosition
import hk.uwu.soundman.model.PanelMaterial

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

    /**
     * 入口圆钮相对音量条的落位。
     *
     * 只在侧栏入口生效（与 [systemUiBuiltinVolumePanelEnabled] 无关）；
     * 入口插入时每次重新读，切换后下一次面板回调就会把按钮挪过去。
     */
    val entryPosition: EntryPosition = AppSettingsDefaults.ENTRY_POSITION,

    /**
     * 入口圆钮是否只在有媒体应用正在播放时出现。
     *
     * 开启时与「音质音效」那颗官方入口同一前提：没有播放就藏起来，避免音量条旁边
     * 长期挂着一颗点开也没内容的按钮。关掉后入口回到旧行为，无论有没有播放都显示。
     */
    val entryPlaybackOnlyEnabled: Boolean = AppSettingsDefaults.ENTRY_PLAYBACK_ONLY_ENABLED,

    /**
     * 入口圆钮的材质来源：跟随 HyperLight / 自研玻璃 / 官方高光材质。
     *
     * 默认跟随 HyperLight——拿不到它（未安装、未激活、或它关了液态玻璃）时
     * 会在插入时自动退回自研玻璃，不会让入口变成裸按钮。
     */
    val entryMaterial: EntryMaterial = AppSettingsDefaults.ENTRY_MATERIAL,

    /**
     * 内置面板是否跟随 HyperLight 的液态玻璃（系统音量条展开面板同款）。
     *
     * HyperLight 自己 hook `MiuiVolumeDialogMotion.updateExpandBgState` 给展开面板挂玻璃；
     * 开启后 SoundMan 的面板走它同一个入口，观感与系统展开面板一致，参数也跟着用户在
     * HyperLight 里的调整变化。关掉后面板只保留官方展开材质 + SoundMan 自研玻璃。
     */
    val hyperLightPanelGlassEnabled: Boolean = AppSettingsDefaults.HYPER_LIGHT_PANEL_GLASS_ENABLED,
) {
    /**
     * 面板材质的三档视图：由 [hyperLightPanelGlassEnabled] 与 [liquidGlassEnabled] 推导。
     *
     * 设置页只有「面板材质」一个下拉框，但底层仍然是两个独立开关——分开存是为了
     * 让老版本写入的值继续生效，也避免一次改动动到跨进程镜像的两份数据格式。
     *
     * HyperLight 优先：面板挂上玻璃后自研玻璃不会再叠加，所以同时为真时按跟随处理。
     */
    val panelMaterial: PanelMaterial
        get() = when {
            hyperLightPanelGlassEnabled -> PanelMaterial.HYPERLIGHT
            liquidGlassEnabled -> PanelMaterial.LIQUID
            else -> PanelMaterial.OFFICIAL
        }
}

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

    /** 入口圆钮默认落在音量条上方，保持模块原本的行为。 */
    val ENTRY_POSITION: EntryPosition = EntryPosition.ABOVE

    /**
     * 「仅在播放时显示入口」默认开启。
     *
     * 这与音质音效官方入口的行为一致，也是本次新增该判定后实际发布出去的行为；
     * 想回到常驻显示的用户需要显式关掉。
     */
    const val ENTRY_PLAYBACK_ONLY_ENABLED = true

    /** 入口材质默认跟随 HyperLight，拿不到时自动退回自研玻璃。 */
    val ENTRY_MATERIAL: EntryMaterial = EntryMaterial.DEFAULT

    /** 面板玻璃默认跟随 HyperLight，与系统展开面板保持一致。 */
    const val HYPER_LIGHT_PANEL_GLASS_ENABLED = true
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

    /**
     * 入口圆钮落位的键名。
     *
     * 值取自 [EntryPosition.storedValue]，故意不用布尔：以后若再加档位，
     * 老用户的「上方」不会被新的布尔语义带偏。
     */
    const val ENTRY_POSITION = "entry_position"

    /** 入口圆钮「仅在播放时显示」开关的键名。 */
    const val ENTRY_PLAYBACK_ONLY = "entry_playback_only_enabled"

    /** 入口圆钮材质来源的键名，值取自 [EntryMaterial.storedValue]。 */
    const val ENTRY_MATERIAL = "entry_material"

    /** 面板是否跟随 HyperLight 玻璃的键名。 */
    const val HYPER_LIGHT_PANEL_GLASS = "hyperlight_panel_glass_enabled"

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
        ENTRY_POSITION,
        ENTRY_PLAYBACK_ONLY,
        ENTRY_MATERIAL,
        HYPER_LIGHT_PANEL_GLASS,
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

    /** 持久化入口圆钮相对音量条的落位，并返回最新快照。 */
    fun setEntryPosition(position: EntryPosition): AppSettings

    /** 持久化入口圆钮的材质来源，并返回最新快照。 */
    fun setEntryMaterial(material: EntryMaterial): AppSettings

    /** 持久化「入口只在播放时出现」开关，并返回最新快照。 */
    fun setEntryPlaybackOnlyEnabled(enabled: Boolean): AppSettings

    /** 持久化「面板跟随 HyperLight 玻璃」开关，并返回最新快照。 */
    fun setHyperLightPanelGlassEnabled(enabled: Boolean): AppSettings

    /**
     * 一次切换面板材质，并返回最新快照。
     *
     * 三档落到上面两个开关上（跟随 HyperLight / 自研玻璃），两边都写成功才算切换完成；
     * 镜像失败时对应 setter 自己会回滚并抛出，调用方拿到的仍是最新的真实快照。
     */
    fun setPanelMaterial(material: PanelMaterial): AppSettings
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

    /** 将"入口圆钮位置"同步到跨进程偏好，供 SystemUI 入口下次插入时读取。 */
    fun persistEntryPosition(context: Context, position: EntryPosition) {
        val crossProcessPreferences = context.prefs(SYSTEM_UI_SETTINGS_PREFERENCES_NAME)
        crossProcessPreferences.edit {
            putString(AppSettingsKeys.ENTRY_POSITION, position.storedValue)
        }
        AppLog.info(
            "Persisted entry position setting position=$position " +
                    "available=${crossProcessPreferences.isPreferencesAvailable}",
        )
    }

    /** 将"入口材质来源"同步到跨进程偏好，供 SystemUI 入口下次插入时读取。 */
    fun persistEntryMaterial(context: Context, material: EntryMaterial) {
        val crossProcessPreferences = context.prefs(SYSTEM_UI_SETTINGS_PREFERENCES_NAME)
        crossProcessPreferences.edit {
            putString(AppSettingsKeys.ENTRY_MATERIAL, material.storedValue)
        }
        AppLog.info(
            "Persisted entry material setting material=$material " +
                    "available=${crossProcessPreferences.isPreferencesAvailable}",
        )
    }

    /** 将"入口只在播放时出现"开关同步到跨进程偏好，供 SystemUI 入口读取。 */
    fun persistEntryPlaybackOnlyEnabled(context: Context, enabled: Boolean) {
        val crossProcessPreferences = context.prefs(SYSTEM_UI_SETTINGS_PREFERENCES_NAME)
        crossProcessPreferences.edit {
            putBoolean(AppSettingsKeys.ENTRY_PLAYBACK_ONLY, enabled)
        }
        AppLog.info(
            "Persisted entry playback-only setting enabled=$enabled " +
                    "available=${crossProcessPreferences.isPreferencesAvailable}",
        )
    }

    /** 将"面板跟随 HyperLight 玻璃"开关同步到跨进程偏好，供 SystemUI 面板读取。 */
    fun persistHyperLightPanelGlassEnabled(context: Context, enabled: Boolean) {
        val crossProcessPreferences = context.prefs(SYSTEM_UI_SETTINGS_PREFERENCES_NAME)
        crossProcessPreferences.edit {
            putBoolean(AppSettingsKeys.HYPER_LIGHT_PANEL_GLASS, enabled)
        }
        AppLog.info(
            "Persisted HyperLight panel glass setting enabled=$enabled " +
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
    private val entryPositionMirror: ((EntryPosition) -> Unit)? = null,
    private val entryPlaybackOnlyMirror: ((Boolean) -> Unit)? = null,
    private val entryMaterialMirror: ((EntryMaterial) -> Unit)? = null,
    private val hyperLightPanelGlassMirror: ((Boolean) -> Unit)? = null,
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
            entryPosition = EntryPosition.fromStored(
                preferences.getString(AppSettingsKeys.ENTRY_POSITION, null)
            ),
            entryPlaybackOnlyEnabled = preferences.getBoolean(
                AppSettingsKeys.ENTRY_PLAYBACK_ONLY,
                AppSettingsDefaults.ENTRY_PLAYBACK_ONLY_ENABLED,
            ),
            entryMaterial = EntryMaterial.fromStored(
                preferences.getString(AppSettingsKeys.ENTRY_MATERIAL, null)
            ),
            hyperLightPanelGlassEnabled = preferences.getBoolean(
                AppSettingsKeys.HYPER_LIGHT_PANEL_GLASS,
                AppSettingsDefaults.HYPER_LIGHT_PANEL_GLASS_ENABLED,
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

    override fun setEntryPosition(position: EntryPosition): AppSettings {
        val previous = read().entryPosition
        val updated = writeString(AppSettingsKeys.ENTRY_POSITION, position.storedValue)
        try {
            entryPositionMirror?.invoke(position)
        } catch (error: RuntimeException) {
            try {
                writeString(AppSettingsKeys.ENTRY_POSITION, previous.storedValue)
            } catch (rollbackError: RuntimeException) {
                error.addSuppressed(rollbackError)
            }
            AppLog.error("Unable to mirror entry position setting", error)
            throw error
        }
        return updated
    }

    override fun setEntryMaterial(material: EntryMaterial): AppSettings {
        val previous = read().entryMaterial
        val updated = writeString(AppSettingsKeys.ENTRY_MATERIAL, material.storedValue)
        try {
            entryMaterialMirror?.invoke(material)
        } catch (error: RuntimeException) {
            try {
                writeString(AppSettingsKeys.ENTRY_MATERIAL, previous.storedValue)
            } catch (rollbackError: RuntimeException) {
                error.addSuppressed(rollbackError)
            }
            AppLog.error("Unable to mirror entry material setting", error)
            throw error
        }
        return updated
    }

    override fun setEntryPlaybackOnlyEnabled(enabled: Boolean): AppSettings {
        val previous = read().entryPlaybackOnlyEnabled
        val updated = write(AppSettingsKeys.ENTRY_PLAYBACK_ONLY, enabled)
        try {
            entryPlaybackOnlyMirror?.invoke(enabled)
        } catch (error: RuntimeException) {
            try {
                write(AppSettingsKeys.ENTRY_PLAYBACK_ONLY, previous)
            } catch (rollbackError: RuntimeException) {
                error.addSuppressed(rollbackError)
            }
            AppLog.error("Unable to mirror entry playback-only setting", error)
            throw error
        }
        return updated
    }

    override fun setHyperLightPanelGlassEnabled(enabled: Boolean): AppSettings {
        val previous = read().hyperLightPanelGlassEnabled
        val updated = write(AppSettingsKeys.HYPER_LIGHT_PANEL_GLASS, enabled)
        try {
            hyperLightPanelGlassMirror?.invoke(enabled)
        } catch (error: RuntimeException) {
            try {
                write(AppSettingsKeys.HYPER_LIGHT_PANEL_GLASS, previous)
            } catch (rollbackError: RuntimeException) {
                error.addSuppressed(rollbackError)
            }
            AppLog.error("Unable to mirror HyperLight panel glass setting", error)
            throw error
        }
        return updated
    }

    override fun setPanelMaterial(material: PanelMaterial): AppSettings {
        setHyperLightPanelGlassEnabled(material == PanelMaterial.HYPERLIGHT)
        setLiquidGlassEnabled(material == PanelMaterial.LIQUID)
        return read()
    }

    private fun write(key: String, enabled: Boolean): AppSettings =
        logged("write app setting key=$key") {
            check(preferences.edit().putBoolean(key, enabled).commit()) {
                "SharedPreferences commit failed for key=$key"
            }
            read()
        }

    private fun writeString(key: String, value: String): AppSettings =
        logged("write app setting key=$key") {
            check(preferences.edit().putString(key, value).commit()) {
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
