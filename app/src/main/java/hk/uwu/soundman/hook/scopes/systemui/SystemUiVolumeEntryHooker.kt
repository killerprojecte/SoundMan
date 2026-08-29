package hk.uwu.soundman.hook.scopes.systemui

import android.util.Log
import android.view.View
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.extension.classOf
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import hk.uwu.soundman.data.AppSettingsDefaults
import hk.uwu.soundman.data.AppSettingsKeys
import hk.uwu.soundman.data.SYSTEM_UI_SETTINGS_PREFERENCES_NAME
import hk.uwu.soundman.hook.core.YLog
import hk.uwu.soundman.hook.scopes.systemui.hidden.SystemUiPluginClassLoader
import hk.uwu.soundman.hook.scopes.systemui.hidden.SystemUiPluginClassLoaderAttach
import hk.uwu.soundman.hook.scopes.systemui.hidden.SystemUiPluginHookTargets
import hk.uwu.soundman.hook.scopes.systemui.runtime.SystemUiVolumeEntryRuntime
import java.lang.invoke.MethodHandles

/**
 * 在 HyperOS 音量侧栏的静音/免打扰按钮下方插入 SoundMan 圆形入口。
 *
 * 本 hooker 只跑在 SystemUI 进程：先在 SystemUI ClassLoader 上监视
 * `PluginInstance.loadPlugin` / `getPlugin`，取出 `miui.systemui.plugin`
 * 的插件 ClassLoader 后再 hook `MiuiRingerModeLayout`。
 * 缺 PluginInstance、提取失败或缺音量类只打日志，不得让异常打穿 SystemUI。
 */
object SystemUiVolumeEntryHooker : YukiBaseHooker() {
    private val runtime = SystemUiVolumeEntryRuntime(
        log = ::writeLog,
        builtinPanelEnabled = ::isBuiltinPanelEnabled,
        hideSystemAppsEnabled = ::isHideSystemAppsEnabled,
        volumePercentEnabled = ::isVolumePercentEnabled,
        liquidGlassEnabled = ::isLiquidGlassEnabled,
        liquidGlassRefractionEnabled = ::isLiquidGlassRefractionEnabled,
        liquidGlassBlurRadius = ::liquidGlassBlurRadius,
        liquidGlassBlendColor = ::liquidGlassBlendColor,
    )
    private val pluginClassLoaderReader = SystemUiPluginClassLoader()
    private val pluginClassLoaderAttach = SystemUiPluginClassLoaderAttach()

    override fun onHook() {
        PLUGIN_WATCH_TARGETS.forEach(::watchPluginTarget)
    }

    private fun watchPluginTarget(target: SystemUiVolumeEntryHookTarget) {
        val clazz = runCatching { target.className.toClass() }
            .onFailure { YLog.warn("Plugin watch class missing: ${target.className}", it) }
            .getOrNull()
            ?: return
        val resolved = clazz.resolve().optional()
        target.methodNames.forEach { methodName ->
            var resolutionFailed = false
            val methods = safeResolve(
                block = { resolved.method { name = methodName } },
                onFailure = { error ->
                    resolutionFailed = true
                    YLog.warn(
                        "Plugin watch method missing: class=${target.className} method=$methodName",
                        error,
                    )
                },
            )
            if (methods.isEmpty()) {
                if (!resolutionFailed) {
                    YLog.warn(
                        "Plugin watch method missing: class=${target.className} method=$methodName",
                    )
                }
                return@forEach
            }
            methods.forEach { method ->
                method.hook {
                    after {
                        try {
                            if (throwable != null) return@after
                            val pluginInstance = instanceOrNull
                            if (pluginInstance == null) {
                                YLog.error("Plugin watch has no instance: ${target.className}#$methodName")
                                return@after
                            }
                            attachPluginClassLoader(pluginInstance)
                        } catch (error: Throwable) {
                            YLog.error(
                                "Plugin watch callback failed: class=${target.className} method=$methodName",
                                error,
                            )
                        }
                    }
                }
            }
            methods.forEach { method ->
                YLog.info(
                    "Installed plugin watch: class=${clazz.name} method=${method.self.toGenericString()}",
                )
            }
        }
    }

