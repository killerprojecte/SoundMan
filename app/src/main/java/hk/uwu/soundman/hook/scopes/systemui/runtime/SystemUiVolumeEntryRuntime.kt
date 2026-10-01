package hk.uwu.soundman.hook.scopes.systemui.runtime

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.view.isVisible
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.extension.toClassOrNull
import hk.uwu.soundman.R
import hk.uwu.soundman.data.PanelBridgePrewarm
import hk.uwu.soundman.hook.scopes.systemui.hidden.ActiveMediaApp
import hk.uwu.soundman.hook.scopes.systemui.hidden.HyperLightGlassBridge
import hk.uwu.soundman.hook.scopes.systemui.hidden.OfficialComponentMaterial
import hk.uwu.soundman.hook.scopes.systemui.hidden.OfficialRingerBlur
import hk.uwu.soundman.hook.scopes.systemui.hidden.OfficialRingerClone
import hk.uwu.soundman.hook.scopes.systemui.hidden.SystemUiPlaybackMonitor
import hk.uwu.soundman.model.EntryMaterial
import hk.uwu.soundman.model.EntryPosition
import hk.uwu.soundman.model.MediaPresence
import hk.uwu.soundman.overlay.OverlayOpenRequest
import hk.uwu.soundman.overlay.SeededPlayback
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

