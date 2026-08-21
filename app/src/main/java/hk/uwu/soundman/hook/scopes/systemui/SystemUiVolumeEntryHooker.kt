package hk.uwu.soundman.hook.scopes.systemui

import android.util.Log
import android.view.View
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.extension.classOf
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import hk.uwu.soundman.data.AppSettingsDefaults
import hk.uwu.soundman.data.AppSettingsKeys
import hk.uwu.soundman.data.CrashGuardContract
import hk.uwu.soundman.data.SYSTEM_UI_SETTINGS_PREFERENCES_NAME
import hk.uwu.soundman.hook.core.YLog
import hk.uwu.soundman.hook.scopes.systemui.hidden.SystemUiCrashGuard
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
    )
    private val pluginClassLoaderReader = SystemUiPluginClassLoader()
    private val pluginClassLoaderAttach = SystemUiPluginClassLoaderAttach()
    private val crashGuard = SystemUiCrashGuard(
        reenableAtMs = ::readCrashGuardReenableAtMs,
        log = ::writeLog,
    )

    override fun onHook() {
        // 崩溃看门狗是唯一的前置闸门：上一轮崩溃触发禁用后，本进程一个 Hook 都不装，
        // 让 SystemUI 以纯净状态起稳，等待用户在模块 App 里手动重新启用。
        if (!crashGuardAdmitted()) return
        PLUGIN_WATCH_TARGETS.forEach(::watchPluginTarget)
    }

    /**
     * 看门狗放行判定；看门狗自身不可用（非主进程/内部故障）时一律放行（fail-open）。
     */
    private fun crashGuardAdmitted(): Boolean {
        val decision = runCatching { crashGuard.admit() }
            .onFailure { YLog.error("Crash guard admit failed", it) }
            .getOrNull() ?: return true
        return decision.admitted
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

    /**
     * 读取用户在模块 App 里最近一次“重新启用”看门狗的时间戳。
     *
     * 只在已处于禁用态的启动路径上被调用；读取失败按“从未请求”处理，
     * 方向安全——宁可多禁用一轮，也不误放行。
     */
    private fun readCrashGuardReenableAtMs(): Long = try {
        val value = prefs(SYSTEM_UI_SETTINGS_PREFERENCES_NAME)
            .all()[CrashGuardContract.MIRROR_REENABLE_AT]
        when (value) {
            null -> 0L
            is Long -> value
            is Int -> value.toLong()
            is String -> value.toLongOrNull() ?: 0L
            else -> {
                YLog.warn(
                    "Invalid ${CrashGuardContract.MIRROR_REENABLE_AT} type=${value.javaClass.name}",
                )
                0L
            }
        }
    } catch (error: Throwable) {
        YLog.error("Unable to read crash guard reenable timestamp through Yuki prefs", error)
        0L
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