    private fun attachPluginClassLoader(pluginInstance: Any) {
        try {
            pluginClassLoaderAttach.attach(pluginInstance, pluginClassLoaderReader) { pluginClassLoader ->
                YLog.info(
                    "Attached ${SystemUiPluginHookTargets.MIUI_PLUGIN_PACKAGE} ClassLoader: " +
                        pluginClassLoader.javaClass.name,
                )
                runtime.attachPluginClassLoader(pluginClassLoader)
                installVolumeHooks(pluginClassLoader)
            }
        } catch (error: Throwable) {
            YLog.error(
                "Unable to attach ${SystemUiPluginHookTargets.MIUI_PLUGIN_PACKAGE} ClassLoader " +
                    "from ${pluginInstance.javaClass.name}",
                error,
            )
        }
    }

    private fun installVolumeHooks(pluginClassLoader: ClassLoader) {
        HOOK_TARGETS.forEach { target -> hookTarget(target, pluginClassLoader) }
        installOfficialControllerCaptureHook(pluginClassLoader)
    }

    private fun installOfficialControllerCaptureHook(pluginClassLoader: ClassLoader) {
        val clazz = runCatching { CLASS_VOLUME_PANEL_VIEW_CONTROLLER.toClass(pluginClassLoader) }
            .onFailure {
                YLog.warn(
                    "Official controller capture class missing: $CLASS_VOLUME_PANEL_VIEW_CONTROLLER",
                    it
                )
            }
            .getOrNull() ?: return
        val intType = classOf<Int>()
        val dismissMethod = clazz.methods.firstOrNull { method ->
            method.name == METHOD_DISMISS_H && method.parameterTypes.contentEquals(arrayOf(intType))
        } ?: run {
            YLog.error("Official controller dismissH(int) missing: $CLASS_VOLUME_PANEL_VIEW_CONTROLLER")
            return
        }
        val dismissHandle = runCatching { MethodHandles.publicLookup().unreflect(dismissMethod) }
            .onFailure {
                YLog.error(
                    "Unable to bind official controller dismissH MethodHandle",
                    it
                )
            }
            .getOrNull() ?: return
        val resolved = clazz.resolve().optional()
        val showMethods = safeResolve(
            block = { resolved.method { name = METHOD_SHOW_H } },
            onFailure = { error -> YLog.error("Official controller showH hook missing", error) },
        )
        showMethods.forEach { method ->
            method.hook {
                after {
                    val controller = instanceOrNull ?: run {
                        YLog.error("Official controller capture has no instance")
                        return@after
                    }
                    runtime.captureOfficialDismissController(controller) { owner ->
                        dismissHandle.invokeWithArguments(owner, OFFICIAL_DISMISS_REASON)
                    }
                }
            }
            YLog.info("Installed official controller capture hook: ${method.self.toGenericString()}")
        }
        val dismissMethods = safeResolve(
            block = { resolved.method { name = METHOD_DISMISS_H } },
            onFailure = { error ->
                YLog.error(
                    "Official controller dismissH lifecycle hook missing",
                    error
                )
            },
        )
        dismissMethods.forEach { method ->
            method.hook {
                after {
                    try {
                        if (throwable != null) return@after
                        val reason = args.getOrNull(0) as? Int
                        if (reason == null) {
                            YLog.error(
                                "Official controller dismissH lifecycle has no Int reason: " +
                                        "signature=${method.self.toGenericString()} arg0=${
                                            args.getOrNull(
                                                0
                                            )?.javaClass?.name
                                        }",
                            )
                            return@after
                        }
                        runtime.onOfficialVolumeDismiss(reason)
                    } catch (error: Throwable) {
                        YLog.error("Official controller dismissH lifecycle callback failed", error)
                    }
                }
            }
            YLog.info("Installed official controller dismissH lifecycle hook: ${method.self.toGenericString()}")
        }
    }

    private fun hookTarget(target: SystemUiVolumeEntryHookTarget, pluginClassLoader: ClassLoader) {
        val clazz = runCatching { target.className.toClass(pluginClassLoader) }
            .onFailure { YLog.warn("Volume hook class missing: ${target.className}", it) }
            .getOrNull()
            ?: return
        val resolved = clazz.resolve().optional()
        target.methodNames.forEach { methodName ->
            var resolutionFailed = false
            val methods = safeResolve(
                block = { resolved.method { name = methodName } },
                onFailure = { error ->
                    resolutionFailed = true
                    YLog.warn(
                        "Volume hook method missing: class=${target.className} method=$methodName",
                        error,
                    )
                },
            )
            if (methods.isEmpty()) {
                if (!resolutionFailed) {
                    YLog.warn(
                        "Volume hook method missing: class=${target.className} method=$methodName",
                    )
                }
                return@forEach
            }
            methods.forEach { method ->
                method.hook {
                    after {
                        try {
                            if (throwable != null) return@after
                            if (methodName == METHOD_UPDATE_EXPANDED_H) {
                                val expanded = args.getOrNull(0) as? Boolean
                                if (expanded == null) {
                                    YLog.error(
                                        "updateExpandedH missing Boolean argument: " +
                                                "class=${target.className} arg0=${args.getOrNull(0)?.javaClass?.name}",
                                    )
                                    return@after
                                }
                                runtime.applyExpanded(instance as? View, expanded)
                                return@after
                            }
                            runtime.scheduleInsertion(instance, "${clazz.name}#$methodName")
                        } catch (error: Throwable) {
                            YLog.error(
                                "Volume hook callback failed: class=${target.className} method=$methodName",
                                error,
                            )
                        }
                    }
                }
            }
            methods.forEach { method ->
                YLog.info(
                    "Installed volume hook: class=${clazz.name} method=${method.self.toGenericString()}",
                )
            }
        }
    }

    private fun isBuiltinPanelEnabled(): Boolean = try {
        val modulePrefs = prefs(SYSTEM_UI_SETTINGS_PREFERENCES_NAME)
        val entries = modulePrefs.all()
        val value = entries[AppSettingsKeys.SYSTEM_UI_BUILTIN_VOLUME_PANEL]
        val enabled = when (value) {
            null -> AppSettingsDefaults.SYSTEM_UI_BUILTIN_VOLUME_PANEL_ENABLED
            is Boolean -> value
            else -> error(
                "Invalid ${AppSettingsKeys.SYSTEM_UI_BUILTIN_VOLUME_PANEL} type=${value.javaClass.name}",
            )
        }
        YLog.info(
            "SystemUI builtin panel preference enabled=$enabled " +
                    "available=${modulePrefs.isPreferencesAvailable} keys=${entries.keys.sorted()}",
        )
        enabled
    } catch (error: Throwable) {
        YLog.error("Unable to read SystemUI builtin panel setting through Yuki prefs", error)
        AppSettingsDefaults.SYSTEM_UI_BUILTIN_VOLUME_PANEL_ENABLED
    }

    private fun isHideSystemAppsEnabled(): Boolean = try {
        val modulePrefs = prefs(SYSTEM_UI_SETTINGS_PREFERENCES_NAME)
        val entries = modulePrefs.all()
        val value = entries[AppSettingsKeys.HIDE_SYSTEM_APPS]
        val enabled = when (value) {
            null -> AppSettingsDefaults.HIDE_SYSTEM_APPS_ENABLED
            is Boolean -> value
            else -> error(
                "Invalid ${AppSettingsKeys.HIDE_SYSTEM_APPS} type=${value.javaClass.name}",
            )
        }
        enabled
    } catch (error: Throwable) {
        YLog.error("Unable to read hide-system-apps setting through Yuki prefs", error)
        AppSettingsDefaults.HIDE_SYSTEM_APPS_ENABLED
    }

    private fun isVolumePercentEnabled(): Boolean = try {
        val modulePrefs = prefs(SYSTEM_UI_SETTINGS_PREFERENCES_NAME)
        val entries = modulePrefs.all()
        val value = entries[AppSettingsKeys.VOLUME_PERCENT]
        val enabled = when (value) {
            null -> AppSettingsDefaults.VOLUME_PERCENT_ENABLED
            is Boolean -> value
            else -> error(
                "Invalid ${AppSettingsKeys.VOLUME_PERCENT} type=${value.javaClass.name}",
            )
        }
        YLog.info(
            "Volume-percent preference enabled=$enabled " +
                    "available=${modulePrefs.isPreferencesAvailable} keys=${entries.keys.sorted()}",
        )
        enabled
    } catch (error: Throwable) {
        YLog.error("Unable to read volume-percent setting through Yuki prefs", error)
        AppSettingsDefaults.VOLUME_PERCENT_ENABLED
    }

    private fun isLiquidGlassEnabled(): Boolean = try {
        val modulePrefs = prefs(SYSTEM_UI_SETTINGS_PREFERENCES_NAME)
        val value = modulePrefs.all()[AppSettingsKeys.LIQUID_GLASS]
        when (value) {
            null -> AppSettingsDefaults.LIQUID_GLASS_ENABLED
            is Boolean -> value
            else -> error(
                "Invalid ${AppSettingsKeys.LIQUID_GLASS} type=${value.javaClass.name}",
            )
        }
    } catch (error: Throwable) {
        YLog.error("Unable to read liquid glass setting through Yuki prefs", error)
        AppSettingsDefaults.LIQUID_GLASS_ENABLED
    }

    private fun isLiquidGlassRefractionEnabled(): Boolean = try {
        val modulePrefs = prefs(SYSTEM_UI_SETTINGS_PREFERENCES_NAME)
        val value = modulePrefs.all()[AppSettingsKeys.LIQUID_GLASS_REFRACTION]
        when (value) {
            null -> AppSettingsDefaults.LIQUID_GLASS_REFRACTION_ENABLED
            is Boolean -> value
            else -> error(
                "Invalid ${AppSettingsKeys.LIQUID_GLASS_REFRACTION} type=${value.javaClass.name}",
            )
        }
    } catch (error: Throwable) {
        YLog.error("Unable to read liquid glass refraction setting through Yuki prefs", error)
        AppSettingsDefaults.LIQUID_GLASS_REFRACTION_ENABLED
    }

    /** 模糊半径跨进程读取：容忍 Int/Long/String 漂移，非法值按默认处理，方向安全。 */
    private fun liquidGlassBlurRadius(): Int = try {
        val modulePrefs = prefs(SYSTEM_UI_SETTINGS_PREFERENCES_NAME)
        val value = modulePrefs.all()[AppSettingsKeys.LIQUID_GLASS_BLUR_RADIUS]
        val radius = when (value) {
            null -> AppSettingsDefaults.LIQUID_GLASS_BLUR_RADIUS
            is Int -> value
            is Long -> value.toInt()
            is String -> value.toIntOrNull() ?: AppSettingsDefaults.LIQUID_GLASS_BLUR_RADIUS
            else -> {
                YLog.warn(
                    "Invalid ${AppSettingsKeys.LIQUID_GLASS_BLUR_RADIUS} type=${value.javaClass.name}",
                )
                AppSettingsDefaults.LIQUID_GLASS_BLUR_RADIUS
            }
        }
        radius.coerceIn(
            AppSettingsDefaults.LIQUID_GLASS_BLUR_RADIUS_MIN,
            AppSettingsDefaults.LIQUID_GLASS_BLUR_RADIUS_MAX,
        )
    } catch (error: Throwable) {
        YLog.error("Unable to read liquid glass blur radius through Yuki prefs", error)
        AppSettingsDefaults.LIQUID_GLASS_BLUR_RADIUS
    }

    /** 混色颜色跨进程读取：容忍 Int/Long/String 漂移，非法值按默认处理。 */
    private fun liquidGlassBlendColor(): Int = try {
        val modulePrefs = prefs(SYSTEM_UI_SETTINGS_PREFERENCES_NAME)
        val value = modulePrefs.all()[AppSettingsKeys.LIQUID_GLASS_BLEND_COLOR]
        when (value) {
            null -> AppSettingsDefaults.LIQUID_GLASS_BLEND_COLOR
            is Int -> value
            is Long -> value.toInt()
            is String -> value.toIntOrNull() ?: AppSettingsDefaults.LIQUID_GLASS_BLEND_COLOR
            else -> {
                YLog.warn(
                    "Invalid ${AppSettingsKeys.LIQUID_GLASS_BLEND_COLOR} type=${value.javaClass.name}",
                )
                AppSettingsDefaults.LIQUID_GLASS_BLEND_COLOR
            }
        }
    } catch (error: Throwable) {
        YLog.error("Unable to read liquid glass blend color through Yuki prefs", error)
        AppSettingsDefaults.LIQUID_GLASS_BLEND_COLOR
    }

    private fun writeLog(priority: Int, tag: String, message: String, throwable: Throwable?) {
        val text = "[$tag] $message"
        when (priority) {
            Log.DEBUG -> YLog.debug(text, throwable)
            Log.INFO -> YLog.info(text, throwable)
            Log.WARN -> YLog.warn(text, throwable)
            else -> YLog.error(text, throwable)
        }
    }

    /**
     * 当前模块实际安装的音量 Hook 目标。
     *
     * 只描述类名和方法名，必须用插件 ClassLoader 解析，方便单测断言挂载范围。
     */
    val HOOK_TARGETS: List<SystemUiVolumeEntryHookTarget> = listOf(
        SystemUiVolumeEntryHookTarget(
            className = CLASS_RINGER_MODE_LAYOUT,
            methodNames = listOf(
                METHOD_FINISH_INFLATE,
                METHOD_ATTACHED_TO_WINDOW,
                METHOD_UPDATE_EXPANDED_H,
            ),
        ),
    )

    /**
     * 在 SystemUI ClassLoader 上监视的插件入口。
     *
     * `loadPlugin` 覆盖首次加载；`getPlugin` 覆盖插件已经 loaded 的路径。
     */
    val PLUGIN_WATCH_TARGETS: List<SystemUiVolumeEntryHookTarget> = listOf(
        SystemUiVolumeEntryHookTarget(
            className = SystemUiPluginHookTargets.PLUGIN_INSTANCE_CLASS,
            methodNames = listOf(
                SystemUiPluginHookTargets.LOAD_PLUGIN,
                SystemUiPluginHookTargets.GET_PLUGIN,
            ),
        ),
    )

    private const val CLASS_RINGER_MODE_LAYOUT =
        "com.android.systemui.miui.volume.MiuiRingerModeLayout"
    private const val CLASS_VOLUME_PANEL_VIEW_CONTROLLER =
        "com.android.systemui.miui.volume.VolumePanelViewController"
    private const val METHOD_FINISH_INFLATE = "onFinishInflate"
    private const val METHOD_ATTACHED_TO_WINDOW = "onAttachedToWindow"
    private const val METHOD_UPDATE_EXPANDED_H = "updateExpandedH"
    private const val METHOD_SHOW_H = "showH"
    private const val METHOD_DISMISS_H = "dismissH"
    private const val OFFICIAL_DISMISS_REASON = 8
}

/**
 * 音量侧栏入口的单个 Hook 目标。
 *
 * 只描述类名和方法名，不负责解析或安装。方法按名字匹配全部重载，不限定参数列表。
 *
 * @param className 目标类全名
 * @param methodNames 要 hook 的方法名，按安装顺序排列
 */
data class SystemUiVolumeEntryHookTarget(
    val className: String,
    val methodNames: List<String>,
)

/**
 * 安全执行 KavaRef 成员解析。
 *
 * `method { }` 找不到成员时会抛 [NoSuchMethodException]；单个方法缺失不得让整个 onHook 失败。
 *
 * @param block 实际解析逻辑，成功时返回匹配到的成员列表
 * @param onFailure 解析抛错时回调，调用方负责打日志
 * @return 解析结果；失败时返回 emptyList
 */
fun <T> safeResolve(block: () -> List<T>, onFailure: (Throwable) -> Unit): List<T> {
    return try {
        block()
    } catch (failure: Throwable) {
        onFailure(failure)
        emptyList()
    }
}