/** 在 HyperOS 紧凑音量侧栏的音量条上方插入 SoundMan 圆钮入口。 */
class SystemUiVolumeEntryRuntime(
    private val log: (priority: Int, tag: String, message: String, throwable: Throwable?) -> Unit,
    private val builtinPanelEnabled: () -> Boolean = { false },
    private val hideSystemAppsEnabled: () -> Boolean = { false },
    private val volumePercentEnabled: () -> Boolean = { false },
    private val liquidGlassEnabled: () -> Boolean = { false },
    private val liquidGlassRefractionEnabled: () -> Boolean = { false },
    private val liquidGlassBlurRadius: () -> Int = { 20 },
    private val liquidGlassBlendColor: () -> Int = { 0x20FFFFFF },
    /**
     * 入口圆钮相对音量条的落位。
     *
     * 每次插入都会重新读：用户在 SoundMan 里改位置后，下一次音量面板回调
     * （`onAttachedToWindow` / `updateExpandedH` …）就会把既有入口迁到新的落位。
     */
    private val entryPosition: () -> EntryPosition = { EntryPosition.DEFAULT },
    /**
     * 当前正在播放的媒体应用；`null` 表示探测不可用（反射被拦、binder 抛错等）。
     *
     * 入口只在有媒体播放时显示，点击时又把同一份结果当作面板的种子带过去，
     * 所以「按钮显示」和「面板里真的有应用」用的是同一个判定，不会对不上。
     */
    private val activeMediaApps: (Context) -> List<ActiveMediaApp>? = { null },
    /**
     * 「入口只在播放时出现」开关是否开启。
     *
     * 关掉后入口回到常驻行为：无论有没有播放都显示。读取失败时按开启处理，
     * 与默认行为一致（用户没改过设置就是开启）。
     */
    private val entryPlaybackOnlyEnabled: () -> Boolean = { true },
    /**
     * 入口圆钮的材质来源。
     *
     * 默认 [EntryMaterial.HYPERLIGHT]：拿得到 HyperLight 就用它的液态玻璃，
     * 拿不到自动退回自研玻璃（再退官方高光材质），不会让入口变成裸按钮。
     */
    private val entryMaterial: () -> EntryMaterial = { EntryMaterial.DEFAULT },
    /**
     * 内置面板是否跟随 HyperLight 的液态玻璃（系统展开面板同款）。
     *
     * 关掉后面板只走官方展开材质 + SoundMan 自研玻璃，与没有 HyperLight 时一致。
     */
    private val hyperLightPanelGlassEnabled: () -> Boolean = { true },
) {
    private val officialDismissHook = SystemUiOfficialDismissHookBridge(log)
    private val hyperLightGlass = HyperLightGlassBridge(log)
    private val playbackMonitor = SystemUiPlaybackMonitor(
        onChange = ::onPlaybackConfigChanged,
        log = { message, throwable -> log(Log.WARN, TAG, message, throwable) },
    )
    private val builtinPanel = SystemUiBuiltinVolumePanel(
        log = log,
        hookDismiss = officialDismissHook::dismiss,
        rescheduleOfficialTimeout = officialDismissHook::rescheduleTimeout,
        hideSystemAppsEnabled = hideSystemAppsEnabled,
        volumePercentEnabled = volumePercentEnabled,
        liquidGlassEnabled = liquidGlassEnabled,
        liquidGlassRefractionEnabled = liquidGlassRefractionEnabled,
        liquidGlassBlurRadius = liquidGlassBlurRadius,
        liquidGlassBlendColor = liquidGlassBlendColor,
        hyperLightPanelGlass = { view, radius ->
            hyperLightPanelGlassEnabled() &&
                hyperLightGlass.available() &&
                hyperLightGlass.liquidGlassEnabled() &&
                hyperLightGlass.attachExpandedPanel(view, radius)
        },
    )
    private val trackedEntries = ArrayList<TrackedEntry>()
    private val pendingInsertions = ArrayList<PendingInsertion>()
    private val closing = AtomicBoolean(false)
    private val lastTriggerLogMillis = AtomicLong()
    private val lastDelayLogMillis = AtomicLong()
    private val lifecycleLock = ReentrantLock()
    private val insertionsIdle = lifecycleLock.newCondition()
    private var activeInsertions = 0
    private var officialBlur: OfficialRingerBlur? = null
    private var componentMaterial: OfficialComponentMaterial? = null
    private var pluginClassLoader: ClassLoader? = null
    private val entryGlass = WeakHashMap<View, LiquidGlassPanelDrawable>()

    /**
     * 插件 ClassLoader 就绪后安装 live MiBlur 入口。
     *
     * `MiBlurCompat` / `Util` / `miuix.*` 只在插件 ClassLoader 里。
     */
    fun attachPluginClassLoader(pluginClassLoader: ClassLoader) {
        this.pluginClassLoader = pluginClassLoader
        officialBlur = OfficialRingerBlur(pluginClassLoader, log)
        componentMaterial = OfficialComponentMaterial(pluginClassLoader, log)
    }

    /** 缓存 hook 框架已捕获的官方 controller 及其公开 dismissH 入口。 */
    fun captureOfficialDismissController(controller: Any, dismiss: (Any) -> Unit) {
        officialDismissHook.capture(controller, dismiss)
    }

    /** 官方 controller 已执行 dismissH 时由 hooker 通知。 */
    fun onOfficialVolumeDismiss(reason: Int) {
        try {
            builtinPanel.closeForOfficialDismiss(reason)
        } catch (throwable: Throwable) {
            log(
                Log.ERROR,
                TAG,
                "Unable to close builtin panel from official dismissH reason=$reason",
                throwable
            )
        }
    }

    private inline fun <T> withLifecycleLock(block: () -> T): T {
        lifecycleLock.lock()
        try {
            return block()
        } finally {
            lifecycleLock.unlock()
        }
    }

    private fun cleanupEntryAndPanel(entry: View): Boolean {
        try {
            builtinPanel.closeFor(entry)
        } catch (throwable: Throwable) {
            log(Log.ERROR, TAG, "Failed to close builtin panel during entry cleanup", throwable)
        }
        return cleanupEntry(entry)
    }

    private fun cleanupEntry(entry: View): Boolean {
        return try {
            removeCenteringFollow(entry)
            removeDragFollow(entry)
            forgetPlacement(entry)
            (entry.parent as? ViewGroup)?.removeView(entry)
            entry.setOnClickListener(null)
            clearVisuals(entry)
            entry.contentDescription = null
            entry.tag = null
            true
        } catch (throwable: Throwable) {
            log(Log.ERROR, TAG, "Failed to clean up SoundMan volume entry", throwable)
            false
        }
    }

    private fun clearVisuals(view: View) {
        view.background = null
        view.outlineProvider = null
        view.clipToOutline = false
        entryGlass.remove(view)?.let { glass ->
            try {
                glass.release()
            } catch (throwable: Throwable) {
                log(Log.ERROR, TAG, "Unable to release entry liquid glass", throwable)
            }
        }
        if (view is ImageView) {
            view.setImageDrawable(null)
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                clearVisuals(view.getChildAt(index))
            }
        }
    }

    /**
     * 入口圆钮材质档位：玻璃优先，其次官方组件材质，最后 ringer chrome。
     *
     * 放实例方法是因为设置开关（[liquidGlassEnabled]）只在实例上，而插入逻辑在
     * companion 里。
     *
     * 入口有没有玻璃由「按钮材质」一档决定：[EntryMaterial.LIQUID] 就是要自研玻璃，
     * [EntryMaterial.HYPERLIGHT] 拿不到 HyperLight 时也会退回自研玻璃，
     * 不再单独给入口留一个玻璃开关——那与材质档位互相冲突。
     */
    private fun chooseEntryMaterial(context: Context): EntryMaterialMode =
        EntryMaterialPolicy.choose(
            componentMaterialAvailable = componentMaterial?.available(context) == true,
            liquidGlassEnabled = liquidGlassEnabled(),
            entryMaterial = entryMaterial(),
            // 光抓到 ClassLoader 不算数，还得它自己的液态玻璃是开着的。
            hyperLightReady = hyperLightGlass.available() && hyperLightGlass.liquidGlassEnabled(),
        )

    /**
     * 把 `View.setBackground` 的调用转给 HyperLight 桥接层。
     *
     * 这是拿到它那一份热 ClassLoader 的唯一途径——模块的类只活在 LSPosed 的
     * `LspModuleClassLoader` 里，没法直接枚举，只能等它自己挂载时反查。
     */
    fun noteBackground(drawable: Drawable) {
        runCatching { hyperLightGlass.noteBackground(drawable) }
    }

    private fun attachHyperLightGlass(chrome: View, radiusPx: Int): Boolean =
        hyperLightGlass.attach(chrome, radiusPx.toFloat())

    /**
     * 读取用户选择的入口落位；读取失败时保持默认落位。
     *
     * 位置偏好再怎么脏也只是「按钮摆在上还是在下」，不值得为此丢掉整颗入口，
     * 所以这里一律吞异常回退默认，和材质/玻璃开关同一个处理口径。
     */
    private fun readEntryPosition(): EntryPosition = try {
        entryPosition()
    } catch (throwable: Throwable) {
        log(Log.ERROR, TAG, "Unable to read volume entry position; keeping default", throwable)
        EntryPosition.DEFAULT
    }

    /**
     * 入口圆钮液态玻璃的渲染配置。
     *
     * 与内置展开面板共用同一组用户参数，官方组件材质不可用（或用户没装 HyperLight
     * 那类模块）时，入口靠它和展开面板保持同款观感。
     */
    private fun entryLiquidGlassConfig(): LiquidGlassPanelConfig? {
        if (!liquidGlassEnabled()) return null
        return LiquidGlassPanelConfig(
            enabled = true,
            trueRefraction = LiquidGlassPanelPolicy.refractionActive(
                liquidGlassEnabled(),
                liquidGlassRefractionEnabled(),
            ),
            captureBlurRadius = liquidGlassBlurRadius().toFloat().coerceIn(0f, 20f),
            blendColor = liquidGlassBlendColor(),
        )
    }

    /**
     * 把液态玻璃叠在入口现有材质之上，和内置面板一样用 LayerDrawable 叠加而不是覆盖。
     *
     * 折射层不透明时视觉上替换下方材质，捕获失败则整层不绘制、露出官方材质兜底。
     */
    private fun attachEntryLiquidGlass(chrome: View, radiusPx: Int): Boolean {
        val config = entryLiquidGlassConfig() ?: return false
        return try {
            val glass = LiquidGlassPanelDrawable(
                context = chrome.context,
                host = chrome,
                initialConfig = config,
                initialCornerRadius = radiusPx.toFloat(),
                log = log,
            )
            val official = chrome.background
            chrome.background = if (official != null) LayerDrawable(arrayOf(official, glass)) else glass
            entryGlass[chrome] = glass
            true
        } catch (throwable: Throwable) {
            log(Log.ERROR, TAG, "Entry liquid glass attach failed; keeping official material only", throwable)
            false
        }
    }

    private fun beginInsertion(): Boolean {
        withLifecycleLock {
            if (closing.get()) return false
            activeInsertions += 1
            return true
        }
    }

    private fun endInsertion() {
        withLifecycleLock {
            activeInsertions -= 1
            insertionsIdle.signalAll()
        }
    }

    private fun track(entry: View, uiLooper: Looper): Boolean {
        withLifecycleLock {
            if (closing.get()) return false
            synchronized(trackedEntries) {
                if (trackedEntries.none { it.view.get() === entry }) {
                    trackedEntries += TrackedEntry(WeakReference(entry), uiLooper)
                }
            }
            return true
        }
    }

    /** 根据动态设置选择同窗原生页或稳定悬浮层；读取或挂载失败时始终回退。 */
    fun openPanel(context: Context, trigger: String, sourceView: View) {
        if (closing.get()) return
        val enabled = try {
            builtinPanelEnabled()
        } catch (error: Throwable) {
            log(
                Log.ERROR,
                TAG,
                "Unable to read builtin panel preference; falling back to overlay",
                error
            )
            false
        }
        try {
            SystemUiBuiltinPanelPolicy.open(
                builtinEnabled = enabled,
                mountBuiltin = {
                    try {
                        builtinPanel.mount(sourceView) {
                            try {
                                openOverlay(context, "$trigger-device-route", sourceView)
                            } catch (throwable: Throwable) {
                                log(
                                    Log.ERROR,
                                    TAG,
                                    "Device-route overlay callback failed",
                                    throwable
                                )
                            }
                        }
                    } catch (throwable: Throwable) {
                        log(Log.ERROR, TAG, "Builtin panel mount callback failed", throwable)
                        false
                    }
                },
                openOverlay = {
                    try {
                        openOverlay(context, trigger, sourceView)
                    } catch (throwable: Throwable) {
                        log(Log.ERROR, TAG, "Overlay fallback callback failed", throwable)
                    }
                },
            )
        } catch (throwable: Throwable) {
            log(Log.ERROR, TAG, "Volume panel boundary callback failed", throwable)
        }
    }

    /**
     * 把刚探测到的正在播放的应用打包成面板种子。
     *
     * 动机：面板进程要连宿主做一次握手才有列表，冷启动那几百毫秒里列表是空的。
     * 侧栏为了决定「显示不显示入口」刚刚探测过一次，这份结果直接带过去当首帧占位，
     * 用户点开立刻就能看到音量条，而不是先空一下再刷出来。
     *
     * 包名查不到（共享 uid 且无包）的应用没法画图标，直接丢掉：种子只是占位，
     * 少一条不影响宿主快照到达后的真实列表。
     */
    private fun seededPlayback(context: Context): List<SeededPlayback> = try {
        activeMediaApps(context)
            ?.mapNotNull { app ->
                val packageName = app.packageName?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                SeededPlayback(app.uid, packageName)
            }
            ?: emptyList()
    } catch (throwable: Throwable) {
        log(Log.WARN, TAG, "Unable to seed panel playback list; opening without it", throwable)
        emptyList()
    }

    /**
     * 从 SystemUI/插件进程打开 SoundMan 面板，并关掉音量侧栏。
     */
    fun openOverlay(context: Context, trigger: String, sourceView: View) {
        if (closing.get()) return
        val launch = OverlayOpenRequest.sidebarActivityLaunch(seededPlayback(context))
        val intent = Intent(launch.action)
            .setComponent(ComponentName(launch.packageName, launch.className))
            .addFlags(launch.flags)
        OverlayOpenRequest.fromExtras(launch.extras).putInto(intent)
        try {
            log(Log.INFO, TAG, "[systemui] $trigger startActivity begin component=${intent.component}", null)
            context.startActivity(intent)
            log(Log.INFO, TAG, "[systemui] $trigger startActivity dispatched component=${intent.component}", null)
            dismissVolumeSidebar(sourceView)
        } catch (error: ActivityNotFoundException) {
            log(Log.ERROR, TAG, "SoundMan overlay activity was not found trigger=$trigger", error)
        } catch (error: SecurityException) {
            log(Log.ERROR, TAG, "SystemUI is not allowed to open the SoundMan overlay trigger=$trigger", error)
        } catch (error: Throwable) {
            log(Log.ERROR, TAG, "Unable to open the SoundMan overlay trigger=$trigger", error)
        }
    }

    private fun dismissVolumeSidebar(sourceView: View) {
        val root = sourceView.rootView
        if (root == null) {
            log(Log.ERROR, TAG, "Volume sidebar dismiss skipped: rootView is null", null)
            return
        }
        OverlayOpenRequest.volumeSidebarDismissSequence().forEach { stroke ->
            val dispatched = try {
                root.dispatchKeyEvent(KeyEvent(stroke.action, stroke.keyCode))
            } catch (error: Throwable) {
                log(
                    Log.ERROR,
                    TAG,
                    "Volume sidebar dismiss dispatch failed action=${stroke.action} keyCode=${stroke.keyCode}",
                    error,
                )
                return@forEach
            }
            if (!dispatched) {
                log(
                    Log.ERROR,
                    TAG,
                    "Volume sidebar dismiss was not handled action=${stroke.action} keyCode=${stroke.keyCode}",
                    null,
                )
            }
        }
    }

    /**
     * 音量面板展开时隐藏只有紧凑态的第三颗入口。
     *
     * 找不到入口只打日志，不得把异常打穿 SystemUI。
     */
    fun applyExpanded(root: View?, expanded: Boolean) {
        if (root == null) {
            log(Log.ERROR, TAG, "Volume expand update skipped: root is not a View", null)
            return
        }
        val uiLooper = root.handler?.looper ?: Looper.myLooper() ?: Looper.getMainLooper()
        val update = Runnable {
            try {
                if (closing.get()) {
                    log(Log.WARN, TAG, "Volume expand update skipped: runtime is closing", null)
                    return@Runnable
                }
                val entry = findInsertedEntry(root)
                if (entry == null) {
                    log(
                        Log.WARN,
                        TAG,
                        "Volume entry not found for expanded=$expanded root=${describeView(root)}",
                        null,
                    )
                    return@Runnable
                }
                val presence = readPresence(root.context)
                applyEntryVisibility(entry, expanded, presence, "expanded=$expanded")
            } catch (throwable: Throwable) {
                log(Log.ERROR, TAG, "Volume expand update failed for expanded=$expanded", throwable)
            }
        }
        if (Looper.myLooper() === uiLooper) {
            update.run()
        } else if (!Handler(uiLooper).post(update)) {
            log(Log.ERROR, TAG, "Volume expand update rejected by target View UI Looper", null)
        }
    }

    private fun findInsertedEntry(root: View): View? = findExistingEntry(root)

    /**
     * 读取当前媒体播放判定；探测抛错时按 [MediaPresence.UNKNOWN] 处理。
     *
     * 动机：入口可见性是个「锦上添花」的判断，探测不可用不能连累入口本身 ——
     * 藏掉入口等于让整个模块看起来失灵，多显示一颗按钮则只是多一个入口。
     *
     * 用户关掉「仅在播放时显示」后直接返回 [MediaPresence.UNKNOWN]：
     * 该状态下的入口恒显示，等价于彻底跳过播放门控，不用在策略层再加分支。
     */
    private fun readPresence(context: Context): MediaPresence {
        val gating = try {
            entryPlaybackOnlyEnabled()
        } catch (throwable: Throwable) {
            log(
                Log.WARN,
                TAG,
                "Unable to read entry playback-only setting; keeping playback gating on",
                throwable,
            )
            true
        }
        if (!gating) return MediaPresence.UNKNOWN
        return try {
            MediaPresence.from(activeMediaApps(context))
        } catch (throwable: Throwable) {
            log(
                Log.WARN,
                TAG,
                "Unable to probe active media playback; keeping volume entry visible",
                throwable,
            )
            MediaPresence.UNKNOWN
        }
    }

    /**
     * 应用入口可见性并补一条诊断日志。
     *
     * 展开态要记进 [expandedStates]：播放回调刷新时没有官方的 timer_layout 可看，
     * 只能靠上一次官方回调留下的展开态，不能因为刷新就把展开态的入口点亮。
     */
    private fun applyEntryVisibility(
        entry: View,
        expanded: Boolean,
        presence: MediaPresence,
        reason: String,
    ) {
        rememberExpanded(entry, expanded)
        entry.visibility = EntryPresencePolicy.visibility(expanded, presence)
        if (!EntryPresencePolicy.isVisible(expanded, presence)) {
            log(
                Log.INFO,
                TAG,
                "[systemui] volume entry hidden ($reason presence=$presence) view=${describeView(entry)}",
                null,
            )
        }
    }

    /**
     * 播放配置变化：面板停在屏幕上的时候，开始/停止播放也要重算入口可见性。
     *
     * 走每颗入口自己的 UI 线程；入口已经被回收就跳过。
     */
    private fun onPlaybackConfigChanged() {
        val tracked = synchronized(trackedEntries) { ArrayList(trackedEntries) }
        tracked.forEach { item ->
            val entry = item.view.get() ?: return@forEach
            val refresh = Runnable {
                try {
                    if (closing.get()) return@Runnable
                    val presence = readPresence(entry.context)
                    applyEntryVisibility(entry, expandedOf(entry), presence, "playback-callback")
                } catch (throwable: Throwable) {
                    log(Log.ERROR, TAG, "Playback presence refresh failed", throwable)
                }
            }
            if (!entry.post(refresh)) {
                log(Log.ERROR, TAG, "Playback presence refresh rejected by entry UI thread", null)
            }
        }
    }

    fun scheduleInsertion(thisObject: Any?, trigger: String) {
        if (closing.get()) return
        val root = thisObject as? View
        if (root == null) {
            log(
                Log.ERROR,
                TAG,
                "Volume insertion skipped: trigger=$trigger target is not a View: ${thisObject?.javaClass?.name}",
                null,
            )
            return
        }
        logRateLimited(
            lastTriggerLogMillis,
            "[systemui] trigger=$trigger root=${describeView(root)} attached=${root.isAttachedToWindow}",
        )
        // 面板一呼出会停在屏幕上好几秒，这段时间里的播放变化只有 AudioPlaybackCallback 能看到。
        playbackMonitor.register(root.context)
        // 侧栏已经出来了，用户还没点到按钮：趁这几百毫秒把面板要用的宿主握手跑完，
        // 免得点开时面板先空一帧再刷出来。
        prewarmPanelBridge(root)
        val uiLooper = root.handler?.looper ?: Looper.myLooper()
        if (uiLooper == null) {
            log(Log.ERROR, TAG, "Volume insertion skipped: trigger=$trigger has no UI Looper", null)
            return
        }
        val resolved = resolveAnchor(root)
        if (resolved == null && root.isAttachedToWindow && isReady(root)) {
            log(Log.ERROR, TAG, "Volume anchor not found under ${describeView(root)} trigger=$trigger", null)
            return
        }

        synchronized(pendingInsertions) {
            pendingInsertions.filter { it.root.get() === root }.forEach(::cancelPending)
            pendingInsertions.removeAll { it.root.get() == null || it.cancelled.get() }
        }

        val pending = PendingInsertion(
            WeakReference(root),
            resolved?.view?.let { WeakReference(it) },
            resolved?.name,
            uiLooper,
        )
        val attempt = Runnable {
            pending.postQueued.set(false)
            if (pending.cancelled.get() || closing.get()) return@Runnable
            val currentRoot = pending.root.get() ?: return@Runnable removePending(pending)
            val currentAnchor = pending.anchor?.get() ?: resolveAnchor(currentRoot)?.also { found ->
                pending.anchor = WeakReference(found.view)
                pending.anchorName = found.name
            }?.view
            if (currentAnchor == null) {
                if (currentRoot.isAttachedToWindow && isReady(currentRoot)) {
                    log(Log.ERROR, TAG, "Volume anchor not found under ${describeView(currentRoot)} trigger=$trigger", null)
                    cancelPending(pending)
                    removePending(pending)
                } else {
                    logRateLimited(
                        lastDelayLogMillis,
                        "[systemui] delaying insertion trigger=$trigger root=${describeView(currentRoot)} attached=${currentRoot.isAttachedToWindow} laidOut=${currentRoot.isLaidOut}",
                    )
                }
                return@Runnable
            }
            if (!currentRoot.isAttachedToWindow || !isReady(currentAnchor)) {
                logRateLimited(
                    lastDelayLogMillis,
                    "[systemui] delaying insertion trigger=$trigger root=${describeView(currentRoot)} target=${describeView(currentAnchor)} attached=${currentRoot.isAttachedToWindow} laidOut=${currentAnchor.isLaidOut} size=${currentAnchor.measuredWidth}x${currentAnchor.measuredHeight}",
                )
                return@Runnable
            }
            if (!beginInsertion()) return@Runnable
            val position = readEntryPosition()
            try {
                if (!closing.get() && !pending.cancelled.get()) {
                    insertEntry(
                        currentRoot,
                        currentAnchor,
                        pending.anchorName ?: "unknown",
                        trigger,
                        log,
                        { closing.get() },
                        ::track,
                        ::cleanupEntryAndPanel,
                        ::openPanel,
                        officialBlur,
                        componentMaterial,
                        ::chooseEntryMaterial,
                        ::attachEntryLiquidGlass,
                        ::attachHyperLightGlass,
                        pluginClassLoader,
                        position,
                        ::readPresence,
                    )
                }
            } catch (throwable: Throwable) {
                log(Log.ERROR, TAG, "Failed to add SoundMan volume entry", throwable)
            } finally {
                endInsertion()
                cancelPending(pending)
                removePending(pending)
            }
        }
        pending.postTask = attempt
        pending.layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            queueInsertionAttempt(pending)
        }
        val listenTarget = resolved?.view ?: root
        listenTarget.addOnLayoutChangeListener(pending.layoutListener)
        synchronized(pendingInsertions) {
            if (closing.get()) {
                cancelPending(pending)
                return
            }
            pendingInsertions += pending
        }
        log(
            Log.DEBUG,
            TAG,
            "$trigger queued SoundMan volume entry until ${pending.anchorName ?: "root"} is measured",
            null,
        )
        queueInsertionAttempt(pending)
    }

    /**
     * 后台预热面板桥接；失败只是回到原来的等待，不能影响入口本身。
     *
     * @param root 侧栏根 View，用它的 context 解析模块 Provider
     */
    private fun prewarmPanelBridge(root: View) {
        try {
            PanelBridgePrewarm.warm(root.context) { message, error ->
                log(Log.DEBUG, TAG, message, error)
            }
        } catch (throwable: Throwable) {
            log(Log.WARN, TAG, "Unable to prewarm panel bridge", throwable)
        }
    }

    private fun resolveAnchor(root: View): AnchorMatch? = findAnchorByResource(root)

    private fun findAnchorByResource(root: View): AnchorMatch? {
        val packages = SystemUiVolumeEntryLayout.resourcePackages(root.context.packageName)
        var current = root.parent as? View ?: return null
        while (true) {
            SystemUiVolumeEntryLayout.VOLUME_COLUMN_RESOURCE_NAMES.forEach { name ->
                val view = findViewByIdName(current, name, packages, log)
                if (view != null && !isWithinRoot(view, root)) {
                    log(
                        Log.INFO,
                        TAG,
                        "Found volume column id=$name view=${describeView(view)} from=${describeView(root)}",
                        null,
                    )
                    return AnchorMatch(view, "id:$name")
                }
            }
            if (current.javaClass.name == VOLUME_DIALOG_VIEW_CLASS) {
                log(
                    Log.ERROR,
                    TAG,
                    "Volume column not found under $VOLUME_DIALOG_VIEW_CLASS from ${describeView(root)}",
                    null,
                )
                return null
            }
            current = current.parent as? View ?: break
        }
        log(
            Log.ERROR,
            TAG,
            "Volume column not found; stopped above ${describeView(root)} without reaching $VOLUME_DIALOG_VIEW_CLASS",
            null,
        )
        return null
    }

    private fun isReady(view: View): Boolean =
        view.isLaidOut && view.measuredWidth > 0 && view.measuredHeight > 0

    private fun queueInsertionAttempt(pending: PendingInsertion) {
        if (pending.cancelled.get() || closing.get() || !pending.postQueued.compareAndSet(false, true)) return
        val root = pending.root.get()
        val task = pending.postTask
        if (root == null || task == null || !root.post(task)) {
            pending.postQueued.set(false)
            log(Log.ERROR, TAG, "Unable to queue SoundMan volume entry insertion on target root", null)
            cancelPending(pending)
            removePending(pending)
        }
    }

    private fun cancelPending(pending: PendingInsertion) {
        pending.cancelled.set(true)
        val root = pending.root.get()
        val task = pending.postTask
        if (root != null && task != null) root.removeCallbacks(task)
        pending.postQueued.set(false)
        val listener = pending.layoutListener
        val listenTarget = pending.anchor?.get() ?: pending.root.get()
        if (listenTarget != null && listener != null) listenTarget.removeOnLayoutChangeListener(listener)
        pending.postTask = null
        pending.layoutListener = null
    }

    private fun removePending(pending: PendingInsertion) {
        synchronized(pendingInsertions) { pendingInsertions.remove(pending) }
    }

    private fun logRateLimited(clock: AtomicLong, message: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        val previous = clock.get()
        if (now - previous >= REPEATED_LOG_INTERVAL_MILLIS && clock.compareAndSet(previous, now)) {
            log(Log.DEBUG, TAG, message, null)
        }
    }

    private fun describeView(view: View): String =
        "${view.javaClass.name}@${Integer.toHexString(System.identityHashCode(view))} id=${view.id}"

    private data class TrackedEntry(
        val view: WeakReference<View>,
        val uiLooper: Looper,
    )

    private class PendingInsertion(
        val root: WeakReference<View>,
        var anchor: WeakReference<View>?,
        var anchorName: String?,
        val uiLooper: Looper,
    ) {
        val cancelled = AtomicBoolean(false)
        val postQueued = AtomicBoolean(false)
        var postTask: Runnable? = null
        var layoutListener: View.OnLayoutChangeListener? = null
    }

    private data class AnchorMatch(
        val view: View,
        val name: String,
    )

    companion object {
        private const val TAG = "SoundMan.SystemUi"
        private const val ENTRY_TAG = "hk.uwu.soundman:volume_entry"
        private const val VOLUME_DIALOG_VIEW_CLASS =
            "com.android.systemui.miui.volume.MiuiVolumeDialogView"
        private const val REPEATED_LOG_INTERVAL_MILLIS = 2_000L

        /**
         * 官方 SlideContainerAnim 动画驱动的容器资源名，按优先级查找。
         *
         * `miui_volume_content` 对应 VolumePanelViewController.mVolumeContentView，
         * 是折叠态 getVolumeContainer() 的默认返回值。
         * `volume_dialog_content` 是部分 HyperOS 版本的等价资源名。
         */
        private val ANIMATED_CONTAINER_RESOURCE_NAMES: List<String> = listOf(
            "miui_volume_content",
            "volume_dialog_content",
        )

        /**
         * 用作 [View.setTag] key 的宿主 R.id 字段名。
         *
         * 官方 QSTileItemIconView 用 `R.id.qs_icon_state_tag` 做 tag key。
         * SoundMan 通过反射读取宿主 `R.id` 类的静态 int 字段获取同一值，
         * 不用 `getIdentifier`。
         */
        private const val TAG_KEY_FIELD_NAME = "qs_icon_state_tag"

        /**
         * 宿主 R.id 类的全名。
         *
         * 原版 `QSTileItemIconView` 使用 `import miui.systemui.controlcenter.R`，
         * `qs_icon_state_tag` 定义在该 R 的 id 内部类中。
         */
        private const val R_ID_CLASS_NAME = "miui.systemui.controlcenter.R\$id"

        /**
         * 反射读取宿主 R.id 的静态 int 值，缓存结果。
         *
         * @param pluginClassLoader 宿主/插件 ClassLoader
         * @return R.id 字段值；找不到时返回 [View.NO_ID]
         */
        @Volatile
        private var cachedTagKey: Int = View.NO_ID

        private fun resolveTagKey(pluginClassLoader: ClassLoader?): Int {
            if (cachedTagKey != View.NO_ID) return cachedTagKey
            if (pluginClassLoader == null) return View.NO_ID
            val clazz = R_ID_CLASS_NAME.toClassOrNull(pluginClassLoader) ?: return View.NO_ID
            val field = clazz.resolve().optional(silent = true)
                .firstFieldOrNull { name = TAG_KEY_FIELD_NAME }
            val id = field?.getQuietly<Int>() ?: 0
            if (id != 0) {
                cachedTagKey = id
                return id
            }
            return View.NO_ID
        }

        /**
         * 安装拖拽动画跟随监听器，使入口按钮跟随官方音量侧栏的 SlideContainerAnim 缩放/位移。
         *
         * 动机：官方 VolumePanelViewController.initAnimListener() 的 SeekBarAnimListener
         * 回调 setScale/setVolY 只对 getVolumeContainer() 返回的视图（通常是
         * mVolumeContentView）应用缩放和位移。SoundMan 入口按钮虽然插入在音量条上方
         * 的同一父容器中，但可能不在 getVolumeContainer() 返回的视图内部（例如
         * mShouldTempBeVisible 时 getVolumeContainer() 返回 mTempColumnContainer），
         * 导致入口按钮不跟随拖拽动画。
         *
         * 方案：在每帧绘制前将入口按钮的 scaleX/scaleY/translationY 与动画容器同步。
         * 使用 OnAttachStateChangeListener 管理监听器生命周期，避免 ViewTreeObserver
         * 失效后监听器丢失。
         *
         * @param entry 已插入的入口按钮
         * @param root 音量侧栏根视图（MiuiRingerModeLayout）
         * @param pluginClassLoader 宿主/插件 ClassLoader，用于反射读取 R.id
         * @param log 日志函数
         */
        private fun installDragFollow(
            entry: View,
            root: View,
            pluginClassLoader: ClassLoader?,
            log: (priority: Int, tag: String, message: String, throwable: Throwable?) -> Unit,
        ) {
            val packages = SystemUiVolumeEntryLayout.resourcePackages(root.context.packageName)
            val animatedContainer = findAnimatedContainer(entry, root, packages)
            if (animatedContainer == null) {
                log(
                    Log.WARN,
                    TAG,
                    "Drag follow skipped: animated container not found for entry",
                    null
                )
                return
            }
            log(
                Log.INFO,
                TAG,
                "Installing drag follow: entry=${entry.javaClass.name}@${
                    Integer.toHexString(
                        System.identityHashCode(
                            entry
                        )
                    )
                } " +
                        "container=${animatedContainer.javaClass.name}@${
                            Integer.toHexString(
                                System.identityHashCode(
                                    animatedContainer
                                )
                            )
                        }",
                null,
            )

            val preDrawListener = ViewTreeObserver.OnPreDrawListener {
                if (!entry.isVisible || !entry.isAttachedToWindow) {
                    return@OnPreDrawListener true
                }
                entry.scaleX = animatedContainer.scaleX
                entry.scaleY = animatedContainer.scaleY
                entry.translationY = animatedContainer.translationY
                true
            }

            val attachListener = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    val observer = v.viewTreeObserver
                    if (observer.isAlive) {
                        observer.addOnPreDrawListener(preDrawListener)
                    }
                }

                override fun onViewDetachedFromWindow(v: View) {
                    val observer = v.viewTreeObserver
                    if (observer.isAlive) {
                        observer.removeOnPreDrawListener(preDrawListener)
                    }
                }
            }

            val tagKey = resolveTagKey(pluginClassLoader)
            if (tagKey == View.NO_ID) {
                log(
                    Log.ERROR,
                    TAG,
                    "Drag follow skipped: R.id.$TAG_KEY_FIELD_NAME not found via reflection",
                    null
                )
                return
            }

            entry.addOnAttachStateChangeListener(attachListener)
            if (entry.isAttachedToWindow) {
                val observer = entry.viewTreeObserver
                if (observer.isAlive) {
                    observer.addOnPreDrawListener(preDrawListener)
                }
            }

            entry.setTag(tagKey, Pair(preDrawListener, attachListener))
        }

        /**
         * 移除拖拽动画跟随监听器。
         */
        private fun removeDragFollow(entry: View) {
            val tagKey = cachedTagKey
            if (tagKey == View.NO_ID) return
            val tag = entry.getTag(tagKey) as? Pair<*, *> ?: return
            val preDrawListener = tag.first as? ViewTreeObserver.OnPreDrawListener
            val attachListener = tag.second as? View.OnAttachStateChangeListener
            if (preDrawListener != null && entry.isAttachedToWindow) {
                val observer = entry.viewTreeObserver
                if (observer.isAlive) {
                    observer.removeOnPreDrawListener(preDrawListener)
                }
            }
            if (attachListener != null) {
                entry.removeOnAttachStateChangeListener(attachListener)
            }
            entry.setTag(tagKey, null)
        }

        /**
         * 安装横屏居中补偿跟随。
         *
         * 动机：官方折叠态 `MiuiVolumeDialogRes.getMarginTop` 在横屏返回
         * `(屏幕高 - o3_miui_volume_background_height) / 2`，即按"官方内容高度"
         * 竖直居中。SoundMan 入口插入 `MiuiVolumeDialogView` 内部后实际高度多了
         * `entry + gap`，但 topMargin 仍按官方高度计算，导致整体下移 `(entry+gap)/2`。
         * 竖屏时官方用固定 dimen 做 topMargin，不存在居中问题，不补偿。
         *
         * 方案：在 dialogView 上挂 OnLayoutChangeListener，每次布局后检查
         * `MarginLayoutParams.topMargin`；横屏 + 入口可见（折叠态）时把 topMargin
         * 减去 `entry.measuredHeight + entry.bottomMargin` 的一半，让整组重新居中。
         * 官方在旋转/展开/收起时会重写 topMargin 触发 onLayoutChange，这里自修正：
         * 只要当前值不等于我们上次写入的值，就把它当作官方新基准重新补偿。
         *
         * @param entry 已插入的入口按钮
         * @param root 音量侧栏根视图（MiuiRingerModeLayout）
         * @param log 日志函数
         */
        private fun installCenteringFollow(
            entry: View,
            root: View,
            log: (priority: Int, tag: String, message: String, throwable: Throwable?) -> Unit,
        ) {
            val dialog = resolveDialogBound(root)
            if (dialog === root) {
                log(Log.WARN, TAG, "Centering follow skipped: dialog bound not found", null)
                return
            }
            val packages = SystemUiVolumeEntryLayout.resourcePackages(root.context.packageName)
            val officialHeight = resolveNamedDimenPx(
                root.context,
                packages,
                SystemUiVolumeEntryLayout.CENTERED_HEIGHT_DIMEN_NAMES,
                log,
            )
            if (officialHeight == null || officialHeight <= 0) {
                log(
                    Log.ERROR,
                    TAG,
                    "Centering follow skipped: official collapsed height dimen missing " +
                            "${SystemUiVolumeEntryLayout.CENTERED_HEIGHT_DIMEN_NAMES}",
                    null,
                )
                return
            }
            removeCenteringFollowForDialog(dialog)
            val follow = CenteringFollow(WeakReference(entry), officialHeight, log)
            val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                applyCenteringFollow(dialog, follow)
            }
            follow.listener = listener
            dialog.addOnLayoutChangeListener(listener)
            centeringFollows[dialog] = follow
            applyCenteringFollow(dialog, follow)
        }

        /**
         * 移除入口的横屏居中补偿并恢复官方 topMargin。
         *
         * @param entry 已被移除/清理的入口按钮
         */
        private fun removeCenteringFollow(entry: View) {
            val iterator = centeringFollows.entries.iterator()
            while (iterator.hasNext()) {
                val item = iterator.next()
                if (item.value.entry.get() === entry) {
                    restoreCentering(item.key, item.value)
                    item.value.listener?.let { item.key.removeOnLayoutChangeListener(it) }
                    iterator.remove()
                }
            }
        }

        private fun removeCenteringFollowForDialog(dialog: View) {
            val follow = centeringFollows.remove(dialog) ?: return
            follow.listener?.let { dialog.removeOnLayoutChangeListener(it) }
            restoreCentering(dialog, follow)
        }

        /**
         * 恢复被补偿过的 topMargin 为官方基准值。
         */
        private fun restoreCentering(dialog: View, follow: CenteringFollow) {
            val applied = follow.applied ?: return
            val layoutParams = dialog.layoutParams as? ViewGroup.MarginLayoutParams ?: return
            if (layoutParams.topMargin == applied) {
                layoutParams.topMargin = follow.lastBase
                dialog.layoutParams = layoutParams
            }
            follow.applied = null
        }

        /**
         * 重新计算并应用横屏居中补偿；自修正官方重写。
         *
         * 仅在满足全部条件时补偿：横屏、入口可见（折叠态）、入口已测量、
         * 官方当前 topMargin 等于 `(屏幕高 - 官方高度) / 2`（即官方确实在居中）。
         * 最后一条检查可以天然排除 flip-tiny/wide-fold 等官方不走居中的路径，
         * 避免在固定 topMargin 布局上误加补偿。
         */
        private fun applyCenteringFollow(dialog: View, follow: CenteringFollow) {
            val entry = follow.entry.get()
            val layoutParams = dialog.layoutParams as? ViewGroup.MarginLayoutParams
            if (entry == null || layoutParams == null) {
                return
            }
            val current = layoutParams.topMargin
            val base = if (follow.applied == current) follow.lastBase else current
            val landscape = dialog.resources.configuration.orientation ==
                    Configuration.ORIENTATION_LANDSCAPE
            if (!landscape || !entry.isVisible || entry.measuredHeight <= 0) {
                if (follow.applied != null && current == follow.applied) {
                    layoutParams.topMargin = base
                    dialog.layoutParams = layoutParams
                }
                follow.applied = null
                return
            }
            val displayHeight = realDisplayHeight(dialog)
            val centered = (displayHeight - follow.officialHeight) / 2
            if (base != centered) {
                if (follow.applied != null && current == follow.applied) {
                    layoutParams.topMargin = follow.lastBase
                    dialog.layoutParams = layoutParams
                }
                follow.applied = null
                if (!follow.mismatchLogged) {
                    follow.mismatchLogged = true
                    follow.log(
                        Log.WARN,
                        TAG,
                        "Landscape centering skipped: official margin $base != " +
                                "(display $displayHeight - height ${follow.officialHeight}) / 2",
                        null,
                    )
                }
                return
            }
            // 入口两侧的 margin 也算进额外高度：官方 gap 可能落在上边（下方落位）也可能落在下边（上方落位）。
            val entryMargins = (entry.layoutParams as? ViewGroup.MarginLayoutParams)
                ?.let { it.topMargin + it.bottomMargin } ?: 0
            val extra = entry.measuredHeight + entryMargins
            val compensated = base - extra / 2
            follow.lastBase = base
            if (current != compensated) {
                layoutParams.topMargin = compensated
                dialog.layoutParams = layoutParams
            }
            follow.applied = compensated
        }

        private fun realDisplayHeight(view: View): Int {
            val metrics = DisplayMetrics()
            val windowManager =
                view.context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            @Suppress("DEPRECATION")
            windowManager?.defaultDisplay?.getRealMetrics(metrics)
            return metrics.heightPixels
        }

        private data class CenteringFollow(
            val entry: WeakReference<View>,
            val officialHeight: Int,
            val log: (priority: Int, tag: String, message: String, throwable: Throwable?) -> Unit,
        ) {
            var listener: View.OnLayoutChangeListener? = null
            var lastBase: Int = 0
            var applied: Int? = null
            var mismatchLogged: Boolean = false
        }

        private val centeringFollows = WeakHashMap<View, CenteringFollow>()

        /**
         * 查找官方音量侧栏中被 SlideContainerAnim 动画驱动的容器视图。
         *
         * 优先从入口按钮向上搜索 miui_volume_content / volume_dialog_content；
         * 找不到则从根视图的父级向下搜索（动画容器与根视图是兄弟节点）。
         */
        private fun findAnimatedContainer(
            entry: View,
            root: View,
            packages: List<String>,
        ): View? {
            var current: View? = entry.parent as? View
            while (current != null) {
                if (isAnimatedContainerId(current, packages)) return current
                current = current.parent as? View
            }
            val rootParent =
                root.parent as? View ?: return findAnimatedContainerDownward(root, packages)
            return findAnimatedContainerDownward(rootParent, packages)
        }

        @SuppressLint("DiscouragedApi")
        private fun isAnimatedContainerId(view: View, packages: List<String>): Boolean {
            if (view.id == View.NO_ID) return false
            val resources = view.context.resources
            return ANIMATED_CONTAINER_RESOURCE_NAMES.any { name ->
                packages.any { pkg ->
                    runCatching {
                        resources.getIdentifier(name, "id", pkg)
                    }.getOrDefault(0) == view.id
                }
            }
        }

        private fun findAnimatedContainerDownward(
            view: View,
            packages: List<String>,
        ): View? {
            if (isAnimatedContainerId(view, packages)) return view
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    val found = findAnimatedContainerDownward(view.getChildAt(index), packages)
                    if (found != null) return found
                }
            }
            return null
        }

        private fun insertEntry(
            root: View,
            anchor: View,
            anchorName: String,
            trigger: String,
            log: (priority: Int, tag: String, message: String, throwable: Throwable?) -> Unit,
            isClosing: () -> Boolean,
            track: (View, Looper) -> Boolean,
            cleanup: (View) -> Boolean,
            openOverlay: (Context, String, View) -> Unit,
            officialBlur: OfficialRingerBlur?,
            componentMaterial: OfficialComponentMaterial?,
            chooseMaterial: (Context) -> EntryMaterialMode,
            attachLiquidGlass: (View, Int) -> Boolean,
            attachHyperLightGlass: (View, Int) -> Boolean,
            pluginClassLoader: ClassLoader?,
            position: EntryPosition,
            presenceOf: (Context) -> MediaPresence,
        ) {
            if (isClosing()) return
            if (!anchor.isLaidOut || anchor.measuredWidth <= 0 || anchor.measuredHeight <= 0) {
                log(Log.ERROR, TAG, "Volume anchor is not ready for measured insertion: $anchorName", null)
                return
            }
            val targetContext = root.context
            val uiLooper = root.handler?.looper ?: Looper.myLooper()
            if (uiLooper == null) {
                log(Log.ERROR, TAG, "Volume insertion skipped: trigger=$trigger has no UI Looper", null)
                return
            }
            val packages = SystemUiVolumeEntryLayout.resourcePackages(targetContext.packageName)
            val styleHost = findStyleTemplate(root, packages, log)
            val template = resolveDndTemplate(styleHost ?: root, packages, log)
            val density = targetContext.resources.displayMetrics.density
            fun dp(value: Int): Int = (value * density + 0.5f).toInt()
            val dimenWidth = resolveNamedDimenPx(
                targetContext,
                packages,
                SystemUiVolumeEntryLayout.BUTTON_WIDTH_DIMEN_NAMES,
                log,
            )
            val dimenHeight = resolveNamedDimenPx(
                targetContext,
                packages,
                SystemUiVolumeEntryLayout.BUTTON_HEIGHT_DIMEN_NAMES,
                log,
            )
            val metrics = resolveButtonMetrics(template, ::dp, dimenWidth, dimenHeight)
            val hadEntry = findExistingEntry(root) != null
            // 用户在面板还活着的时候改了落位：先把旧入口摘下来，
            // 之后 indexOfChild(anchor) 才是干净的新落位下标。
            detachMovedEntry(root, position, cleanup, log)
            // 间距必须在「入口尚未插入」时才实测：入口一插进去，静音/免打扰与音量条
            // 的实测距离就含入口自身，采信就会一次比一次大。注意 `removeView` 之后
            // 兄弟视图的 top/bottom 仍是旧值，所以这里按「本轮开始时是否插过」判断。
            val fallbackGapPx = dp(SystemUiVolumeEntryLayout.MARGIN_VERTICAL_DP)
            val gapResolution = resolveOfficialGap(
                ringerRoot = root,
                volumeAnchor = anchor,
                fallbackPx = fallbackGapPx,
                entryPresent = hadEntry,
                collapsed = !isExpanded(template.timerLayout),
                // 间距再大也不会超过一整行的高度；用入口自身高度当上限，跟着 dpi 缩放。
                maxPx = maxOf(fallbackGapPx, metrics.height),
            )
            val gap = gapResolution.px
            val placement = resolvePlacement(root, anchor, metrics, gap, position, log) ?: return
            val dialogBound = resolveDialogBound(root)
            if (!isWithinBound(placement.parent, dialogBound)) {
                failVisible(
                    placement.parent,
                    log,
                    "resolved placement escaped $VOLUME_DIALOG_VIEW_CLASS",
                )
                return
            }
            val existing: View? = findExistingEntry(root)
                ?: placement.parent.findViewWithTag(ENTRY_TAG)
            if (existing != null && existing !is FrameLayout) {
                log(
                    Log.WARN,
                    TAG,
                    "[systemui] $trigger removing stale non-frame entry view=${existing.javaClass.name}@" +
                        Integer.toHexString(System.identityHashCode(existing)),
                    null,
                )
                (existing.parent as? ViewGroup)?.removeView(existing)
            }
            if (isClosing()) return
            val entry = (existing as? FrameLayout) ?: FrameLayout(targetContext)
            try {
                if (!configureEntry(
                        entry,
                        targetContext,
                        template,
                        packages,
                        log,
                        isClosing,
                        openOverlay,
                        officialBlur,
                        componentMaterial,
                        chooseMaterial,
                        attachLiquidGlass,
                        attachHyperLightGlass,
                        pluginClassLoader,
                        officialLayout = root,
                    )
                ) {
                    return
                }
                val previousParent = entry.parent as? ViewGroup
                val previousIndex = previousParent?.indexOfChild(entry) ?: -1
                previousParent?.removeView(entry)
                val insertIndex =
                    if (previousParent === placement.parent && previousIndex in 0 until placement.index) {
                        placement.index - 1
                    } else {
                        placement.index
                    }
                placement.parent.addView(entry, insertIndex, placement.layoutParams)
                applyInsertVisibility(entry, template.timerLayout, presenceOf, log)
                if (!track(entry, uiLooper)) {
                    cleanup(entry)
                    return
                }
                installDragFollow(entry, root, pluginClassLoader, log)
                installCenteringFollow(entry, root, log)
                entry.tag = ENTRY_TAG
                appliedPositions[entry] = position
                val action = if (existing === entry) "adopted" else "inserted"
                val margins = placement.layoutParams as? ViewGroup.MarginLayoutParams
                // 几何诊断：这些数字跨呼出周期必须稳定。若 anchorBounds / parentSize 逐次变大，
                // 说明入口自身把容器撑大了，新一轮又按撑大后的锚点定位 —— 即漂移仍在。
                log(
                    Log.INFO,
                    TAG,
                    "[systemui] $trigger $action SoundMan entry view=${describeInserted(entry)} " +
                        "anchor=$anchorName position=$position parent=${placement.parent.javaClass.name} " +
                        "index=$insertIndex gap=$gap/${gapResolution.source} " +
                        "size=${placement.layoutParams.width}x${placement.layoutParams.height} " +
                        "anchorBounds=[${anchor.top}..${anchor.bottom}] " +
                        "parentSize=${placement.parent.width}x${placement.parent.height} " +
                        "entryMargin=(top=${margins?.topMargin},bottom=${margins?.bottomMargin})",
                    null,
                )
            } catch (throwable: Throwable) {
                cleanup(entry)
                log(
                    Log.ERROR,
                    TAG,
                    "$trigger failed to add SoundMan volume entry to ${placement.parent.javaClass.name}",
                    throwable,
                )
            }
        }

        private fun describeInserted(view: View): String =
            "${view.javaClass.name}@${Integer.toHexString(System.identityHashCode(view))}"

        /**
         * 落位开关被改动后，把已经插好的入口从原来的父容器里摘下来。
         *
         * 动机：跨进程偏好是「写入即生效」的，但入口已经插在视图树里了，
         * 光改 margin / 顺序不足以把它从音量条上方挪到下方——尤其是 FrameLayout 那种
         * 绝对定位分支，必须重新算 topMargin。最省事也最稳的做法是整颗摘掉，
         * 让后面的 [resolvePlacement] 按新落位重新插一遍。
         *
         * 只在「确实插过」且「落位真的变了」时动手，其余情况保持原样，
         * 避免每次 `updateExpandedH` 都无意义地把入口来回挪。
         */
        private fun detachMovedEntry(
            root: View,
            position: EntryPosition,
            cleanup: (View) -> Boolean,
            log: (priority: Int, tag: String, message: String, throwable: Throwable?) -> Unit,
        ) {
            val existing = findExistingEntry(root) ?: return
            val applied = appliedPositions[existing] ?: return
            if (applied == position) return
            log(
                Log.INFO,
                TAG,
                "Moving SoundMan volume entry from $applied to $position",
                null,
            )
            cleanup(existing)
        }

        /**
         * 记住每颗已插入入口当时用的落位。
         *
         * 用 WeakHashMap：入口被官方视图树回收时不应该被这里吊住。
         */
        private val appliedPositions = WeakHashMap<View, EntryPosition>()

        /**
         * 每个 `MiuiRingerModeLayout` 上缓存的官方间距。
         *
         * 只存「入口尚未插入」时测到的值（见 [EntryPlacementPolicy.resolveGap]），
         * 入口存在期间一律复用，避免把入口自身的高度反复算进官方间距里。
         */
        private val measuredGaps = WeakHashMap<View, Int>()

        private fun forgetPlacement(entry: View) {
            appliedPositions.remove(entry)
        }

        private fun configureEntry(
            entry: FrameLayout,
            targetContext: Context,
            template: DndTemplate,
            packages: List<String>,
            log: (priority: Int, tag: String, message: String, throwable: Throwable?) -> Unit,
            isClosing: () -> Boolean,
            openOverlay: (Context, String, View) -> Unit,
            officialBlur: OfficialRingerBlur?,
            componentMaterial: OfficialComponentMaterial?,
            chooseMaterial: (Context) -> EntryMaterialMode,
            attachLiquidGlass: (View, Int) -> Boolean,
            attachHyperLightGlass: (View, Int) -> Boolean,
            pluginClassLoader: ClassLoader?,
            officialLayout: View?,
        ): Boolean {
            val iconDrawable = resolvePhoneIcon(targetContext, packages, log) ?: return false
            val radiusPx = resolveNamedDimenPx(
                targetContext,
                packages,
                SystemUiVolumeEntryLayout.BUTTON_RADIUS_DIMEN_NAMES,
                log,
            )
            val density = targetContext.resources.displayMetrics.density
            fun dp(value: Int): Int = (value * density + 0.5f).toInt()
            val fallbackWidth = resolveNamedDimenPx(
                targetContext,
                packages,
                SystemUiVolumeEntryLayout.BUTTON_WIDTH_DIMEN_NAMES,
                log,
            ) ?: dp(SystemUiVolumeEntryLayout.BUTTON_SIZE_DP)
            val fallbackHeight = resolveNamedDimenPx(
                targetContext,
                packages,
                SystemUiVolumeEntryLayout.BUTTON_HEIGHT_DIMEN_NAMES,
                log,
            ) ?: dp(SystemUiVolumeEntryLayout.BUTTON_SIZE_DP)
            val iconSizePx = resolveNamedDimenPx(
                targetContext,
                packages,
                SystemUiVolumeEntryLayout.ICON_SIZE_DIMEN_NAMES,
                log,
            )
            val moduleContext = targetContext.createPackageContext(
                OverlayOpenRequest.MODULE_PACKAGE,
                Context.CONTEXT_IGNORE_SECURITY,
            )
            val contentDescription = moduleContext.getString(R.string.systemui_volume_entry_content_description)
            entry.id = View.NO_ID
            entry.removeAllViews()
            entry.background = null
            entry.elevation = 0f
            entry.isClickable = true
            entry.isFocusable = true
            entry.contentDescription = contentDescription
            entry.tag = ENTRY_TAG
            val liveRadius = radiusPx ?: (fallbackWidth / 2)
            val mode = chooseMaterial(targetContext)
            // HYPERLIGHT 档优先走「官方按钮克隆」：inflate miui_ringer_mode_layout +
            // 绑官方 RingerButtonHelper，让入口和铃铛/月亮走同一条官方材质链路。
            // HyperLight 拦的就是这条链路 —— 克隆按钮会自动带上一整套玻璃效果
            // （含描边/陀螺仪折射/触控辉光），这是手工调 zf0.l 拿不到的；
            // HyperLight 没装时就是官方材质，观感仍然和铃铛月亮一致。
            if (mode == EntryMaterialMode.HYPERLIGHT_GLASS) {
                val openEntry = View.OnClickListener { clickedView ->
                    if (isClosing()) return@OnClickListener
                    openOverlay(clickedView.context, "click", clickedView)
                }
                val cloned = OfficialRingerClone.create(
                    targetContext = targetContext,
                    packages = packages,
                    pluginClassLoader = pluginClassLoader,
                    officialLayout = officialLayout,
                    iconDrawable = iconDrawable,
                    applyFallbackMaterial = { view ->
                        runCatching {
                            officialBlur?.applyCollapsedChrome(view, liveRadius)
                        }.onFailure { throwable ->
                            log(
                                Log.INFO,
                                TAG,
                                "Official ringer clone fallback material failed",
                                throwable,
                            )
                        }.getOrDefault(false) == true
                    },
                    onClick = openEntry,
                    log = log,
                )
                if (cloned != null) {
                    entry.removeAllViews()
                    entry.addView(
                        cloned,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        ),
                    )
                    // bg_blur 已经在 clone 里被覆盖成同一个监听器；这里再给 root/entry
                    // 兜一层，保证事件无论落在哪一层都只会打开 SoundMan 面板。
                    cloned.setOnClickListener(openEntry)
                    entry.setOnClickListener(openEntry)
                    log(
                        Log.INFO,
                        TAG,
                        "SoundMan entry uses cloned official ringer button; HyperLight glass follows the official path",
                        null,
                    )
                    return true
                }
                log(
                    Log.INFO,
                    TAG,
                    "Official ringer clone unavailable; falling back to manual HyperLight glass",
                    null,
                )
            }
            val blurLayer = officialBlur?.createCollapsedBlurLayer(targetContext, liveRadius)
                ?: View(targetContext)
            blurLayer.id = View.NO_ID
            blurLayer.layoutParams = childLayoutParams(template.bgBlur, fallbackWidth, fallbackHeight)
            applyRoundOutline(blurLayer, template.bgBlur, radiusPx)
            val chrome = FrameLayout(targetContext)
            chrome.id = View.NO_ID
            chrome.isActivated = true
            chrome.isSelected = false
            chrome.layoutParams = childLayoutParams(template.standardBtn, fallbackWidth, fallbackHeight)
            applyRoundOutline(chrome, template.standardBtn, radiusPx)
            val blurBackground = resolveNamedDrawable(
                targetContext,
                packages,
                SystemUiVolumeEntryLayout.BLUR_BACKGROUND_RESOURCE_NAMES,
                log,
                "volume entry blur background",
            )
            val buttonBackground = resolveNamedDrawable(
                targetContext,
                packages,
                SystemUiVolumeEntryLayout.BUTTON_BACKGROUND_RESOURCE_NAMES,
                log,
                "volume entry button background",
            )
            val themeBlur = officialBlur?.themeBlurOpened(targetContext)
            val componentApplied = mode == EntryMaterialMode.COMPONENT &&
                runCatching {
                    componentMaterial?.apply(
                        chrome,
                        liveRadius,
                        EntryMaterialPolicy.isNight(targetContext.resources.configuration.uiMode),
                    ) == true
                }.onFailure { throwable ->
                    log(
                        Log.INFO,
                        TAG,
                        "Official component material unavailable; falling back to ringer chrome",
                        throwable,
                    )
                }.getOrDefault(false)
            val liveApplied = if (componentApplied) {
                false
            } else {
                themeBlur != false && officialBlur != null &&
                    officialBlur.applyCollapsedChrome(chrome, liveRadius)
            }
            if (!componentApplied && themeBlur == true && !liveApplied) {
                log(Log.ERROR, TAG, "Theme live blur is on but official chrome blend failed; skip insertion", null)
                return false
            }
            // 官方组件材质自带完整背景，和 ringer 的「新系统 material」一样不要再叠静态 blur。
            val newMaterial = componentApplied ||
                (liveApplied && officialBlur?.usedNewMaterialChrome() == true)
            blurLayer.background = if (newMaterial) null else blurBackground
            // HyperLight 的渲染器拿 view 当前 background 当底图；铃铛/月亮是官方 View，
            // 本来就带着官方按钮底，我们若跟着置 null，玻璃就只剩一层"悬空"的折射，
            // 观感自然对不上。所以这一档要把官方按钮底留在下面。
            val keepGlassBase = mode == EntryMaterialMode.HYPERLIGHT_GLASS
            if (componentApplied || liveApplied) {
                chrome.background = if (keepGlassBase) buttonBackground else null
                if (componentApplied) {
                    log(Log.INFO, TAG, "Applied volume-column component material to SoundMan entry", null)
                } else {
                    log(
                        Log.INFO,
                        TAG,
                        "Applied official chrome MiBlur; kept ringer blur drawable under it",
                        null
                    )
                }
            } else {
                if (buttonBackground == null && blurBackground == null) {
                    log(Log.ERROR, TAG, "Volume entry official backgrounds missing; skip insertion", null)
                    return false
                }
                chrome.background = buttonBackground
            }
            // HyperLight 的渲染器会把 view 当前 background 当 base 叠在自己下面，
            // 所以必须等上面把 chrome.background 定完再挂；挂不上就退回自研玻璃。
            val hyperLightApplied = mode == EntryMaterialMode.HYPERLIGHT_GLASS &&
                !componentApplied &&
                attachHyperLightGlass(chrome, liveRadius)
            if (hyperLightApplied) {
                log(
                    Log.INFO,
                    TAG,
                    "Applied HyperLight liquid glass to SoundMan entry (base=${chrome.background != null})",
                    null,
                )
            } else if (mode == EntryMaterialMode.HYPERLIGHT_GLASS && !componentApplied) {
                log(Log.INFO, TAG, "HyperLight glass unavailable; falling back to own glass", null)
            }
            // 自研玻璃只在 LIQUID 档叠；HYPERLIGHT 档挂不上时兜底。
            // RINGER / COMPONENT 档不叠，保持模块原本的观感。
            val wantOwnGlass = mode == EntryMaterialMode.LIQUID_GLASS ||
                (mode == EntryMaterialMode.HYPERLIGHT_GLASS && !hyperLightApplied)
            if (!componentApplied && wantOwnGlass && attachLiquidGlass(chrome, liveRadius)) {
                log(Log.INFO, TAG, "Applied builtin-panel liquid glass to SoundMan entry", null)
            }
            chrome.addView(createIconView(targetContext, template.icon, iconDrawable, iconSizePx))
            entry.addView(blurLayer)
            entry.addView(chrome)
            entry.setOnClickListener { clickedView ->
                if (isClosing()) return@setOnClickListener
                openOverlay(clickedView.context, "click", clickedView)
            }
            return true
        }

        private fun createIconView(
            context: Context,
            iconTemplate: View?,
            drawable: Drawable,
            iconSizePx: Int?,
        ): ImageView {
            val imageView = ImageView(context)
            imageView.id = View.NO_ID
            imageView.setImageDrawable(drawable)
            if (iconTemplate is ImageView) {
                imageView.scaleType = iconTemplate.scaleType
                imageView.adjustViewBounds = iconTemplate.adjustViewBounds
                imageView.setPadding(
                    iconTemplate.paddingLeft,
                    iconTemplate.paddingTop,
                    iconTemplate.paddingRight,
                    iconTemplate.paddingBottom,
                )
                val sourceParams = iconTemplate.layoutParams
                imageView.layoutParams = if (sourceParams != null) {
                    FrameLayout.LayoutParams(sourceParams.width, sourceParams.height).apply {
                        gravity = Gravity.CENTER
                        if (sourceParams is ViewGroup.MarginLayoutParams) {
                            setMargins(
                                sourceParams.leftMargin,
                                sourceParams.topMargin,
                                sourceParams.rightMargin,
                                sourceParams.bottomMargin,
                            )
                        }
                    }
                } else {
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER,
                    )
                }
            } else {
                imageView.scaleType = ImageView.ScaleType.CENTER_INSIDE
                val size = iconSizePx ?: ViewGroup.LayoutParams.MATCH_PARENT
                imageView.layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
            }
            return imageView
        }

        private fun childLayoutParams(
            source: View?,
            fallbackWidth: Int?,
            fallbackHeight: Int?,
        ): FrameLayout.LayoutParams {
            val sourceParams = source?.layoutParams
            val width = when {
                source != null && source.measuredWidth > 0 -> source.measuredWidth
                sourceParams != null && sourceParams.width > 0 -> sourceParams.width
                sourceParams != null && sourceParams.width != 0 -> sourceParams.width
                fallbackWidth != null && fallbackWidth > 0 -> fallbackWidth
                else -> ViewGroup.LayoutParams.WRAP_CONTENT
            }
            val height = when {
                source != null && source.measuredHeight > 0 -> source.measuredHeight
                sourceParams != null && sourceParams.height > 0 -> sourceParams.height
                sourceParams != null && sourceParams.height != 0 -> sourceParams.height
                fallbackHeight != null && fallbackHeight > 0 -> fallbackHeight
                else -> ViewGroup.LayoutParams.WRAP_CONTENT
            }
            return FrameLayout.LayoutParams(width, height, Gravity.CENTER).apply {
                if (sourceParams is ViewGroup.MarginLayoutParams) {
                    setMargins(
                        sourceParams.leftMargin,
                        sourceParams.topMargin,
                        sourceParams.rightMargin,
                        sourceParams.bottomMargin,
                    )
                }
            }
        }

        private fun applyRoundOutline(target: View, template: View?, radiusPx: Int?) {
            target.clipToOutline = true
            val templateProvider = template?.outlineProvider
            if (templateProvider != null) {
                target.outlineProvider = templateProvider
                return
            }
            target.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    if (view.width <= 0 || view.height <= 0) {
                        outline.setEmpty()
                        return
                    }
                    if (radiusPx != null) {
                        outline.setRoundRect(0, 0, view.width, view.height, radiusPx.toFloat())
                    } else {
                        outline.setOval(0, 0, view.width, view.height)
                    }
                }
            }
        }

        private fun resolvePhoneIcon(
            context: Context,
            packages: List<String>,
            log: (priority: Int, tag: String, message: String, throwable: Throwable?) -> Unit,
        ): Drawable? {
            val drawable = resolveNamedDrawable(
                context,
                packages,
                SystemUiVolumeEntryLayout.ICON_RESOURCE_NAMES,
                log,
                "volume entry icon",
            )
            if (drawable == null) {
                log(
                    Log.ERROR,
                    TAG,
                    "Volume entry icon not found: ${SystemUiVolumeEntryLayout.ICON_RESOURCE_NAMES} in $packages",
                    null,
                )
            }
            return drawable
        }

        @SuppressLint("UseCompatLoadingForDrawables", "DiscouragedApi")
        private fun resolveNamedDrawable(
            context: Context,
            packages: List<String>,
            names: List<String>,
            log: (priority: Int, tag: String, message: String, throwable: Throwable?) -> Unit,
            purpose: String,
        ): Drawable? {
            names.forEach { name ->
                packages.forEach { packageName ->
                    val id = runCatching {
                        context.resources.getIdentifier(name, "drawable", packageName)
                    }.onFailure {
                        log(Log.ERROR, TAG, "getIdentifier failed name=$name type=drawable package=$packageName", it)
                    }.getOrDefault(0)
                    if (id == 0) return@forEach
                    val drawable = runCatching {
                        context.getDrawable(id)
                    }.onFailure {
                        log(Log.ERROR, TAG, "getDrawable failed name=$name package=$packageName id=$id", it)
                    }.getOrNull()
                    if (drawable != null) {
                        log(Log.INFO, TAG, "Resolved $purpose name=$name package=$packageName", null)
                        return drawable.mutate()
                    }
                }
            }
            return null
        }

        @SuppressLint("DiscouragedApi")
        private fun resolveNamedDimenPx(
            context: Context,
            packages: List<String>,
            names: List<String>,
            log: (priority: Int, tag: String, message: String, throwable: Throwable?) -> Unit,
        ): Int? {
            names.forEach { name ->
                packages.forEach { packageName ->
                    val id = runCatching {
                        context.resources.getIdentifier(name, "dimen", packageName)
                    }.onFailure {
                        log(Log.ERROR, TAG, "getIdentifier failed name=$name type=dimen package=$packageName", it)
                    }.getOrDefault(0)
                    if (id == 0) return@forEach
                    val px = runCatching {
                        context.resources.getDimensionPixelSize(id)
                    }.onFailure {
                        log(Log.ERROR, TAG, "getDimensionPixelSize failed name=$name package=$packageName id=$id", it)
                    }.getOrNull()
                    if (px != null) {
                        log(Log.INFO, TAG, "Resolved dimen name=$name package=$packageName px=$px", null)
                        return px
                    }
                }
            }
            return null
        }

        private fun resolveDndTemplate(
            anchor: View,
            packages: List<String>,
            log: (priority: Int, tag: String, message: String, throwable: Throwable?) -> Unit,
        ): DndTemplate {
            val children = LinkedHashMap<String, View>()
            SystemUiVolumeEntryLayout.DND_CHILD_RESOURCE_NAMES.forEach { name ->
                findViewByIdName(anchor, name, packages, log)?.let { children[name] = it }
            }
            return DndTemplate(
                standardBtn = children["miui_standard_btn"],
                bgBlur = children["bg_blur"],
                icon = children["icon"],
                timerLayout = findViewByIdName(
                    anchor,
                    SystemUiVolumeEntryLayout.TIMER_LAYOUT_RESOURCE_NAME,
                    packages,
                    log,
                ),
            )
        }

        @SuppressLint("DiscouragedApi")
        private fun findViewByIdName(
            scope: View,
            name: String,
            packages: List<String>,
            log: (priority: Int, tag: String, message: String, throwable: Throwable?) -> Unit,
        ): View? {
            packages.forEach { packageName ->
                val id = runCatching {
                    scope.context.resources.getIdentifier(name, "id", packageName)
                }.onFailure {
                    log(Log.ERROR, TAG, "getIdentifier failed name=$name package=$packageName", it)
                }.getOrDefault(0)
                if (id == 0) return@forEach
                val view = scope.findViewById<View>(id)
                if (view != null) return view
            }
            return null
        }

        /**
         * 面板是否展开：DND 的 timer 行可见即展开。
         *
         * 与 [applyInsertVisibility] 共用同一判定，避免两处对「展开」的理解漂移。
         */
        private fun isExpanded(timerLayout: View?): Boolean =
            timerLayout != null && timerLayout.isVisible

        private fun applyInsertVisibility(
            entry: View,
            timerLayout: View?,
            presenceOf: (Context) -> MediaPresence,
            log: (priority: Int, tag: String, message: String, throwable: Throwable?) -> Unit,
        ) {
            val expanded = isExpanded(timerLayout)
            rememberExpanded(entry, expanded)
            val presence = presenceOf(entry.context)
            entry.visibility = EntryPresencePolicy.visibility(expanded, presence)
            when {
                expanded -> log(
                    Log.INFO,
                    TAG,
                    "Volume entry hidden because DND timer_layout is already visible",
                    null,
                )
                presence.hidesEntry -> log(
                    Log.INFO,
                    TAG,
                    "Volume entry hidden because no media app is playing (presence=$presence)",
                    null,
                )
            }
        }

        /**
         * 每颗入口最近一次官方展开态。
         *
         * 播放回调刷新可见性时手上没有官方 `timer_layout`，只能沿用上一次
         * `updateExpandedH` / 插入时的展开态；弱引用跟着入口一起回收。
         */
        private val expandedStates = WeakHashMap<View, Boolean>()

        private fun rememberExpanded(entry: View, expanded: Boolean) {
            expandedStates[entry] = expanded
        }

        private fun expandedOf(entry: View): Boolean = expandedStates[entry] ?: false

        private fun copyMetrics(
            sizeSource: View,
            marginSource: View,
            dp: (Int) -> Int,
            dimenWidth: Int?,
            dimenHeight: Int?,
        ): CopiedMetrics {
            val spec = SystemUiVolumeEntryLayout.circularButtonSpec()
            val fallback = dp(spec.sizeDp)
            val fallbackWidth = dimenWidth ?: fallback
            val fallbackHeight = dimenHeight ?: fallback
            val fallbackMargin = dp(spec.marginVerticalDp)
            val sizeLp = sizeSource.layoutParams
            val width = when {
                sizeSource.measuredWidth > 0 -> sizeSource.measuredWidth
                sizeLp != null && sizeLp.width > 0 -> sizeLp.width
                else -> fallbackWidth
            }
            val height = when {
                sizeSource.measuredHeight > 0 -> sizeSource.measuredHeight
                sizeLp != null && sizeLp.height > 0 -> sizeLp.height
                else -> fallbackHeight
            }
            val marginLp = (marginSource.layoutParams as? ViewGroup.MarginLayoutParams)
                ?: (sizeLp as? ViewGroup.MarginLayoutParams)
            val gravitySource = sizeLp ?: marginSource.layoutParams
            val gravity = when (gravitySource) {
                is LinearLayout.LayoutParams -> gravitySource.gravity
                is FrameLayout.LayoutParams -> gravitySource.gravity
                else -> Gravity.CENTER_HORIZONTAL
            }.let { resolved ->
                if (resolved == Gravity.NO_GRAVITY) Gravity.CENTER_HORIZONTAL else resolved
            }
            return CopiedMetrics(
                width = width,
                height = height,
                leftMargin = marginLp?.leftMargin ?: 0,
                topMargin = marginLp?.topMargin ?: fallbackMargin,
                rightMargin = marginLp?.rightMargin ?: 0,
                bottomMargin = marginLp?.bottomMargin ?: fallbackMargin,
                gravity = gravity,
            )
        }

        private fun resolvePlacement(
            root: View,
            anchor: View,
            metrics: CopiedMetrics,
            gap: Int,
            position: EntryPosition,
            log: (priority: Int, tag: String, message: String, throwable: Throwable?) -> Unit,
        ): EntryPlacement? {
            val parent = anchor.parent as? ViewGroup
            if (parent == null) {
                log(Log.ERROR, TAG, "Volume column parent is not a ViewGroup: ${anchor.javaClass.name}", null)
                return null
            }
            val dialogBound = resolveDialogBound(root)
            if (!isWithinBound(parent, dialogBound)) {
                return failVisible(
                    parent,
                    log,
                    "volume column parent is outside $VOLUME_DIALOG_VIEW_CLASS",
                )
            }
            val entryWidth = alignEntryWidth(anchor)
            return when (parent) {
                is LinearLayout -> when (parent.orientation) {
                    LinearLayout.VERTICAL -> verticalPlacement(parent, anchor, metrics, entryWidth, gap, position)
                    LinearLayout.HORIZONTAL -> outerVerticalPlacement(
                        root,
                        parent,
                        anchor,
                        metrics,
                        entryWidth,
                        gap,
                        position,
                        log,
                        "horizontal LinearLayout",
                    )
                    else -> failVisible(parent, log, "LinearLayout has unsupported orientation")
                }
                is FrameLayout -> framePlacement(parent, anchor, metrics, entryWidth, gap, position)
                    ?: outerVerticalPlacement(
                        root,
                        parent,
                        anchor,
                        metrics,
                        entryWidth,
                        gap,
                        position,
                        log,
                        "FrameLayout cannot place entry $position volume column",
                    )
                    ?: clampedFramePlacement(parent, metrics, entryWidth, gap)
                else -> failVisible(parent, log, "unsupported volume column parent")
            }
        }

        private fun verticalPlacement(
            parent: LinearLayout,
            insertBeside: View,
            metrics: CopiedMetrics,
            entryWidth: Int,
            gap: Int,
            position: EntryPosition,
        ): EntryPlacement {
            val margins = EntryPlacementPolicy.verticalMargins(gap, position)
            val params = LinearLayout.LayoutParams(entryWidth, metrics.height).apply {
                weight = 0f
                gravity = volumeRowGravity(insertBeside)
                setMargins(0, margins.top, 0, margins.bottom)
            }
            val anchorIndex = parent.indexOfChild(insertBeside)
            val index = EntryPlacementPolicy.insertIndex(anchorIndex, position)
            return EntryPlacement(parent, index, params)
        }

        private fun outerVerticalPlacement(
            root: View,
            originalParent: ViewGroup,
            volumeAnchor: View,
            metrics: CopiedMetrics,
            entryWidth: Int,
            gap: Int,
            position: EntryPosition,
            log: (priority: Int, tag: String, message: String, throwable: Throwable?) -> Unit,
            reason: String,
        ): EntryPlacement? {
            val dialogBound = resolveDialogBound(root)
            var row: View = originalParent
            var ancestor = originalParent.parent
            while (ancestor is ViewGroup && isWithinBound(ancestor, dialogBound)) {
                if (ancestor is LinearLayout && ancestor.orientation == LinearLayout.VERTICAL) {
                    return verticalPlacement(
                        ancestor,
                        row,
                        metrics,
                        alignEntryWidth(row),
                        gap,
                        position,
                    )
                }
                if (ancestor === dialogBound) break
                row = ancestor
                ancestor = ancestor.parent
            }
            return failVisible(
                originalParent,
                log,
                "$reason has no vertical container within $VOLUME_DIALOG_VIEW_CLASS for ${volumeAnchor.javaClass.name}",
            )
        }

        private fun isWithinRoot(view: View, root: View): Boolean = isWithinBound(view, root)

        private fun isWithinBound(view: View, bound: View): Boolean {
            var current: View? = view
            while (current != null) {
                if (current === bound) return true
                current = current.parent as? View
            }
            return false
        }

        private fun resolveDialogBound(ringerRoot: View): View {
            var current: View = ringerRoot
            while (true) {
                if (current.javaClass.name == VOLUME_DIALOG_VIEW_CLASS) return current
                current = current.parent as? View ?: return current
            }
        }

        private fun findExistingEntry(root: View): View? {
            resolveDialogBound(root).findViewWithTag<View>(ENTRY_TAG)?.let { return it }
            return null
        }

        private fun findStyleTemplate(
            ringerRoot: View,
            packages: List<String>,
            log: (priority: Int, tag: String, message: String, throwable: Throwable?) -> Unit,
        ): View? {
            SystemUiVolumeEntryLayout.STYLE_TEMPLATE_RESOURCE_NAMES.forEach { name ->
                val view = findViewByIdName(ringerRoot, name, packages, log)
                if (view != null) {
                    log(Log.INFO, TAG, "Found style template id=$name view=${view.javaClass.name}", null)
                    return view
                }
            }
            log(Log.WARN, TAG, "Style template dnd_layout/ringer_layout not found; using official dimen sizes", null)
            return null
        }

        private fun resolveButtonMetrics(
            template: DndTemplate,
            dp: (Int) -> Int,
            dimenWidth: Int?,
            dimenHeight: Int?,
        ): CopiedMetrics {
            val sizeSource = template.standardBtn ?: template.bgBlur
            val fallback = dp(SystemUiVolumeEntryLayout.BUTTON_SIZE_DP)
            if (sizeSource != null) {
                return copyMetrics(sizeSource, sizeSource, dp, dimenWidth, dimenHeight).copy(
                    leftMargin = 0,
                    topMargin = 0,
                    rightMargin = 0,
                    bottomMargin = 0,
                    gravity = Gravity.CENTER_HORIZONTAL,
                )
            }
            return CopiedMetrics(
                width = dimenWidth ?: fallback,
                height = dimenHeight ?: fallback,
                leftMargin = 0,
                topMargin = 0,
                rightMargin = 0,
                bottomMargin = 0,
                gravity = Gravity.CENTER_HORIZONTAL,
            )
        }

        /**
         * 官方「音量条 ↔ 静音/免打扰」之间的间距。
         *
         * ⚠️ 实测值**只能在入口还没插进去时采信**：入口一旦插入就会把这一对视图撑开，
         * 再实测得到的是「入口高度 + 上一轮间距」，会被下一轮继续放大 ——
         * 表现就是每呼出一次音量条，入口连带静音/免打扰按钮离音量条主体远一截。
         * 取值顺序与缓存策略见 [EntryPlacementPolicy.resolveGap]。
         *
         * @param entryPresent 入口此刻是否已插在视图树里
         * @param collapsed 面板是否折叠；展开态量出来的间距不适用于折叠态
         * @param maxPx 实测值的合理上限
         */
        private fun resolveOfficialGap(
            ringerRoot: View,
            volumeAnchor: View,
            fallbackPx: Int,
            entryPresent: Boolean,
            collapsed: Boolean,
            maxPx: Int,
        ): OfficialGapResolution {
            val ringerMargin =
                (ringerRoot.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0
            val volumeMargin =
                (volumeAnchor.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
            val resolution = EntryPlacementPolicy.resolveGap(
                officialMargin = if (ringerMargin > 0) ringerMargin else volumeMargin,
                cached = measuredGaps[ringerRoot],
                measured = measureGap(ringerRoot, volumeAnchor),
                entryPresent = entryPresent,
                collapsed = collapsed,
                fallback = fallbackPx,
                maxPx = maxPx,
            )
            if (resolution.cacheable) {
                measuredGaps[ringerRoot] = resolution.px
            }
            return resolution
        }

        /** 实测静音/免打扰与音量条之间的距离；布局未完成时返回 null。 */
        private fun measureGap(ringerRoot: View, volumeAnchor: View): Int? {
            if (!ringerRoot.isLaidOut || !volumeAnchor.isLaidOut) return null
            if (ringerRoot.parent === volumeAnchor.parent) {
                return kotlin.math.abs(ringerRoot.top - volumeAnchor.bottom)
            }
            if (ringerRoot.isAttachedToWindow && volumeAnchor.isAttachedToWindow) {
                val ringerLoc = IntArray(2)
                val volumeLoc = IntArray(2)
                ringerRoot.getLocationOnScreen(ringerLoc)
                volumeAnchor.getLocationOnScreen(volumeLoc)
                return kotlin.math.abs(ringerLoc[1] - (volumeLoc[1] + volumeAnchor.height))
            }
            return null
        }

        private fun alignEntryWidth(volumeAnchor: View): Int {
            val layoutParams = volumeAnchor.layoutParams
            return when {
                volumeAnchor.measuredWidth > 0 -> volumeAnchor.measuredWidth
                layoutParams != null && layoutParams.width > 0 -> layoutParams.width
                layoutParams != null && layoutParams.width == ViewGroup.LayoutParams.MATCH_PARENT ->
                    ViewGroup.LayoutParams.MATCH_PARENT
                else -> ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }

        private fun volumeRowGravity(volumeAnchor: View): Int {
            val layoutParams = volumeAnchor.layoutParams
            val gravity = when (layoutParams) {
                is LinearLayout.LayoutParams -> layoutParams.gravity
                is FrameLayout.LayoutParams -> layoutParams.gravity
                else -> Gravity.CENTER_HORIZONTAL
            }
            return if (gravity == Gravity.NO_GRAVITY) Gravity.CENTER_HORIZONTAL else gravity
        }

        private fun framePlacement(
            parent: FrameLayout,
            anchor: View,
            metrics: CopiedMetrics,
            entryWidth: Int,
            gap: Int,
            position: EntryPosition,
        ): EntryPlacement? {
            val topMargin = EntryPlacementPolicy.frameTopMargin(
                anchorTop = anchor.top,
                anchorBottom = anchor.bottom,
                entryHeight = metrics.height,
                gap = gap,
                position = position,
            )
            if (topMargin < 0) return null
            // 放不下就别硬放：绝对定位一旦超出父容器，wrap_content 父容器会被撑高，
            // 而 MATCH_PARENT 的锚点会跟着变高，下一轮按 anchor.bottom 定位又会更往下
            // （实测每轮 +（入口高+间距））。交给 outerVerticalPlacement 插成一整行。
            if (!EntryPlacementPolicy.fitsInsideParent(topMargin, metrics.height, parent.height)) {
                return null
            }
            val params = FrameLayout.LayoutParams(entryWidth, metrics.height).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                this.topMargin = topMargin
            }
            return EntryPlacement(parent, parent.indexOfChild(anchor), params)
        }

        /**
         * 兜底落位：既放不进父容器、又找不到外层纵向容器时，贴着父容器底部放。
         *
         * 宁可和官方按钮重叠，也绝不能把父容器撑高（那会逐轮累加），更不能干脆不插。
         *
         * @param parent 音量条锚点的直接父容器
         * @param metrics 入口尺寸
         * @param entryWidth 入口宽度
         * @param gap 官方间距，用作离底边的留白
         */
        private fun clampedFramePlacement(
            parent: FrameLayout,
            metrics: CopiedMetrics,
            entryWidth: Int,
            gap: Int,
        ): EntryPlacement {
            val topMargin = (parent.height - metrics.height - gap).coerceAtLeast(0)
            val params = FrameLayout.LayoutParams(entryWidth, metrics.height).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                this.topMargin = topMargin
            }
            return EntryPlacement(parent, parent.childCount, params)
        }

        private fun failVisible(
            parent: ViewGroup,
            log: (priority: Int, tag: String, message: String, throwable: Throwable?) -> Unit,
            reason: String,
        ): EntryPlacement? {
            log(Log.ERROR, TAG, "SoundMan volume entry not inserted: $reason; parent=${parent.javaClass.name}", null)
            return null
        }

        private data class EntryPlacement(
            val parent: ViewGroup,
            val index: Int,
            val layoutParams: ViewGroup.LayoutParams,
        )

        private data class DndTemplate(
            val standardBtn: View?,
            val bgBlur: View?,
            val icon: View?,
            val timerLayout: View?,
        )

        private data class CopiedMetrics(
            val width: Int,
            val height: Int,
            val leftMargin: Int,
            val topMargin: Int,
            val rightMargin: Int,
            val bottomMargin: Int,
            val gravity: Int,
        )
    }
}

