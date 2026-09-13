package hk.uwu.soundman.hook.scopes.systemui.runtime

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Outline
import android.graphics.drawable.Drawable
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
import hk.uwu.soundman.hook.scopes.systemui.hidden.OfficialRingerBlur
import hk.uwu.soundman.overlay.OverlayOpenRequest
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
) {
    private val officialDismissHook = SystemUiOfficialDismissHookBridge(log)
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
    private var pluginClassLoader: ClassLoader? = null

    /**
     * 插件 ClassLoader 就绪后安装 live MiBlur 入口。
     *
     * `MiBlurCompat` / `Util` 只在插件 ClassLoader 里。
     */
    fun attachPluginClassLoader(pluginClassLoader: ClassLoader) {
        this.pluginClassLoader = pluginClassLoader
        officialBlur = OfficialRingerBlur(pluginClassLoader, log)
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
        if (view is ImageView) {
            view.setImageDrawable(null)
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                clearVisuals(view.getChildAt(index))
            }
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
     * 从 SystemUI/插件进程打开 SoundMan 面板，并关掉音量侧栏。
     */
    fun openOverlay(context: Context, trigger: String, sourceView: View) {
        if (closing.get()) return
        val launch = OverlayOpenRequest.sidebarActivityLaunch()
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
                entry.visibility = SystemUiVolumeEntryLayout.entryVisibility(expanded)
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
                        pluginClassLoader,
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
            val entryMargin =
                (entry.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
            val extra = entry.measuredHeight + entryMargin
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
            pluginClassLoader: ClassLoader?,
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
            val gap = resolveOfficialGap(root, anchor, dp(SystemUiVolumeEntryLayout.MARGIN_VERTICAL_DP))
            val placement = resolvePlacement(root, anchor, metrics, gap, log) ?: return
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
                applyInsertVisibility(entry, template.timerLayout, log)
                if (!track(entry, uiLooper)) {
                    cleanup(entry)
                    return
                }
                installDragFollow(entry, root, pluginClassLoader, log)
                installCenteringFollow(entry, root, log)
                entry.tag = ENTRY_TAG
                val action = if (existing === entry) "adopted" else "inserted"
                log(
                    Log.INFO,
                    TAG,
                    "[systemui] $trigger $action SoundMan entry view=${describeInserted(entry)} " +
                        "anchor=$anchorName parent=${placement.parent.javaClass.name} index=$insertIndex " +
                        "size=${placement.layoutParams.width}x${placement.layoutParams.height}",
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

        private fun configureEntry(
            entry: FrameLayout,
            targetContext: Context,
            template: DndTemplate,
            packages: List<String>,
            log: (priority: Int, tag: String, message: String, throwable: Throwable?) -> Unit,
            isClosing: () -> Boolean,
            openOverlay: (Context, String, View) -> Unit,
            officialBlur: OfficialRingerBlur?,
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
            val liveApplied = themeBlur != false && officialBlur != null &&
                officialBlur.applyCollapsedChrome(chrome, liveRadius)
            if (themeBlur == true && !liveApplied) {
                log(Log.ERROR, TAG, "Theme live blur is on but official chrome blend failed; skip insertion", null)
                return false
            }
            val newMaterial = liveApplied && officialBlur.usedNewMaterialChrome() == true
            blurLayer.background = if (newMaterial) null else blurBackground
            if (liveApplied) {
                chrome.background = null
                if (newMaterial) {
                    log(Log.INFO, TAG, "Applied official volume-column material chrome", null)
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

        private fun applyInsertVisibility(
            entry: View,
            timerLayout: View?,
            log: (priority: Int, tag: String, message: String, throwable: Throwable?) -> Unit,
        ) {
            val expanded = timerLayout != null && timerLayout.isVisible
            entry.visibility = SystemUiVolumeEntryLayout.entryVisibility(expanded)
            if (expanded) {
                log(Log.INFO, TAG, "Volume entry hidden because DND timer_layout is already visible", null)
            }
        }

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
                    LinearLayout.VERTICAL -> verticalPlacement(parent, anchor, metrics, entryWidth, gap)
                    LinearLayout.HORIZONTAL -> outerVerticalPlacement(
                        root,
                        parent,
                        anchor,
                        metrics,
                        entryWidth,
                        gap,
                        log,
                        "horizontal LinearLayout",
                    )
                    else -> failVisible(parent, log, "LinearLayout has unsupported orientation")
                }
                is FrameLayout -> framePlacement(parent, anchor, metrics, entryWidth, gap)
                    ?: outerVerticalPlacement(
                        root,
                        parent,
                        anchor,
                        metrics,
                        entryWidth,
                        gap,
                        log,
                        "FrameLayout cannot place entry above volume column",
                    )
                else -> failVisible(parent, log, "unsupported volume column parent")
            }
        }

        private fun verticalPlacement(
            parent: LinearLayout,
            insertBefore: View,
            metrics: CopiedMetrics,
            entryWidth: Int,
            gap: Int,
        ): EntryPlacement {
            val params = LinearLayout.LayoutParams(entryWidth, metrics.height).apply {
                weight = 0f
                gravity = volumeRowGravity(insertBefore)
                setMargins(0, 0, 0, gap)
            }
            return EntryPlacement(parent, parent.indexOfChild(insertBefore), params)
        }

        private fun outerVerticalPlacement(
            root: View,
            originalParent: ViewGroup,
            volumeAnchor: View,
            metrics: CopiedMetrics,
            entryWidth: Int,
            gap: Int,
            log: (priority: Int, tag: String, message: String, throwable: Throwable?) -> Unit,
            reason: String,
        ): EntryPlacement? {
            val dialogBound = resolveDialogBound(root)
            var row: View = originalParent
            var ancestor = originalParent.parent
            while (ancestor is ViewGroup && isWithinBound(ancestor, dialogBound)) {
                if (ancestor is LinearLayout && ancestor.orientation == LinearLayout.VERTICAL) {
                    return verticalPlacement(ancestor, row, metrics, alignEntryWidth(row), gap)
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

        private fun resolveOfficialGap(ringerRoot: View, volumeAnchor: View, fallbackPx: Int): Int {
            val ringerMargin = (ringerRoot.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0
            if (ringerMargin > 0) return ringerMargin
            val volumeMargin = (volumeAnchor.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
            if (volumeMargin > 0) return volumeMargin
            if (ringerRoot.isLaidOut && volumeAnchor.isLaidOut) {
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
            }
            return fallbackPx
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
        ): EntryPlacement? {
            val topMargin = anchor.top - gap - metrics.height
            if (topMargin < 0) return null
            val params = FrameLayout.LayoutParams(entryWidth, metrics.height).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                this.topMargin = topMargin
            }
            return EntryPlacement(parent, parent.indexOfChild(anchor), params)
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