private class SystemUiOfficialDismissHookBridge(
    private val log: (priority: Int, tag: String, message: String, throwable: Throwable?) -> Unit,
) {
    private var controller = WeakReference<Any>(null)
    private var dismiss: ((Any) -> Unit)? = null

    fun capture(owner: Any, action: (Any) -> Unit) {
        controller = WeakReference(owner)
        dismiss = action
    }

    fun dismiss(): Boolean {
        val owner = controller.get()
        val action = dismiss
        if (owner == null || action == null) {
            log(Log.ERROR, TAG, "Official dismiss hook has no live VolumePanelViewController", null)
            return false
        }
        return try {
            action(owner)
            true
        } catch (throwable: Throwable) {
            log(Log.ERROR, TAG, "Official dismiss hook controller callback failed", throwable)
            false
        }
    }

    /**
     * 通过反射调用官方 VolumePanelViewController.rescheduleTimeoutH()，
     * 重置官方自动收回超时计时器。
     *
     * 动机：独立面板接管了用户交互（调音量、拖滑块等），但官方控制器的超时
     * 仍在运行且不会被用户交互重置。如果不转发用户活动，原始超时会按时触发
     * dismissH(TIMEOUT)，导致独立面板比官方展开面板更早被收回。
     *
     * 官方展开面板（mExpanded=true）的超时为 Constant.MAX_STR_LENGTH=5000ms，
     * 折叠面板的超时为 DIALOG_TIMEOUT_MILLIS（更短）。
     * 独立面板在展开状态下挂载，但官方控制器的 mExpanded 可能未被正确设置，
     * 导致 rescheduleTimeoutH 用了短超时。因此调用前先确保 mExpanded=true。
     *
     * rescheduleTimeoutH 和 mExpanded 都是 VolumePanelViewController 的 private 成员，
     * 无法通过公开 API 访问；已确认 VolumePanelViewController 实例已被 showH hook 捕获，
     * 此处通过反射操作。
     */
    fun rescheduleTimeout(): Boolean {
        val owner = controller.get()
        if (owner == null) {
            log(
                Log.ERROR,
                TAG,
                "Official rescheduleTimeout has no live VolumePanelViewController",
                null
            )
            return false
        }
        return try {
            val resolved = owner.javaClass.resolve().optional(silent = true)
            // 确保 mExpanded=true，使 computeTimeoutH() 返回展开状态的长超时（5000ms）
            // 而非折叠状态的短超时。
            val expandedField = resolved.firstFieldOrNull { name = "mExpanded" }
                ?: error("mExpanded field not found on ${owner.javaClass.name}")
            expandedField.of(owner)
            if (expandedField.getQuietly<Boolean>() != true) {
                expandedField.setQuietly(true)
            }
            val method = resolved.firstMethodOrNull { name = "rescheduleTimeoutH" }
                ?: error("rescheduleTimeoutH method not found on ${owner.javaClass.name}")
            method.of(owner)
            method.invokeQuietly()
            true
        } catch (throwable: Throwable) {
            log(Log.ERROR, TAG, "Official rescheduleTimeoutH failed", throwable)
            false
        }
    }

    companion object {
        private const val TAG = "SoundMan.SystemUiDismiss"
    }
}

