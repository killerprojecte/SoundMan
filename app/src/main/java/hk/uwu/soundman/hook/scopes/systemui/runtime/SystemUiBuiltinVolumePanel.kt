package hk.uwu.soundman.hook.scopes.systemui.runtime

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.makeAccessible
import com.highcapable.kavaref.extension.toClass
import hk.uwu.soundman.R
import hk.uwu.soundman.data.AudioDeviceScan
import hk.uwu.soundman.data.PanelPlaybackRow
import hk.uwu.soundman.data.PanelPlaybackSnapshot
import hk.uwu.soundman.data.PanelPlaybackStatus
import hk.uwu.soundman.data.ProviderPanelPlayback
import hk.uwu.soundman.hook.scopes.systemui.hidden.OfficialExpandedMaterial
import hk.uwu.soundman.model.AudioOutputDevice
import hk.uwu.soundman.model.OutputDeviceType
import hk.uwu.soundman.model.OutputTarget
import hk.uwu.soundman.overlay.OverlayOpenRequest
import hk.uwu.soundman.ui.DevicePageRow
import hk.uwu.soundman.ui.DevicePageRowKind
import hk.uwu.soundman.ui.DevicePageRows
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** 在 MiuiVolumeDialogView 旁挂载独立应用音量页，不触碰官方展开状态或内部 View 树。 */
class SystemUiBuiltinVolumePanel(
    private val log: (priority: Int, tag: String, message: String, throwable: Throwable?) -> Unit,
    private val hookDismiss: () -> Boolean,
    private val rescheduleOfficialTimeout: () -> Boolean,
    private val hideSystemAppsEnabled: () -> Boolean = { false },
) {
    fun closeFor(sourceView: View) {
        try {
            val dialog = findDialog(sourceView) ?: return
            synchronized(sessions) { sessions[dialog] }?.close("entry cleanup")
        } catch (throwable: Throwable) {
            log(Log.ERROR, TAG, "Independent panel close boundary failed", throwable)
        }
    }

    /**
     * 官方 VolumePanelViewController 已经进入 dismissH 时调用。
     *
     * 锁屏、系统窗口状态变化等场景不会经过 SoundMan 的关闭按钮，因此必须清理自建 host；
     * session 使用无官方回调的终止路径，绝不能从这里再次调用 dismissH。
     */
    fun closeForOfficialDismiss(reason: Int) {
        val activeSessions = synchronized(sessions) { sessions.values.toSet() }
        activeSessions.forEach { session ->
            try {
                session.closeForOfficialDismiss(reason)
            } catch (throwable: Throwable) {
                log(
                    Log.ERROR,
                    TAG,
                    "Independent panel official-dismiss cleanup failed reason=$reason",
                    throwable,
                )
            }
        }
    }

    fun mount(sourceView: View, openOverlay: () -> Unit): Boolean {
        val dialog = findDialog(sourceView) ?: run {
            log(
                Log.ERROR,
                TAG,
                "Independent panel mount failed: MiuiVolumeDialogView not found",
                null
            )
            return false
        }
        synchronized(sessions) { sessions[dialog]?.let { return true } }
        var session: Session? = null
        return try {
            val targetContext = dialog.context
            val created = Session(
                dialog = dialog,
                targetContext = targetContext,
                moduleContext = ModuleApplicationContext(
                    targetContext.createPackageContext(
                        OverlayOpenRequest.MODULE_PACKAGE,
                        Context.CONTEXT_IGNORE_SECURITY,
                    ),
                ),
                openOverlay = openOverlay,
                log = log,
                hookDismiss = hookDismiss,
                rescheduleOfficialTimeout = rescheduleOfficialTimeout,
                hideSystemAppsEnabled = hideSystemAppsEnabled,
                onClosed = { closedSession ->
                    synchronized(sessions) {
                        if (sessions[dialog] === closedSession) sessions.remove(dialog)
                    }
                },
            )
            session = created
            created.mount()
            synchronized(sessions) { sessions[dialog] = created }
            true
        } catch (throwable: Throwable) {
            try {
                session?.closeImmediately("mount failure")
            } catch (cleanupError: Throwable) {
                log(Log.ERROR, TAG, "Independent panel mount cleanup failed", cleanupError)
            }
            log(
                Log.ERROR,
                TAG,
                "Independent panel mount failed; falling back to overlay",
                throwable
            )
            false
        }
    }

    private fun findDialog(source: View): ViewGroup? {
        var current: View? = source
        while (current != null) {
            if (current.javaClass.name == VOLUME_DIALOG_VIEW_CLASS) return current as? ViewGroup
            current = current.parent as? View
        }
        return null
    }

    private class ModuleApplicationContext(base: Context) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
    }

    private class Session(
        private val dialog: ViewGroup,
        private val targetContext: Context,
        private val moduleContext: Context,
        private val openOverlay: () -> Unit,
        private val log: (priority: Int, tag: String, message: String, throwable: Throwable?) -> Unit,
        private val hookDismiss: () -> Boolean,
        private val rescheduleOfficialTimeout: () -> Boolean,
        private val hideSystemAppsEnabled: () -> Boolean,
        private val onClosed: (Session) -> Unit,
    ) {
        private val closed = AtomicBoolean(false)
        private val closeFinalized = AtomicBoolean(false)
        private val fallbackRequested = AtomicBoolean(false)
        private val generation = AtomicLong(0L)

        // 独立 UI 线程调度器：dismissH completion 超时兜底必须走主线程，避免依赖
        // dialog 的 AttachInfo.Handler（dialog 若已 detach，postDelayed 不会执行）。
        private val uiHandler = Handler(Looper.getMainLooper())
        private val executor: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "SoundMan.PanelBridge").apply { isDaemon = true }
            }
        private val panelBridge = ProviderPanelPlayback(targetContext)
        private val deviceRows = DevicePageRows()
        private val originalVisibility = dialog.visibility
        private val originalAlpha = dialog.alpha
        private val originalImportantForAccessibility = dialog.importantForAccessibility
        private val officialColumns = ArrayList<OfficialVolumeColumn>()
        private val trackingUids = HashSet<Int>()
        private val volumeSubmissionLock = Any()
        private val pendingVolumeSubmissions = HashMap<Int, PendingVolumeSubmission>()
        private val drainingVolumeUids = HashSet<Int>()
        private val appVisualCache = HashMap<String, Pair<String, Drawable>>()
        private val pluginDrawableIdCache = HashMap<String, Int>()
        private var expandedPanelContentInsetPx: Int? = null
        private lateinit var windowRoot: ViewGroup
        private lateinit var host: FrameLayout
        private lateinit var panel: FrameLayout
        private lateinit var pageHost: FrameLayout
        private lateinit var animationSpec: SystemUiIndependentPanelAnimationSpec
        private lateinit var foldedRect: SystemUiPanelRect
        private lateinit var pluginClassLoader: ClassLoader
        private lateinit var expandedMaterial: OfficialExpandedMaterial
        private val touchInsets = OfficialTouchInsetsRegistration(dialog, log)
        private var morphAnimator: ValueAnimator? = null
        private var resizeAnimator: ValueAnimator? = null
        private val officialDismissCompletion = SystemUiOfficialDismissCompletionGate()
        private var officialShadowSuppression: OfficialShadowSuppression? = null
        private var slideAwayAnimator: ValueAnimator? = null
        private var animationFraction = 0f
        private var openAnimationStarted = false
        private var currentPage: View? = null
        private var activeAppsPage: AppsPageBuild? = null
        private var renderedAppPackages = emptySet<String>()
        private var appsRenderGeneration = 0L
        private var selectedPackage: String? = null
        private var lastSnapshot: PanelPlaybackSnapshot? = null
        private var lastFingerprint: SystemUiPanelSnapshotFingerprint? = null
        private var fallbackColumnWidth = 0
        private var fallbackColumnHeight = 0

        // 官方 miuix.animation.utils.SpringInterpolator 实例，通过 pluginClassLoader 反射创建。
        // 官方实现内部自动计算 spring 收束时间（solveDuration），getDuration() 返回自然 duration。
        private var hideSpringInterpolator: android.animation.TimeInterpolator =
            PathInterpolator(0.2f, 0f, 0f, 1f)
        private var hideSpringDurationMillis = HIDE_SLIDE_ANIMATION_DURATION_FALLBACK_MILLIS
        private var columnEnterSpringInterpolator: android.animation.TimeInterpolator =
            PathInterpolator(0.2f, 0f, 0f, 1f)
        private var columnEnterSpringDurationMillis = COLUMN_TRANSITION_DURATION_FALLBACK_MILLIS
        private var columnExitSpringInterpolator: android.animation.TimeInterpolator =
            PathInterpolator(0.2f, 0f, 0f, 1f)
        private var columnExitSpringDurationMillis = COLUMN_TRANSITION_DURATION_FALLBACK_MILLIS

        fun mount() {
            check(!closed.get()) { "Cannot mount a closed independent panel" }
            windowRoot =
                dialog.rootView as? ViewGroup ?: error("Volume window root is not a ViewGroup")
            check(windowRoot.findViewWithTag<View>(HOST_TAG) == null) { "Independent full-window host already exists" }
            pluginClassLoader = dialog.javaClass.classLoader
                ?: error("MiuiVolumeDialogView has no plugin ClassLoader")

            // 反射创建官方 miuix.animation.utils.SpringInterpolator，复刻官方 Folme spring 物理曲线。
            // 官方实现内部自动计算 spring 收束时间，无需手写 duration。
            initSpringInterpolators()

            val foldedWidth =
                dimension(dialog.width, dialog.measuredWidth, dialog.layoutParams?.width)
            val foldedHeight =
                dimension(dialog.height, dialog.measuredHeight, dialog.layoutParams?.height)
            val dialogLocation = IntArray(2).also(dialog::getLocationOnScreen)
            val rootLocation = IntArray(2).also(windowRoot::getLocationOnScreen)
            foldedRect = SystemUiPanelRect(
                left = dialogLocation[0] - rootLocation[0],
                top = dialogLocation[1] - rootLocation[1],
                right = dialogLocation[0] - rootLocation[0] + foldedWidth,
                bottom = dialogLocation[1] - rootLocation[1] + foldedHeight,
            )
            fallbackColumnWidth = (foldedWidth - dp(PANEL_HORIZONTAL_PADDING_DP) * 2)
                .coerceIn(dp(MIN_COLUMN_WIDTH_DP), dp(MAX_COLUMN_WIDTH_DP))
            fallbackColumnHeight = (foldedHeight - dp(PANEL_VERTICAL_PADDING_DP) * 2)
                .coerceAtLeast(dp(MIN_COLUMN_HEIGHT_DP))

            expandedMaterial = OfficialExpandedMaterial(pluginClassLoader, targetContext, log)
            host = buildFullWindowHost()
            panel = buildPanel()
            expandedMaterial.applyOutline(panel)
            panel.alpha = 0f
            panel.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    try {
                        expandedMaterial.apply(panel)
                    } catch (throwable: Throwable) {
                        log(
                            Log.ERROR,
                            TAG,
                            "Official expanded material failed after panel attach",
                            throwable
                        )
                        closeImmediately("material failure")
                    }
                }

                override fun onViewDetachedFromWindow(v: View) {
                    if (!closed.get()) closeImmediately("panel detached")
                }
            })
            host.addView(
                panel,
                FrameLayout.LayoutParams(
                    foldedRect.width,
                    foldedRect.height,
                    Gravity.TOP or Gravity.START
                ).apply {
                    leftMargin = foldedRect.left
                    topMargin = foldedRect.top
                },
            )
            touchInsets.pause()
            windowRoot.addView(
                host, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            )
            // 官方 dismissH 以 mVolumeView.isShown() 作为状态机前置条件；不能将 dialog 设为
            // INVISIBLE，否则 timeout/screen-off 会直接 return，mShowing 不会复位、官方窗口也不会关闭。
            // 用 alpha=0 保持它对状态机可见，自建全窗口 host 覆盖实际视觉和触摸。
            dialog.alpha = 0f
            dialog.visibility = View.VISIBLE
            check(
                SystemUiOfficialDismissGate.accepts(
                    controllerShowing = true,
                    needsDialog = true,
                    dialogShown = dialog.isShown,
                )
            ) { "Independent panel must keep official dialog shown for dismissH" }
            // 官方 shadow 是 dialog 的独立 sibling；必须在自建面板开始显示前一起隐藏。
            // 否则面板关闭滑出时会先露出仍可见的原版 shadow，形成用户看到的闪帧。
            suppressOfficialShadow()
            dialog.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            // 面板挂载时立即重置官方超时，避免挂载前遗留的短超时导致面板刚展开就被收回。
            rescheduleOfficialDismissTimeout()
            startPolling()
            log(
                Log.INFO,
                TAG,
                "Mounted independent full-window host folded=${foldedWidth}x$foldedHeight " +
                        "root=${rootWidth()}x${rootHeight()} class=${windowRoot.javaClass.name}",
                null,
            )
        }

        @SuppressLint("ClickableViewAccessibility")
        private fun buildFullWindowHost(): FrameLayout = FrameLayout(targetContext).apply {
            tag = HOST_TAG
            isClickable = true
            isFocusable = false
            background = null
            clipChildren = false
            clipToPadding = false
            var outsideGesture = false
            setOnTouchListener { _, event ->
                val currentPanel = if (::panel.isInitialized) currentPanelRect() else foldedRect
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        outsideGesture = SystemUiIndependentPanelPolicy.hitTest(
                            currentPanel,
                            event.x.toInt(),
                            event.y.toInt(),
                        ) == SystemUiPanelHit.OUTSIDE
                        if (!outsideGesture) rescheduleOfficialDismissTimeout()
                        true
                    }

                    MotionEvent.ACTION_UP -> {
                        val shouldClose = outsideGesture
                        outsideGesture = false
                        // 点击面板外部关闭要走带动画的折叠（morph 回折叠尺寸），
                        // 不要用 closeImmediately 瞬闪消失，否则用户会觉得“没有关闭动画”。
                        if (shouldClose) close("outside touch")
                        true
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        outsideGesture = false
                        true
                    }

                    else -> true
                }
            }
        }

        private fun buildPanel(): FrameLayout = FrameLayout(targetContext).apply {
            tag = PANEL_TAG
            isClickable = true
            isFocusable = true
            background = null
            outlineProvider = dialog.outlineProvider
            clipToOutline = true
            // 不继承官方 dialog 的 elevation/translationZ：全窗口 host 上再叠一层投影会形成
            // 灰色阴影边框，官方展开面板背景（expandedMaterial）自带圆角材质，无需投影。
            elevation = 0f
            translationZ = 0f
            clipChildren = true
            clipToPadding = true
            pageHost = FrameLayout(targetContext).apply {
                isClickable = true
                clipChildren = true
                clipToPadding = true
            }
            addView(
                pageHost,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }

        private fun startOpenAnimation() {
            if (openAnimationStarted) return
            openAnimationStarted = true
            startMorphAnimation(from = 0f, to = 1f, onEnd = null)
        }

        private fun startMorphAnimation(from: Float, to: Float, onEnd: (() -> Unit)?) {
            morphAnimator?.cancel()
            morphAnimator = ValueAnimator.ofFloat(from, to).apply {
                duration = EXPAND_ANIMATION_DURATION_MILLIS
                interpolator = EXPAND_INTERPOLATOR
                addUpdateListener { animator ->
                    val fraction = animator.animatedValue as Float
                    animationFraction = fraction
                    applyPanelRect(
                        SystemUiIndependentPanelPolicy.interpolateRect(
                            animationSpec,
                            fraction
                        )
                    )
                    // 整个面板内容（音量条 + 更多按钮 + 应用图标）作为整体淡入，不做列分层；
                    // 列分层会让音量条与按钮/图标不同步播放，用户要求三者整体连贯。
                    panel.alpha = fraction
                }
                addListener(object : AnimatorListenerAdapter() {
                    private var cancelled = false

                    override fun onAnimationCancel(animation: Animator) {
                        cancelled = true
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        if (!cancelled) onEnd?.invoke()
                    }
                })
                start()
            }
        }

        private fun applyPanelRect(rect: SystemUiPanelRect) {
            val params = panel.layoutParams ?: return
            params.width = rect.width
            params.height = rect.height
            if (params is ViewGroup.MarginLayoutParams) {
                params.leftMargin = rect.left
                params.topMargin = rect.top
                params.rightMargin = 0
                params.bottomMargin = 0
            }
            panel.layoutParams = params
        }

        private fun currentPanelRect(): SystemUiPanelRect {
            val params = panel.layoutParams
            val left = (params as? ViewGroup.MarginLayoutParams)?.leftMargin ?: panel.left
            val top = (params as? ViewGroup.MarginLayoutParams)?.topMargin ?: panel.top
            val width = dimension(panel.width, panel.measuredWidth, params?.width)
            val height = dimension(panel.height, panel.measuredHeight, params?.height)
            return SystemUiPanelRect(left, top, left + width, top + height)
        }

        private fun configureAppsPanel(appCount: Int, columnWidth: Int, columnHeight: Int) {
            val contentInset = expandedPanelContentInset()
            val layout = SystemUiIndependentPanelPolicy.compactLayout(
                appCount = appCount,
                availableWidth = rootWidth(),
                availableHeight = rootHeight(),
                columnWidth = columnWidth,
                columnHeight = columnHeight,
                navigationWidth = 0,
                horizontalPadding = contentInset,
                verticalPadding = contentInset,
                columnSpacing = contentInset,
                headerHeight = 0,
                edgeMargin = dp(PANEL_EDGE_MARGIN_DP),
                emptyContentWidth = dp(EMPTY_CONTENT_WIDTH_DP),
            )
            configurePanelBounds(layout.width, layout.height)
            log(
                Log.DEBUG,
                TAG,
                "Compact panel apps=$appCount visible=${layout.visibleColumns} scroll=${layout.scrollable} " +
                        "size=${layout.width}x${layout.height}",
                null,
            )
        }

        /**
         * 计算设备选择页的目标面板矩形（基于折叠入口展开的设备页形态），只计算不落位。
         *
         * 进入设备选择页时用它作为展开动画的终点；[configureDevicePanel] 负责真正把
         * animationSpec 落位到该形态。
         */
        private fun devicePanelExpandedRect(deviceCount: Int): SystemUiPanelRect {
            val maximumWidth = rootWidth() - dp(PANEL_EDGE_MARGIN_DP) * 2
            val maximumHeight = rootHeight() - dp(PANEL_EDGE_MARGIN_DP) * 2
            val width = dp(DEVICE_PAGE_WIDTH_DP).coerceAtMost(maximumWidth)
            val naturalHeight = devicePanelNaturalHeight(deviceCount)
            return SystemUiIndependentPanelPolicy.animationSpec(
                folded = foldedRect,
                expandedWidth = width,
                expandedHeight = naturalHeight.coerceAtMost(maximumHeight),
                parentWidth = rootWidth(),
                parentHeight = rootHeight(),
                edgeMargin = dp(PANEL_EDGE_MARGIN_DP),
            ).expanded
        }

        /**
         * 设备页自然高度：实际行数 = 跟随系统固定 1 行 + 扫描设备（本机 + 外设）+
         * 可能的断开行。deviceCount 只是扫描设备数，必须补上 follow 行，否则面板偏矮，
         * 设备列表底部会被裁掉（表现为"只显示一行"）。
         *
         * 高度与 buildDevicePage 的布局精确对齐：header + 行数×行高 +
         * (行数+1)×行间距（每行 topMargin + rowsScroll topMargin）+ rowsContent
         * 顶部/底部 padding + 页面上下 padding。行少则面板矮（不空），行多封顶后
         * 由 ScrollView 滚动，底部自然露出半行提示可继续滚动。
         */
        private fun devicePanelNaturalHeight(deviceCount: Int): Int {
            val rowCount = deviceCount.coerceAtLeast(0) + DEVICE_PAGE_RESERVED_ROWS
            return dp(DEVICE_PAGE_HEADER_HEIGHT_DP) +
                    // header 下移的 topMargin。
                    dp(DEVICE_PAGE_ROW_SPACING_DP) +
                    rowCount * dp(DEVICE_ROW_HEIGHT_DP) +
                    (rowCount + 1) * dp(DEVICE_PAGE_ROW_SPACING_DP) +
                    // rowsContent 顶部 padding + 页面 top padding。
                    dp(DEVICE_PAGE_ROW_SPACING_DP) + dp(PANEL_VERTICAL_PADDING_DP) +
                    // 页面 bottom padding（rowsContent 底部已不额外留白）。
                    dp(DEVICE_PAGE_ROW_SPACING_DP)
        }

        /**
         * 计算应用概览页的目标面板矩形（基于折叠入口展开的 apps 页形态），只计算不落位。
         *
         * 从设备选择页返回概览时用它作为收缩动画的终点；[configureAppsPanel] 负责真正把
         * animationSpec 落位到该形态。
         */
        private fun appsPanelExpandedRect(
            appCount: Int,
            columnWidth: Int,
            columnHeight: Int,
        ): SystemUiPanelRect {
            val contentInset = expandedPanelContentInset()
            val layout = SystemUiIndependentPanelPolicy.compactLayout(
                appCount = appCount,
                availableWidth = rootWidth(),
                availableHeight = rootHeight(),
                columnWidth = columnWidth,
                columnHeight = columnHeight,
                navigationWidth = 0,
                horizontalPadding = contentInset,
                verticalPadding = contentInset,
                columnSpacing = contentInset,
                headerHeight = 0,
                edgeMargin = dp(PANEL_EDGE_MARGIN_DP),
                emptyContentWidth = dp(EMPTY_CONTENT_WIDTH_DP),
            )
            return SystemUiIndependentPanelPolicy.animationSpec(
                folded = foldedRect,
                expandedWidth = layout.width,
                expandedHeight = layout.height,
                parentWidth = rootWidth(),
                parentHeight = rootHeight(),
                edgeMargin = dp(PANEL_EDGE_MARGIN_DP),
            ).expanded
        }

        private fun configureDevicePanel(deviceCount: Int) {
            val maximumWidth = rootWidth() - dp(PANEL_EDGE_MARGIN_DP) * 2
            val maximumHeight = rootHeight() - dp(PANEL_EDGE_MARGIN_DP) * 2
            val width = dp(DEVICE_PAGE_WIDTH_DP).coerceAtMost(maximumWidth)
            val naturalHeight = devicePanelNaturalHeight(deviceCount)
            configurePanelBounds(width, naturalHeight.coerceAtMost(maximumHeight))
        }

        private fun configurePanelBounds(width: Int, height: Int) {
            animationSpec = SystemUiIndependentPanelPolicy.animationSpec(
                folded = foldedRect,
                expandedWidth = width,
                expandedHeight = height,
                parentWidth = rootWidth(),
                parentHeight = rootHeight(),
                edgeMargin = dp(PANEL_EDGE_MARGIN_DP),
            )
            applyPanelRect(
                SystemUiIndependentPanelPolicy.interpolateRect(
                    animationSpec,
                    animationFraction
                )
            )
        }

        private fun rootWidth(): Int = dimension(
            windowRoot.width,
            windowRoot.measuredWidth,
            targetContext.resources.displayMetrics.widthPixels,
        )

        private fun rootHeight(): Int = dimension(
            windowRoot.height,
            windowRoot.measuredHeight,
            targetContext.resources.displayMetrics.heightPixels,
        )

        private fun resolveColumnDimension(view: View, horizontal: Boolean, fallback: Int): Int {
            val layoutValue =
                if (horizontal) view.layoutParams?.width else view.layoutParams?.height
            if (layoutValue != null && layoutValue > 0) return layoutValue
            return try {
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(rootWidth(), View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(rootHeight(), View.MeasureSpec.AT_MOST),
                )
                val measured = if (horizontal) view.measuredWidth else view.measuredHeight
                measured.takeIf { it > 0 } ?: fallback
            } catch (error: RuntimeException) {
                log(Log.WARN, TAG, "Unable to pre-measure official VolumeColumn", error)
                fallback
            }
        }

        private fun applyOfficialColumnLayers(panelFraction: Float) {
            officialColumns.forEachIndexed { index, column ->
                val state =
                    SystemUiIndependentPanelPolicy.volumeColumnLayerState(index, panelFraction)
                column.view.alpha = state.alpha
                column.view.scaleX = state.scale
                column.view.scaleY = state.scale
                column.view.translationZ = -dp(COLUMN_TRANSLATION_Z_DP) * state.translationZFraction
            }
        }

        private fun startPolling() {
            val taskGeneration = generation.get()
            executor.scheduleWithFixedDelay(
                { pollSnapshot(taskGeneration) },
                0L,
                POLL_INTERVAL_MILLIS,
                TimeUnit.MILLISECONDS,
            )
        }

        private fun pollSnapshot(taskGeneration: Long) {
            if (!SystemUiGenerationGate.accepts(
                    closed.get(),
                    generation.get(),
                    taskGeneration
                )
            ) return
            if (trackingUids.isNotEmpty()) return
            try {
                val snapshot = panelBridge.snapshot()
                if (!SystemUiGenerationGate.accepts(
                        closed.get(),
                        generation.get(),
                        taskGeneration
                    )
                ) return
                if (snapshot.status == PanelPlaybackStatus.HOST_UNAVAILABLE) {
                    requestOverlayFallback(taskGeneration, "host unavailable")
                    return
                }
                if (snapshot.status == PanelPlaybackStatus.CONNECTING) return
                val fingerprint = SystemUiIndependentPanelPolicy.fingerprint(snapshot)
                if (fingerprint == lastFingerprint) return
                val loaded = loadSnapshot(snapshot)
                if (!SystemUiGenerationGate.accepts(
                        closed.get(),
                        generation.get(),
                        taskGeneration
                    )
                ) return
                lastFingerprint = fingerprint
                postToPanel(taskGeneration) { renderSnapshot(loaded) }
            } catch (throwable: Throwable) {
                log(Log.ERROR, TAG, "Unable to poll panel bridge", throwable)
                requestOverlayFallback(taskGeneration, "panel bridge failure")
            }
        }

        private fun loadSnapshot(snapshot: PanelPlaybackSnapshot): LoadedSnapshot {
            val packageManager = targetContext.packageManager
            val hideSystemApps = hideSystemAppsEnabled()
            val filteredRows = if (hideSystemApps) {
                snapshot.rows.filter { !it.isSystemApp }
            } else {
                snapshot.rows
            }
            val loadedByUid =
                filteredRows.associateBy(PanelPlaybackRow::uid).mapValues { (_, row) ->
                    val cacheKey = "${row.uid}:${row.packageName}"
                    val cached = appVisualCache[cacheKey]
                    val visual = if (cached != null) {
                        cached
                    } else {
                        val packageInfo = loadApplicationInfo(row)
                        val label = packageInfo?.let { info ->
                            runCatching {
                                info.loadLabel(packageManager).toString().takeIf(String::isNotBlank)
                            }
                                .onFailure { error ->
                                    log(
                                        Log.WARN,
                                        TAG,
                                        "Unable to load SystemUI package label package=${row.packageName}",
                                        error
                                    )
                                }
                                .getOrNull()
                        } ?: row.label ?: row.packageName
                        (label to loadApplicationIcon(
                            row,
                            packageInfo
                        )).also { appVisualCache[cacheKey] = it }
                    }
                    LoadedAppRow(
                        state = SystemUiBuiltinAppRowState(
                            row.packageName,
                            visual.first,
                            row.uid,
                            row.volumePercent
                        ),
                        protocolRow = row,
                        icon = visual.second,
                    )
                }
            val rows = SystemUiBuiltinPanelState.sorted(loadedByUid.values.map(LoadedAppRow::state))
                .map { state ->
                    checkNotNull(loadedByUid[state.uid]) { "Sorted panel row disappeared uid=${state.uid}" }
                }
            return LoadedSnapshot(snapshot, rows)
        }

        private fun loadApplicationInfo(row: PanelPlaybackRow): ApplicationInfo? {
            val userId = row.uid / PER_USER_RANGE
            val currentUserId = android.os.Process.myUid() / PER_USER_RANGE
            if (userId == currentUserId) {
                try {
                    return targetContext.packageManager.getApplicationInfo(row.packageName, 0)
                } catch (error: PackageManager.NameNotFoundException) {
                    log(
                        Log.WARN,
                        TAG,
                        "SystemUI PackageManager cannot see package=${row.packageName} user=$userId",
                        error,
                    )
                } catch (error: SecurityException) {
                    log(
                        Log.WARN,
                        TAG,
                        "SystemUI PackageManager denied package=${row.packageName} user=$userId",
                        error,
                    )
                }
            }
            return try {
                val launcherApps =
                    targetContext.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
                        ?: error("LauncherApps service unavailable")
                launcherApps.getApplicationInfo(
                    row.packageName,
                    0,
                    UserHandle.getUserHandleForUid(row.uid),
                )
            } catch (error: PackageManager.NameNotFoundException) {
                log(
                    Log.WARN,
                    TAG,
                    "Cross-user LauncherApps cannot see package=${row.packageName} user=$userId",
                    error,
                )
                null
            } catch (error: SecurityException) {
                log(
                    Log.WARN,
                    TAG,
                    "Cross-user LauncherApps denied package=${row.packageName} user=$userId",
                    error,
                )
                null
            } catch (error: RuntimeException) {
                log(
                    Log.WARN,
                    TAG,
                    "Cross-user LauncherApps lookup failed package=${row.packageName} user=$userId",
                    error,
                )
                null
            }
        }

        private fun loadApplicationIcon(row: PanelPlaybackRow, info: ApplicationInfo?): Drawable {
            val rowUserId = row.uid / PER_USER_RANGE
            val rowUser = UserHandle.getUserHandleForUid(row.uid)
            val currentUserId = android.os.Process.myUid() / PER_USER_RANGE
            val currentUserPackageIcon = if (rowUserId == currentUserId) {
                info?.let { applicationInfo ->
                    runCatching { applicationInfo.loadIcon(targetContext.packageManager) }
                        .onFailure { error ->
                            log(
                                Log.WARN,
                                TAG,
                                "Unable to load current-user PackageManager icon package=${row.packageName}",
                                error
                            )
                        }
                        .getOrNull()
                }
            } else {
                null
            }
            val launcherIcon = loadLauncherIcon(row, rowUser)
            val providerIcon = decodeProviderIcon(row)
            val candidates = buildList {
                currentUserPackageIcon?.let { add(SystemUiAppIconSource.SYSTEM_UI_PACKAGE to it) }
                launcherIcon?.let { add(SystemUiAppIconSource.LAUNCHER_APPS to it) }
                providerIcon?.let { add(SystemUiAppIconSource.PROVIDER_PAYLOAD to it) }
                add(SystemUiAppIconSource.DEFAULT_ICON to targetContext.packageManager.defaultActivityIcon)
            }
            candidates.forEach { (source, drawable) ->
                val rendered = runCatching { rasterizeDrawable(row.packageName, drawable) }
                    .onFailure { error ->
                        log(
                            Log.ERROR,
                            TAG,
                            "App icon candidate rejected package=${row.packageName} source=$source drawable=${drawable.javaClass.name}",
                            error,
                        )
                    }
                    .getOrNull()
                if (rendered != null) {
                    log(
                        Log.INFO,
                        TAG,
                        "Loaded app icon package=${row.packageName} source=$source drawable=${drawable.javaClass.name} " +
                                "rendered=${rendered.javaClass.name} size=${rendered.intrinsicWidth}x${rendered.intrinsicHeight}",
                        null,
                    )
                    return rendered
                }
            }
            error("No visible app icon candidate package=${row.packageName} uid=${row.uid}")
        }

        private fun loadLauncherIcon(row: PanelPlaybackRow, user: UserHandle): Drawable? = try {
            val launcherApps =
                targetContext.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
                    ?: error("LauncherApps service unavailable")
            val launcherInfo = launcherApps.getApplicationInfo(row.packageName, 0, user)
            launcherInfo.loadIcon(targetContext.packageManager)
        } catch (error: PackageManager.NameNotFoundException) {
            log(
                Log.WARN,
                TAG,
                "LauncherApps icon package not found package=${row.packageName} user=${row.uid / PER_USER_RANGE}",
                error
            )
            null
        } catch (error: SecurityException) {
            log(
                Log.WARN,
                TAG,
                "LauncherApps icon denied package=${row.packageName} user=${row.uid / PER_USER_RANGE}",
                error
            )
            null
        } catch (error: RuntimeException) {
            log(
                Log.WARN,
                TAG,
                "LauncherApps icon failed package=${row.packageName} user=${row.uid / PER_USER_RANGE}",
                error
            )
            null
        }

        private fun rasterizeDrawable(packageName: String, drawable: Drawable): Drawable {
            val size = dp(APP_ICON_RASTER_SIZE_DP)
            val bitmap = createBitmap(size, size)
            val canvas = Canvas(bitmap)
            val oldBounds = drawable.bounds
            try {
                drawable.setBounds(0, 0, size, size)
                drawable.draw(canvas)
            } finally {
                drawable.bounds = oldBounds
            }
            check(bitmapHasVisiblePixel(bitmap)) {
                "Drawable rendered fully transparent package=$packageName type=${drawable.javaClass.name}"
            }
            return bitmap.toDrawable(targetContext.resources).apply {
                setBounds(
                    0,
                    0,
                    size,
                    size
                )
            }
        }

        private fun bitmapHasVisiblePixel(bitmap: Bitmap): Boolean {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return SystemUiDrawablePixelVisibility.hasVisiblePixel(
                bitmap.width,
                bitmap.height,
                pixels
            )
        }

        private fun decodeProviderIcon(row: PanelPlaybackRow): Drawable? {
            val bytes = row.iconPng ?: run {
                log(
                    Log.WARN,
                    TAG,
                    "Provider supplied no app icon package=${row.packageName} uid=${row.uid}",
                    null
                )
                return null
            }
            return try {
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap == null) {
                    log(
                        Log.ERROR,
                        TAG,
                        "Provider app icon decode returned null package=${row.packageName}",
                        null
                    )
                    null
                } else if (!bitmapHasVisiblePixel(bitmap)) {
                    log(
                        Log.ERROR,
                        TAG,
                        "Provider app icon is fully transparent package=${row.packageName}",
                        null
                    )
                    null
                } else {
                    bitmap.toDrawable(targetContext.resources).apply {
                        setBounds(0, 0, bitmap.width, bitmap.height)
                    }
                }
            } catch (error: RuntimeException) {
                log(
                    Log.ERROR,
                    TAG,
                    "Unable to decode provider app icon package=${row.packageName}",
                    error
                )
                null
            }
        }

        private fun renderSnapshot(loaded: LoadedSnapshot) {
            lastSnapshot = loaded.snapshot
            val selected = selectedPackage?.let { packageName ->
                loaded.apps.firstOrNull { it.state.packageName == packageName }
            }
            if (selectedPackage != null && selected == null) selectedPackage = null
            if (selected != null) {
                // 设备页会释放 overview 的官方列；取消任何尚未执行的列退出回调，避免旧页重建。
                appsRenderGeneration += 1L
                activeAppsPage = null
                renderedAppPackages = emptySet()
                configureDevicePanel(loaded.snapshot.devices.size)
                replacePage(
                    buildDevicePage(selected, loaded.snapshot.devices),
                    forward = true,
                    animate = false
                )
                startOpenAnimation()
                return
            }
            renderAppsSnapshot(loaded.apps)
        }

        private fun renderAppsSnapshot(apps: List<LoadedAppRow>) {
            val nextPackages = apps.map { it.state.packageName }.toSet()
            val transition = SystemUiAppColumnTransitions.resolve(renderedAppPackages, nextPackages)
            val renderGeneration = ++appsRenderGeneration
            // 应用列表完全没变时（只是音量百分比变化），原地更新 slider 位置，
            // 不重建页面、不触发收束动画。重建页面会导致新 View 尚未 layout，
            // view.x 为 0，startRetainedColumnConvergeAnimation 误判所有列需要从
            // 旧位置滑到 0，表现为"第二个音量条及后面的从右侧滑过来"。
            if (transition.entering.isEmpty() && transition.exiting.isEmpty() &&
                activeAppsPage?.view === currentPage && officialColumns.isNotEmpty()
            ) {
                val columnsByPackage = officialColumns.associateBy { it.packageName }
                apps.forEach { row ->
                    val column = columnsByPackage[row.state.packageName]
                    column?.updateVolume(row.state.volumePercent)
                }
                startOpenAnimation()
                return
            }
            // 在构建新页面前，记录保留列在旧页面中的 X 坐标，
            // 用于退出动画完成后让保留列平滑滑动到新位置，而非瞬移。
            val oldColumnXs = HashMap<String, Float>()
            val oldPage = activeAppsPage?.takeIf { it.view === currentPage }
            oldPage?.columnViews?.forEach { (pkg, view) ->
                if (pkg in transition.retained) {
                    oldColumnXs[pkg] = view.x
                }
            }
            val applyPage: () -> Unit = applyPage@{
                if (closed.get() || renderGeneration != appsRenderGeneration) return@applyPage
                val appsPage = buildAppsPage(apps)
                configureAppsPanel(apps.size, appsPage.columnWidth, appsPage.columnHeight)
                replacePage(appsPage.view, forward = false, animate = false)
                activeAppsPage = appsPage
                renderedAppPackages = nextPackages
                if (transition.entering.isNotEmpty() && transition.retained.isNotEmpty()) {
                    startColumnEnterAnimation(
                        transition.entering.mapNotNull(appsPage.columnViews::get),
                    )
                }
                // 保留列从旧位置平滑滑动到新位置（收束动画）。
                if (oldColumnXs.isNotEmpty()) {
                    startRetainedColumnConvergeAnimation(appsPage.columnViews, oldColumnXs)
                }
                startOpenAnimation()
            }
            val exiting = oldPage?.columnViews
                ?.filterKeys(transition.exiting::contains)
                ?.values
                .orEmpty()
            if (exiting.isEmpty()) {
                applyPage.invoke()
                return
            }
            startColumnExitAnimation(exiting)
            val accepted =
                pageHost.postDelayed({ applyPage.invoke() }, columnExitSpringDurationMillis)
            if (!accepted) {
                log(Log.ERROR, TAG, "Volume page rejected app-column transition callback", null)
            }
        }

        /**
         * 复刻官方 VolumeExpandCollapsedAnimator.getVolumeAnimNode(expanded=true) 的错位节点逻辑。
         * 第 0 列 node=0（立即开始），第 1 列=0.3，第 2 列=0.5，第 3 列=0.6，后续递增 0.1。
         * node 值映射到 alpha = (1/(1-node)) * (progress - node)，scale = 0.4*alpha + 0.6。
         */
        private fun volumeAnimNodeExpanded(index: Int): Float = when (index) {
            0 -> 0f
            1 -> 0.3f
            2 -> 0.5f
            3 -> 0.6f
            else -> 0.6f + (index - 3) * 0.1f
        }

        /**
         * 复刻官方 VolumeExpandCollapsedAnimator.getVolumeAnimNode(expanded=false) 的折叠错位节点。
         * 折叠时第 0 列仍然 node=0，其余列按 0.5-((count-1-i)*0.1) 递减错位。
         */
        private fun volumeAnimNodeCollapsed(index: Int, count: Int): Float {
            if (index == 0) return 0f
            return (0.5f - ((count - 1 - index) * 0.1f)).coerceAtLeast(0f)
        }

        private fun startColumnEnterAnimation(columns: List<View>) {
            // 初始化所有列为隐藏状态
            columns.forEach { column ->
                column.animate().cancel()
                column.alpha = 0f
                column.scaleX = COLUMN_ENTER_START_SCALE
                column.scaleY = COLUMN_ENTER_START_SCALE
                column.translationY = dp(COLUMN_ENTER_TRANSLATION_DP).toFloat()
            }
            // 单一 ValueAnimator + spring 插值器驱动所有列，通过 node 错位实现依次出现。
            // 这与官方 VolumeExpandCollapsedAnimator 的单 progress 驱动方式一致，
            // 所有列共享同一条 spring 曲线，视觉上更连贯。
            val enterTranslation = dp(COLUMN_ENTER_TRANSLATION_DP).toFloat()
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = columnEnterSpringDurationMillis
                interpolator = columnEnterSpringInterpolator
                addUpdateListener { animator ->
                    val progress = animator.animatedValue as Float
                    columns.forEachIndexed { index, column ->
                        val node = volumeAnimNodeExpanded(index)
                        // 官方 alpha 映射：alpha = (1/(1-node)) * (progress - node)
                        val rawAlpha =
                            if (node >= 1f) 1f else (1f / (1f - node)) * (progress - node)
                        val alpha = rawAlpha.coerceIn(0f, 1f)
                        // 官方 scale 映射：scale = 0.4 * alpha + 0.6
                        val scale = (0.4f * alpha + 0.6f).coerceIn(COLUMN_ENTER_START_SCALE, 1f)
                        column.alpha = alpha
                        column.scaleX = scale
                        column.scaleY = scale
                        column.translationY = enterTranslation * (1f - alpha)
                    }
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        columns.forEach { column ->
                            column.alpha = 1f
                            column.scaleX = 1f
                            column.scaleY = 1f
                            column.translationY = 0f
                        }
                    }
                })
                start()
            }
        }

        private fun startColumnExitAnimation(columns: Collection<View>) {
            val columnList = columns.toList()
            // 单一 ValueAnimator + spring 插值器驱动所有列退出，与进入动画结构对称。
            val exitTranslation = dp(COLUMN_EXIT_TRANSLATION_DP).toFloat()
            ValueAnimator.ofFloat(1f, 0f).apply {
                duration = columnExitSpringDurationMillis
                interpolator = columnExitSpringInterpolator
                addUpdateListener { animator ->
                    val progress = animator.animatedValue as Float
                    columnList.forEachIndexed { index, column ->
                        val node = volumeAnimNodeCollapsed(index, columnList.size)
                        // 退出时 progress 从 1→0，alpha 映射同进入但方向相反
                        val rawAlpha =
                            if (node >= 1f) 1f else (1f / (1f - node)) * (progress - node)
                        val alpha = rawAlpha.coerceIn(0f, 1f)
                        val scale = (0.4f * alpha + 0.6f).coerceIn(COLUMN_EXIT_END_SCALE, 1f)
                        column.alpha = alpha
                        column.scaleX = scale
                        column.scaleY = scale
                        column.translationY = -exitTranslation * (1f - alpha)
                    }
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        columnList.forEach { column ->
                            column.alpha = 0f
                            column.scaleX = COLUMN_EXIT_END_SCALE
                            column.scaleY = COLUMN_EXIT_END_SCALE
                        }
                    }
                })
                start()
            }
        }

        /**
         * 保留列收束动画：当某些列退出后，剩余列需要从旧位置平滑滑动到新位置。
         * 新页面构建后保留列已被放到新坐标，先把 translationX 设为旧坐标与新坐标的差值
         * （视觉上回到旧位置），再用 spring 插值器动画 translationX→0，让列平滑滑入新位置。
         */
        private fun startRetainedColumnConvergeAnimation(
            newColumnViews: Map<String, View>,
            oldColumnXs: Map<String, Float>,
        ) {
            newColumnViews.forEach { (pkg, view) ->
                val oldX = oldColumnXs[pkg] ?: return@forEach
                val newX = view.x
                val offset = oldX - newX
                if (offset * offset < 1f) return@forEach // 偏移 < 1px 不动画
                view.animate().cancel()
                view.translationX = offset
                view.animate()
                    .translationX(0f)
                    .setDuration(columnExitSpringDurationMillis)
                    .setInterpolator(columnExitSpringInterpolator)
                    .start()
            }
        }

        private fun buildAppsPage(apps: List<LoadedAppRow>): AppsPageBuild {
            releaseOfficialColumns()
            val columns = LinearLayout(targetContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = null
                clipChildren = false
                clipToPadding = false
            }
            val columnViews = LinkedHashMap<String, View>()
            val contentInset = expandedPanelContentInset()
            var resolvedColumnWidth = fallbackColumnWidth.coerceAtLeast(dp(MIN_COLUMN_WIDTH_DP))
            var resolvedColumnHeight = if (apps.isEmpty()) {
                dp(EMPTY_CONTENT_BODY_HEIGHT_DP)
            } else {
                fallbackColumnHeight.coerceAtLeast(dp(MIN_COLUMN_HEIGHT_DP))
            }
            if (apps.isEmpty()) {
                columns.addView(buildMessage(moduleContext.getString(R.string.panel_no_playing_apps)))
            } else {
                val streams =
                    SystemUiFakeStreamAllocator.allocate(apps.map { it.state.packageName })
                val builtColumns = apps.map { app ->
                    buildAppColumn(app, streams.getValue(app.state.packageName))
                }
                resolvedColumnWidth = builtColumns.maxOf(AppColumnBuild::columnWidth)
                resolvedColumnHeight = builtColumns.maxOf(AppColumnBuild::columnHeight)
                builtColumns.forEachIndexed { index, built ->
                    check(columnViews.put(built.packageName, built.view) == null) {
                        "Duplicate app column package=${built.packageName}"
                    }
                    columns.addView(
                        built.view,
                        LinearLayout.LayoutParams(resolvedColumnWidth, resolvedColumnHeight).apply {
                            if (index > 0) marginStart = contentInset
                        },
                    )
                }
            }
            val scroll = HorizontalScrollView(targetContext).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                background = null
                clipChildren = false
                clipToPadding = false
                isFillViewport = false
                addView(
                    columns,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    ),
                )
            }
            check(SystemUiIndependentPanelPolicy.symmetricColumnInsets(contentInset).horizontal == contentInset) {
                "Application page content inset must be symmetric"
            }
            val content = LinearLayout(targetContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = null
                clipChildren = false
                clipToPadding = false
                setPadding(contentInset, contentInset, contentInset, contentInset)
                addView(
                    scroll,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    ).apply {
                        // 列数较少时让整排音量条水平居中，而不是贴左。
                        gravity = Gravity.CENTER_HORIZONTAL
                    },
                )
            }
            return AppsPageBuild(content, resolvedColumnWidth, resolvedColumnHeight, columnViews)
        }

        @SuppressLint("RtlHardcoded")
        private fun buildAppColumn(
            row: LoadedAppRow,
            fakeStream: Int,
        ): AppColumnBuild {
            val openDetails = {
                selectedPackage = row.state.packageName
                appsRenderGeneration += 1L
                activeAppsPage = null
                renderedAppPackages = emptySet()
                val devices = lastSnapshot?.devices.orEmpty()
                // 参考官方 VolumeExpandCollapsedAnimator.expand：进入设备选择使用尺寸/位置
                // 展开过渡，而不是横向翻页。先无动画替换内容，再从当前面板形态展开到设备页形态。
                val from = currentPanelRect()
                val to = devicePanelExpandedRect(devices.size)
                replacePage(buildDevicePage(row, devices), forward = true, animate = false)
                startPanelResizeAnimation(from, to) {
                    // 动画落定后把正式动画规格固定为设备页形态（供返回/关闭使用），并精确对齐目标矩形。
                    configureDevicePanel(devices.size)
                    animationFraction = 1f
                    applyPanelRect(
                        SystemUiIndependentPanelPolicy.interpolateRect(
                            animationSpec,
                            1f
                        )
                    )
                }
            }
            // 官方 VolumeColumn 需要传入 parent 完成 initColumn 挂载；wrapper 会重新把
            // official.view 直接 addView 进自己并强制拉伸到 officialWidth × sliderHeight，
            // 这里只给一个临时容器供官方初始化使用。
            val official = OfficialVolumeColumn.create(
                classLoader = pluginClassLoader,
                context = targetContext,
                parent = sliderContainer(targetContext),
                fakeStream = fakeStream,
                row = row,
                onTrackingChanged = { tracking ->
                    if (tracking) trackingUids += row.state.uid else trackingUids -= row.state.uid
                    rescheduleOfficialDismissTimeout()
                },
                onVolumeCommitted = ::submitVolume,
                onFailure = { throwable ->
                    log(
                        Log.ERROR,
                        TAG,
                        "Official VolumeColumn interaction failed uid=${row.state.uid}",
                        throwable
                    )
                },
            )
            official.prepareStandaloneColumn(row.state.packageName, log)
            officialColumns += official
            val pixelSizes =
                SystemUiColumnPixelSizes.fromDensity(targetContext.resources.displayMetrics.density)
            val officialWidth = resolveColumnDimension(
                official.view,
                horizontal = true,
                fallback = fallbackColumnWidth,
            ).coerceIn(dp(MIN_COLUMN_WIDTH_DP), dp(MAX_COLUMN_WIDTH_DP))
            // 只保留一个顶部更多按钮；此前按双按钮 actionContentWidth 扩宽列（40+8+40），
            // 会让卡片左右比上下多出一截空白。官方 VolumeColumn 本身宽度已容纳单按钮，
            // 因此列宽只需覆盖 slider 与一个 action slot。
            val wrapperWidth = SystemUiIndependentPanelPolicy.singleActionColumnWidth(
                officialWidth = officialWidth,
                actionSize = pixelSizes.actionSize,
            )
            val maximumSliderHeight =
                rootHeight() - dp(PANEL_EDGE_MARGIN_DP) * 2 - expandedPanelContentInset() * 2
            // 卡片高度必须严格等于官方 VolumeColumnRes.getHeight 的真实 slider 高度。
            // 此前 coerceIn(220dp, 420dp) 会在官方高度较小时把外层卡片单独拉高，内部 slider
            // 仍顶部对齐，因此所有差值都会表现成额外底部空白。
            val sliderHeight = SystemUiIndependentPanelPolicy.officialColumnHeight(
                measuredHeight = resolveColumnDimension(
                    official.slider,
                    horizontal = false,
                    fallback = fallbackColumnHeight,
                ),
                maximumHeight = maximumSliderHeight,
            )
            // 官方展开面板直接使用 o3_miui_volume_expend_height 的完整列高；slider padding 是
            // 原版材质、圆角和触摸几何的一部分，不能从外层裁掉。
            // 参考官方 VolumeColumn：icon/按钮作为音量条内部的内嵌覆盖层（官方布局
            // miui_volume_dialog_column 把 icon 放在 slider 内部，用 bottomMargin 定位），
            // 而不是在音量条外面再叠一排独立槽位。更多按钮在内部顶部，应用图标在内部底部。
            // 设备喇叭按钮已按用户要求移除；更多按钮的触摸严格复用官方侧边栏展开按钮路径。
            val moreButton = buildMoreButton(official.slider, openDetails)
            // 应用图标：SoundMan 独立 ImageView，内容 = 应用图标原色（不叠加官方 tint），
            // 运行时对齐官方 slider 轨道的实际底部内部（用坐标计算，不依赖官方 icon 布局，
            // 避免官方 icon 的 tint/updateIcon 覆盖导致"蓝色/内容丢失/落在轨道下方"）。
            val appIconContentSize = dp(INNER_ICON_SIZE_DP)
            val appIconSlotSize = pixelSizes.actionSize
            val overlayVerticalInset = dp(ACTION_INSET_MARGIN_DP)
            val appIcon = ImageView(targetContext).apply {
                setImageDrawable(row.icon)
                imageTintList = null
                backgroundTintList = null
                background = null
                contentDescription = null
                isEnabled = false
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                // 完全作为展示层禁用：FrameLayout 会继续把事件派发到下层官方 slider，图标区域
                // 不应成为触摸目标或无障碍焦点，更不能阻挡音量拖动。
                // 不设 OnTouchListener：触摸图标区域时事件会继续
                // 传给 wrapper 里的 sibling（official.view -> slider），音量条正常拖动，
                // 图标只是展示层，绝不阻隔触摸。
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                val contentInset = (appIconSlotSize - appIconContentSize).coerceAtLeast(0) / 2
                setPadding(contentInset, contentInset, contentInset, contentInset)
            }
            // 将覆盖层直接挂到官方 VolumeColumn 根容器：官方 SeekBar 的触摸/动画仍只作用于
            // 原生 root，更多按钮和应用图标则天然跟随同一 scale/translation，无额外父层参与绘制。
            val officialRoot = checkNotNull(official.view as? FrameLayout) {
                "Official VolumeColumn root must be FrameLayout: ${official.view.javaClass.name}"
            }.apply {
                clipChildren = false
                clipToPadding = false
                addView(
                    moreButton,
                    FrameLayout.LayoutParams(
                        appIconSlotSize,
                        appIconSlotSize,
                        Gravity.LEFT or Gravity.TOP,
                    ),
                )
                addView(
                    appIcon,
                    FrameLayout.LayoutParams(
                        appIconSlotSize,
                        appIconSlotSize,
                        Gravity.LEFT or Gravity.TOP,
                    ),
                )
            }
            val officialContainer = FrameLayout(targetContext).apply {
                background = null
                clipChildren = false
                clipToPadding = false
                addView(
                    officialRoot,
                    FrameLayout.LayoutParams(
                        officialWidth,
                        sliderHeight,
                        Gravity.TOP or Gravity.CENTER_HORIZONTAL,
                    ),
                )
            }
            var overlaysPositioned = false
            officialContainer.addOnLayoutChangeListener { container, _, _, _, _, _, _, _, _ ->
                if (overlaysPositioned || official.slider.height <= 0 || container.height <= 0) {
                    return@addOnLayoutChangeListener
                }
                val sliderLocation = IntArray(2)
                val containerLocation = IntArray(2)
                official.slider.getLocationOnScreen(sliderLocation)
                officialContainer.getLocationOnScreen(containerLocation)
                val rawSliderTop = sliderLocation[1] - containerLocation[1]
                val rawSliderBottom = rawSliderTop + official.slider.height
                check(rawSliderBottom > rawSliderTop) {
                    "Official slider bounds are empty top=$rawSliderTop bottom=$rawSliderBottom"
                }
                // 自定义顶部/底部覆盖元素使用同一个 inset，并相对完整官方材质边界定位，
                // 避免 slider 内部不对称 padding 让底部看起来明显更宽。
                val left = (container.width - appIconSlotSize) / 2
                moreButton.layoutParams = FrameLayout.LayoutParams(
                    appIconSlotSize,
                    appIconSlotSize,
                    Gravity.LEFT or Gravity.TOP,
                ).apply {
                    leftMargin = left
                    topMargin = rawSliderTop + overlayVerticalInset
                }
                appIcon.layoutParams = FrameLayout.LayoutParams(
                    appIconSlotSize,
                    appIconSlotSize,
                    Gravity.LEFT or Gravity.TOP,
                ).apply {
                    leftMargin = left
                    topMargin = rawSliderBottom - appIconSlotSize - overlayVerticalInset
                }
                overlaysPositioned = true
                moreButton.requestLayout()
                appIcon.requestLayout()
                log(
                    Log.DEBUG,
                    TAG,
                    "Official overlay slots top=${rawSliderTop} bottom=${rawSliderBottom} " +
                            "more=${moreButton.top} icon=${appIcon.top}",
                    null,
                )
            }
            val wrapper = FrameLayout(targetContext).apply {
                background = null
                // 不裁剪官方展开列：完整保留 slider 背景材质、圆角轮廓及上下 padding。
                clipChildren = false
                clipToPadding = false
                addView(
                    officialContainer,
                    FrameLayout.LayoutParams(
                        officialWidth,
                        sliderHeight,
                        Gravity.CENTER,
                    ),
                )
            }
            check(pixelSizes.actionSize <= wrapperWidth) {
                "More-button slot does not fit package=${row.state.packageName} width=$wrapperWidth"
            }
            // 列始终保持完整状态（alpha=1、scale=1、无分层位移）：打开/展开动画改为
            // 整个面板内容（音量条 + 更多按钮 + 应用图标）作为整体由 panel.alpha 与面板
            // morph 驱动，不再对音量列做分层错峰，保证三者整体连贯。
            applyOfficialColumnLayers(1f)
            return AppColumnBuild(
                packageName = row.state.packageName,
                view = wrapper,
                columnWidth = wrapperWidth,
                columnHeight = sliderHeight,
            )
        }

        private fun sliderContainer(context: Context): ViewGroup = FrameLayout(context).apply {
            background = null
            clipChildren = false
            clipToPadding = false
        }

        private fun buildDevicePage(app: LoadedAppRow, devices: List<AudioOutputDevice>): View {
            releaseOfficialColumns()
            // 设备选择页不再并排展示一个音量列（否则会像官方音量面板折叠时左侧残留半截音量条），
            // 参考官方音量面板的"仅内容"展开方式：顶部 header + 全宽设备列表，音量调节回到概览页。
            val backToOverview = {
                selectedPackage = null
                val snapshot = lastSnapshot
                if (snapshot == null) {
                    log(Log.ERROR, TAG, "Cannot return to overview without a snapshot", null)
                } else {
                    val apps = loadSnapshot(snapshot).apps
                    val appsPage = buildAppsPage(apps)
                    // 返回概览与进入方向相反：从设备页形态收缩回 apps 页形态。
                    val from = currentPanelRect()
                    val to = appsPanelExpandedRect(
                        apps.size,
                        appsPage.columnWidth,
                        appsPage.columnHeight,
                    )
                    appsRenderGeneration += 1L
                    replacePage(appsPage.view, forward = false, animate = false)
                    activeAppsPage = appsPage
                    renderedAppPackages = appsPage.columnViews.keys
                    startPanelResizeAnimation(from, to) {
                        // 动画落定后把正式动画规格恢复为 apps 页形态，并精确对齐目标矩形。
                        configureAppsPanel(apps.size, appsPage.columnWidth, appsPage.columnHeight)
                        animationFraction = 1f
                        applyPanelRect(
                            SystemUiIndependentPanelPolicy.interpolateRect(
                                animationSpec,
                                1f
                            )
                        )
                    }
                }
            }
            val rowsContent = LinearLayout(targetContext).apply {
                orientation = LinearLayout.VERTICAL
                // 底部不额外留白（最后一行卡片底 → 页面 bottom padding），让最后一行
                // 卡片到面板底部的距离与卡片之间间距一致，不再"边框下方距离割裂"。
                setPadding(
                    dp(DEVICE_PAGE_HORIZONTAL_PADDING_DP),
                    dp(DEVICE_PAGE_ROW_SPACING_DP),
                    dp(DEVICE_PAGE_HORIZONTAL_PADDING_DP),
                    0
                )
            }
            deviceRows.build(
                scan = AudioDeviceScan(devices, null),
                rule = app.protocolRow.asRule(),
                followSystemName = moduleContext.getString(R.string.output_follow_system),
                builtinName = moduleContext.getString(R.string.output_device_builtin),
            ).forEach { deviceRow ->
                rowsContent.addView(
                    buildDeviceRow(app, deviceRow),
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(DEVICE_ROW_HEIGHT_DP)
                    ).apply { topMargin = dp(DEVICE_PAGE_ROW_SPACING_DP) },
                )
            }
            // 列表容器：内容超出时正常滚动；内容不足时也能上下拖动并松手回弹，
            // 保留"拖动列表"的手感（用户要求：哪怕页面没超出也能拖动）。
            // 用标准 onInterceptTouchEvent 模式：只有纵向移动超过 touch slop 才接管拖动，
            // 无移动的按下/松开仍交给设备行处理点击（切换设备），两者互不干扰。
            val rowsScroll = object : ScrollView(targetContext) {
                private var downY = 0f
                private var contentDragging = false
                private val touchSlop = ViewConfiguration.get(targetContext).scaledTouchSlop

                private fun contentOverflow(): Boolean {
                    val contentView = getChildAt(0)
                    return contentView != null && contentView.height > height
                }

                override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
                    if (contentOverflow()) return super.onInterceptTouchEvent(event)
                    // 内容未超出：只有纵向移动超过 touch slop 才拦截，接管拖动。
                    return when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            downY = event.y
                            contentDragging = false
                            false
                        }

                        MotionEvent.ACTION_MOVE ->
                            if (!contentDragging && kotlin.math.abs(event.y - downY) > touchSlop) {
                                contentDragging = true
                                true
                            } else {
                                false
                            }

                        else -> false
                    }
                }

                @SuppressLint("ClickableViewAccessibility")
                override fun onTouchEvent(event: MotionEvent): Boolean {
                    if (contentOverflow() || !contentDragging) return super.onTouchEvent(event)
                    // 内容未超出且已接管拖动：拖动内容 view，松手回弹。
                    return when (event.actionMasked) {
                        MotionEvent.ACTION_MOVE -> {
                            val contentView = getChildAt(0)
                            // 半阻力，避免拖太远。
                            contentView?.translationY = (event.y - downY) * 0.5f
                            true
                        }

                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            val contentView = getChildAt(0)
                            contentView?.animate()
                                ?.translationY(0f)
                                ?.setDuration(220L)
                                ?.setInterpolator(android.view.animation.DecelerateInterpolator())
                                ?.start()
                            contentDragging = false
                            true
                        }

                        else -> true
                    }
                }
            }.apply {
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(rowsContent)
            }
            return LinearLayout(targetContext).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                background = null
                setPadding(
                    dp(DEVICE_PAGE_HORIZONTAL_PADDING_DP),
                    dp(PANEL_VERTICAL_PADDING_DP),
                    dp(DEVICE_PAGE_HORIZONTAL_PADDING_DP),
                    dp(DEVICE_PAGE_ROW_SPACING_DP)
                )
                // 顶部 header：返回按钮 + 应用图标 + 应用名，左右对称，标题视觉居中。
                addView(
                    LinearLayout(targetContext).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        background = null
                        // 左侧返回按钮：36dp 内图标适当放大，避免 padding 过大导致箭头过小。
                        // marginStart 把返回按钮左缘推到与设备列表行（圆角条）左缘对齐，
                        // 而不是贴着页面 padding 偏左一截。
                        addView(
                            buildIconButton(
                                resolvePluginDrawable(BACK_ICON_NAMES),
                                moduleContext.getString(R.string.panel_back),
                                backToOverview,
                            ).apply {
                                setPadding(dp(6), dp(6), dp(6), dp(6))
                            },
                            LinearLayout.LayoutParams(
                                dp(HEADER_ACTION_SIZE_DP),
                                dp(HEADER_ACTION_SIZE_DP)
                            ).apply {
                                marginStart = dp(DEVICE_PAGE_HORIZONTAL_PADDING_DP)
                            },
                        )
                        // 中间标题区：图标 + 名称，weight 撑满并居中，右侧用等宽占位保证对称。
                        addView(
                            LinearLayout(targetContext).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = Gravity.CENTER
                                background = null
                                addView(
                                    ImageView(targetContext).apply {
                                        setImageDrawable(app.icon)
                                        imageTintList = null
                                        background = null
                                        scaleType = ImageView.ScaleType.CENTER_INSIDE
                                        setPadding(dp(2), dp(2), dp(2), dp(2))
                                    },
                                    LinearLayout.LayoutParams(
                                        dp(HEADER_ICON_SIZE_DP),
                                        dp(HEADER_ICON_SIZE_DP)
                                    )
                                )
                                addView(
                                    TextView(targetContext).apply {
                                        text = app.state.label
                                        setTextColor(Color.WHITE)
                                        textSize = 16f
                                        maxLines = 1
                                        gravity = Gravity.CENTER_VERTICAL
                                    },
                                    LinearLayout.LayoutParams(
                                        ViewGroup.LayoutParams.WRAP_CONTENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    ).apply {
                                        marginStart = dp(8)
                                    })
                            },
                            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f),
                        )
                        // 右侧等宽占位，让标题真正居中；与左侧返回按钮的 marginStart 对称。
                        addView(
                            View(targetContext),
                            LinearLayout.LayoutParams(
                                dp(HEADER_ACTION_SIZE_DP),
                                dp(HEADER_ACTION_SIZE_DP)
                            ).apply {
                                marginEnd = dp(DEVICE_PAGE_HORIZONTAL_PADDING_DP)
                            },
                        )
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(HEADER_ACTION_SIZE_DP)
                    ).apply {
                        // 标题栏整体下移一点，避免贴顶显得拥挤。
                        topMargin = dp(DEVICE_PAGE_ROW_SPACING_DP)
                    },
                )
                addView(
                    rowsScroll, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1f,
                    ).apply { topMargin = dp(DEVICE_PAGE_ROW_SPACING_DP) })
            }
        }

        private fun buildDeviceRow(app: LoadedAppRow, row: DevicePageRow): View {
            val selectedColor = Color.argb(235, 255, 255, 255)
            val idleColor = Color.argb(48, 255, 255, 255)
            val textColor = when {
                !row.enabled -> Color.argb(110, 255, 255, 255)
                row.selected -> Color.rgb(17, 17, 17)
                else -> Color.WHITE
            }
            return LinearLayout(targetContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isEnabled = row.enabled
                alpha = if (row.enabled) 1f else 0.62f
                background = roundedBackground(
                    if (row.selected) selectedColor else idleColor,
                    dp(22).toFloat()
                )
                // 行内左右留白用独立常量（16dp），保证图标与圆角边框之间有足够呼吸距离；
                // 页面横向边框（DEVICE_PAGE_HORIZONTAL_PADDING_DP）保持较窄。
                setPadding(
                    dp(DEVICE_ROW_HORIZONTAL_PADDING_DP),
                    0,
                    dp(DEVICE_ROW_HORIZONTAL_PADDING_DP),
                    0
                )
                addView(ImageView(targetContext).apply {
                    setImageDrawable(resolveDeviceIcon(row))
                    imageTintList = ColorStateList.valueOf(
                        if (row.selected) Color.rgb(52, 120, 246) else textColor,
                    )
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                }, LinearLayout.LayoutParams(dp(28), dp(28)))
                addView(
                    TextView(targetContext).apply {
                        text = row.name
                        setTextColor(textColor)
                        textSize = 16f
                        maxLines = 1
                        gravity = Gravity.CENTER_VERTICAL
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                        .apply { marginStart = dp(12) })
                setOnClickListener {
                    if (!row.enabled) return@setOnClickListener
                    val target =
                        checkNotNull(row.clickTarget) { "Disconnected device row must not be clickable" }
                    submitRoute(app, target)
                }
            }
        }

        private fun replacePage(next: View, forward: Boolean, animate: Boolean) {
            val previous = currentPage
            if (!animate || previous == null) {
                previous?.let(pageHost::removeView)
                pageHost.removeAllViews()
                pageHost.addView(
                    next,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                currentPage = next
                return
            }
            val distance = (pageHost.width.takeIf { it > 0 } ?: panel.width).toFloat()
                .coerceAtLeast(dp(120).toFloat())
            val direction = if (forward) 1f else -1f
            next.translationX = distance * direction
            next.alpha = 0f
            pageHost.addView(
                next,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            previous.animate().cancel()
            next.animate().cancel()
            previous.animate()
                .translationX(-distance * direction * 0.35f)
                .alpha(0f)
                .setDuration(PAGE_ANIMATION_DURATION_MILLIS)
                .setInterpolator(EXPAND_INTERPOLATOR)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        previous.animate().setListener(null)
                        pageHost.removeView(previous)
                    }
                })
                .start()
            next.animate()
                .translationX(0f)
                .alpha(1f)
                .setDuration(PAGE_ANIMATION_DURATION_MILLIS)
                .setInterpolator(EXPAND_INTERPOLATOR)
                .start()
            currentPage = next
        }

        /**
         * 转发用户活动给官方 VolumePanelViewController，重置其自动收回超时。
         *
         * 官方展开面板在每次用户交互（拖滑块、点击等）时通过 rescheduleTimeoutH()
         * 重置超时计时器（展开状态约 5000ms）。独立面板接管了交互但不触发官方
         * 的 rescheduleTimeoutH，导致原始超时按时触发 dismissH(TIMEOUT)，
         * 使面板比官方更早收回。此方法在用户交互时调用官方 rescheduleTimeoutH
         * 以保持一致的自动收回节奏。
         */
        private fun rescheduleOfficialDismissTimeout() {
            try {
                rescheduleOfficialTimeout()
            } catch (throwable: Throwable) {
                log(Log.WARN, TAG, "Failed to reschedule official dismiss timeout", throwable)
            }
        }

        private fun submitVolume(state: SystemUiBuiltinAppRowState, percent: Int) {
            require(percent in 0..100) { "percent must be in 0..100" }
            rescheduleOfficialDismissTimeout()
            val shouldSchedule = synchronized(volumeSubmissionLock) {
                // 拖动事件可能远快于跨进程 Provider 调用。每个 UID 只保留尚未下发的最新等级，
                // 避免把几十个历史位置排进单线程 executor，造成手指已经移动但声音仍在追赶旧值。
                pendingVolumeSubmissions[state.uid] = PendingVolumeSubmission(state, percent)
                drainingVolumeUids.add(state.uid)
            }
            if (!shouldSchedule) return
            val taskGeneration = generation.get()
            executeBridge(taskGeneration, "set live volume uid=${state.uid}") {
                try {
                    drainLatestVolumeSubmissions(state.uid, taskGeneration)
                } finally {
                    synchronized(volumeSubmissionLock) {
                        pendingVolumeSubmissions.remove(state.uid)
                        drainingVolumeUids.remove(state.uid)
                    }
                }
            }
        }

        private fun drainLatestVolumeSubmissions(uid: Int, taskGeneration: Long) {
            while (SystemUiGenerationGate.accepts(
                    closed.get(),
                    generation.get(),
                    taskGeneration,
                )
            ) {
                val submission = synchronized(volumeSubmissionLock) {
                    pendingVolumeSubmissions.remove(uid).also { next ->
                        if (next == null) drainingVolumeUids.remove(uid)
                    }
                } ?: return
                panelBridge.setVolume(
                    submission.state.packageName,
                    submission.state.uid,
                    submission.percent,
                )
                lastFingerprint = null
            }
        }

        private fun submitRoute(app: LoadedAppRow, target: OutputTarget) {
            rescheduleOfficialDismissTimeout()
            val taskGeneration = generation.get()
            executeBridge(taskGeneration, "set route uid=${app.state.uid}") {
                panelBridge.setRoute(app.state.packageName, app.state.uid, target)
                lastFingerprint = null
                val snapshot = panelBridge.snapshot()
                val loaded = loadSnapshot(snapshot)
                postToPanel(taskGeneration) { renderSnapshot(loaded) }
            }
        }

        private fun executeBridge(taskGeneration: Long, operation: String, action: () -> Unit) {
            try {
                executor.execute {
                    try {
                        if (!SystemUiGenerationGate.accepts(
                                closed.get(),
                                generation.get(),
                                taskGeneration
                            )
                        ) return@execute
                        action()
                    } catch (throwable: Throwable) {
                        log(Log.ERROR, TAG, "Unable to $operation", throwable)
                        requestOverlayFallback(taskGeneration, "$operation failure")
                    }
                }
            } catch (throwable: Throwable) {
                if (!closed.get()) {
                    log(Log.ERROR, TAG, "Panel executor rejected $operation", throwable)
                    requestOverlayFallback(taskGeneration, "$operation rejected")
                }
            }
        }

        private fun requestOverlayFallback(taskGeneration: Long, reason: String) {
            postToPanel(taskGeneration) {
                if (!SystemUiFallbackPolicy.shouldRequest(
                        closed = closed.get(),
                        currentGeneration = generation.get(),
                        resultGeneration = taskGeneration,
                        alreadyRequested = fallbackRequested.get(),
                    )
                ) return@postToPanel
                if (!fallbackRequested.compareAndSet(false, true)) return@postToPanel
                close(reason) {
                    try {
                        openOverlay()
                    } catch (throwable: Throwable) {
                        log(
                            Log.ERROR,
                            TAG,
                            "Panel overlay fallback failed reason=$reason",
                            throwable
                        )
                    }
                }
            }
        }

        private fun postToPanel(taskGeneration: Long, action: () -> Unit) {
            if (!SystemUiGenerationGate.accepts(
                    closed.get(),
                    generation.get(),
                    taskGeneration
                )
            ) return
            val target = if (::panel.isInitialized && panel.isAttachedToWindow) panel else dialog
            if (!target.isAttachedToWindow) {
                log(
                    Log.WARN,
                    TAG,
                    "Dropped panel UI result because volume window is detached",
                    null
                )
                return
            }
            val accepted = target.post {
                if (!SystemUiGenerationGate.accepts(
                        closed.get(),
                        generation.get(),
                        taskGeneration
                    )
                ) return@post
                try {
                    action()
                } catch (throwable: Throwable) {
                    log(Log.ERROR, TAG, "Panel UI action failed", throwable)
                    requestOverlayFallback(taskGeneration, "render failure")
                }
            }
            if (!accepted) log(Log.ERROR, TAG, "Volume View rejected panel UI result", null)
        }

        /** 由官方 dismissH 触发的清理入口；官方状态机已运行，不能递归请求关闭。 */
        fun closeForOfficialDismiss(reason: Int) {
            when (SystemUiOfficialDismissSessionPolicy.action(reason, closed.get())) {
                SystemUiOfficialDismissSessionAction.IGNORE_ALREADY_CLOSING -> {
                    log(
                        Log.DEBUG,
                        TAG,
                        "Ignored official dismiss for closing independent panel reason=$reason",
                        null,
                    )
                }

                SystemUiOfficialDismissSessionAction.CLOSE_FROM_OFFICIAL_IMMEDIATELY -> {
                    log(
                        Log.INFO,
                        TAG,
                        "Immediately closing independent panel from official dismissH reason=$reason",
                        null
                    )
                    closeImmediately(reason = "official dismiss reason=$reason")
                }

                SystemUiOfficialDismissSessionAction.CLOSE_FROM_OFFICIAL_ANIMATED -> {
                    log(
                        Log.INFO,
                        TAG,
                        "Animating independent panel close from official dismissH reason=$reason",
                        null
                    )
                    closeFromOfficialDismiss(reason)
                }
            }
        }

        /**
         * 官方 timeout/touch 等关闭已经启动后，自建 panel 只播放自身隐藏动画并清理；不能重新
         * 调用 controller dismissH，也不能把官方刚隐藏的 dialog 恢复成折叠侧边栏。
         */
        private fun closeFromOfficialDismiss(reason: Int) {
            if (!closed.compareAndSet(false, true)) {
                log(
                    Log.DEBUG,
                    TAG,
                    "Ignored external animated close after close started reason=$reason",
                    null
                )
                return
            }
            val dismissGeneration = generation.incrementAndGet()
            executor.shutdownNow()
            if (!::panel.isInitialized || panel.parent == null || !panel.isAttachedToWindow || !openAnimationStarted) {
                awaitExternalOfficialDismissCompletion(reason, dismissGeneration)
                return
            }
            startHideSlideAwayAnimation {
                awaitExternalOfficialDismissCompletion(reason, dismissGeneration)
            }
        }

        /**
         * 外部官方 dismiss 通过 VolumePanelDialog.dismiss() detach Window；以 dialog.isShown=false
         * 作为完成信号，避免读取仍可能 VISIBLE 的旧 parent 而错误拖到超时分支。
         */
        private fun awaitExternalOfficialDismissCompletion(reason: Int, dismissGeneration: Long) {
            val startedAt = android.os.SystemClock.uptimeMillis()
            val poll = object : Runnable {
                override fun run() {
                    val elapsed = android.os.SystemClock.uptimeMillis() - startedAt
                    when (
                        SystemUiIndependentPanelPolicy.externalOfficialDismissCompletionAction(
                            dialogShown = dialog.isShown,
                            elapsedMillis = elapsed,
                            timeoutMillis = OFFICIAL_DISMISS_COMPLETION_TIMEOUT_MILLIS,
                        )
                    ) {
                        SystemUiOfficialDismissCompletionAction.WAIT ->
                            uiHandler.postDelayed(this, OFFICIAL_DISMISS_POLL_INTERVAL_MILLIS)

                        SystemUiOfficialDismissCompletionAction.COMPLETE -> {
                            if (dismissGeneration != generation.get()) return
                            // 官方父容器已隐藏，恢复自建前 dialog 状态不会回显侧边栏，却能确保下次
                            // showH 恢复 alpha、visibility 和可访问性，不会留下不可点击的窗口状态。
                            finishClose(
                                reason = "official dismiss reason=$reason",
                                afterClosed = null,
                                dismissGeneration = dismissGeneration,
                                requestOfficialDismiss = false,
                                restoreOriginalDialogState = SystemUiExternalDismissCompletionPolicy
                                    .restoreOriginalDialogState(
                                        SystemUiOfficialDismissCompletionAction.COMPLETE,
                                    ),
                            )
                        }

                        SystemUiOfficialDismissCompletionAction.FORCE_COMPLETE -> {
                            log(
                                Log.ERROR,
                                TAG,
                                "External official dismiss dialog remained shown beyond " +
                                        "${OFFICIAL_DISMISS_COMPLETION_TIMEOUT_MILLIS}ms",
                                null,
                            )
                            if (dismissGeneration != generation.get()) return
                            // ROM 未完成官方隐藏时保持 dialog/shadow 不可见，避免透明窗口吞触摸。
                            finishClose(
                                reason = "official dismiss reason=$reason",
                                afterClosed = null,
                                dismissGeneration = dismissGeneration,
                                requestOfficialDismiss = false,
                                restoreOriginalDialogState = SystemUiExternalDismissCompletionPolicy
                                    .restoreOriginalDialogState(
                                        SystemUiOfficialDismissCompletionAction.FORCE_COMPLETE,
                                    ),
                            )
                        }
                    }
                }
            }
            uiHandler.post(poll)
        }

        fun close(reason: String, afterClosed: (() -> Unit)? = null) {
            if (!closed.compareAndSet(false, true)) {
                log(
                    Log.DEBUG,
                    TAG,
                    "Ignored duplicate independent panel close reason=$reason",
                    null
                )
                return
            }
            val dismissGeneration = generation.incrementAndGet()
            executor.shutdownNow()
            if (!::panel.isInitialized || panel.parent == null || !panel.isAttachedToWindow || !openAnimationStarted) {
                finishClose(reason, afterClosed, dismissGeneration)
                return
            }
            // 先复刻官方 VolumeShowHideAnimator.hide 的 panel 侧滑出，再把已在屏幕外的
            // panel 保持住；host 仍保留到官方 dismissH completion，避免官方阴影短暂露出。
            startHideSlideAwayAnimation {
                finishClose(reason, afterClosed, dismissGeneration)
            }
        }

        /**
         * 自建 panel 的关闭动画只负责把 panel 滑出屏幕；动画结束后不重置 transform，
         * 直到官方 dismissH completion 触发 cleanupAndComplete。
         */
        private fun startHideSlideAwayAnimation(onEnd: () -> Unit) {
            morphAnimator?.cancel()
            resizeAnimator?.cancel()
            slideAwayAnimator?.cancel()
            val panelRect = currentPanelRect()
            slideAwayAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = hideSpringDurationMillis
                interpolator = hideSpringInterpolator
                addUpdateListener { animator ->
                    val transform = SystemUiIndependentPanelPolicy.dismissTransform(
                        panel = panelRect,
                        rootWidth = rootWidth(),
                        fraction = animator.animatedValue as Float,
                    )
                    panel.translationX = transform.translationX
                    panel.translationY = transform.translationY
                    panel.scaleX = transform.scale
                    panel.scaleY = transform.scale
                }
                addListener(object : AnimatorListenerAdapter() {
                    private var cancelled = false

                    override fun onAnimationCancel(animation: Animator) {
                        cancelled = true
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        if (!cancelled) {
                            log(
                                Log.DEBUG,
                                TAG,
                                "Independent panel hide animation completed; awaiting official dismissH",
                                null
                            )
                            onEnd()
                        }
                    }
                })
                start()
            }
        }

        /**
         * 参考官方 VolumeExpandCollapsedAnimator.expand：以尺寸/位置状态过渡的方式
         * 把面板从 [from] 展开到 [to]（进入设备选择页），不改变 panel 透明度。
         */
        private fun startPanelResizeAnimation(
            from: SystemUiPanelRect,
            to: SystemUiPanelRect,
            onEnd: (() -> Unit)? = null,
        ) {
            morphAnimator?.cancel()
            resizeAnimator?.cancel()
            resizeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = EXPAND_ANIMATION_DURATION_MILLIS
                interpolator = EXPAND_INTERPOLATOR
                addUpdateListener { animator ->
                    val fraction = animator.animatedValue as Float
                    animationFraction = fraction
                    applyPanelRect(
                        SystemUiIndependentPanelPolicy.interpolateRect(
                            SystemUiIndependentPanelAnimationSpec(from, to),
                            fraction,
                        ),
                    )
                    // 页面内容随面板 resize 同步整体过渡（参考官方 VolumeExpandCollapsedAnimator
                    // 的 SIZE/POSITION/COLOR 整体插值）：新页面整体淡入 + 轻微放大，与面板
                    // 尺寸/位置变化同步，不做列分层，保证音量条/按钮/图标整体连贯。
                    currentPage?.let { page ->
                        page.alpha = fraction
                        page.scaleX = 0.96f + 0.04f * fraction
                        page.scaleY = 0.96f + 0.04f * fraction
                    }
                }
                addListener(object : AnimatorListenerAdapter() {
                    private var cancelled = false

                    override fun onAnimationCancel(animation: Animator) {
                        cancelled = true
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        if (!cancelled) onEnd?.invoke()
                    }
                })
                start()
            }
        }

        fun closeImmediately(
            reason: String,
            restoreOriginalDialogState: Boolean = true,
        ) {
            if (!closed.compareAndSet(false, true)) {
                log(
                    Log.DEBUG,
                    TAG,
                    "Ignored immediate close after close started reason=$reason",
                    null
                )
                return
            }
            val dismissGeneration = generation.incrementAndGet()
            executor.shutdownNow()
            finishClose(
                reason = reason,
                afterClosed = null,
                dismissGeneration = dismissGeneration,
                requestOfficialDismiss = false,
                restoreOriginalDialogState = restoreOriginalDialogState,
            )
        }

        private fun releaseOfficialColumns() {
            val copy = officialColumns.toList()
            officialColumns.clear()
            copy.forEach { column ->
                try {
                    column.release()
                } catch (throwable: Throwable) {
                    log(Log.ERROR, TAG, "Unable to release official VolumeColumn", throwable)
                }
            }
        }

        private fun finishClose(
            reason: String,
            afterClosed: (() -> Unit)?,
            dismissGeneration: Long,
            requestOfficialDismiss: Boolean = true,
            restoreOriginalDialogState: Boolean = true,
        ) {
            val closeState = SystemUiIndependentPanelPolicy.closeState(reason)
            check(closeState.terminal) { "Independent panel close must be terminal" }
            if (requestOfficialDismiss && closeState.dismissOfficialSession) {
                if (!officialDismissCompletion.begin(dismissGeneration)) {
                    log(
                        Log.ERROR,
                        TAG,
                        "Official dismiss already started or session generation changed: $dismissGeneration",
                        null,
                    )
                    return
                }
                morphAnimator?.cancel()
                resizeAnimator?.cancel()
                try {
                    // 复用官方完整关闭路线（多入口：view-controller callback / dialog-event
                    // listener / hook fallback，走官方 controller 的 dismissH 状态机，保证
                    // mShowing 等状态正确，下次 showH 才能重新打开）。
                    //
                    // JADX 已确认 reason=8 会让原版关闭路径先把 dialog 的直接父 View 设为
                    // INVISIBLE，再同步完成 collapse；这是原版展开页没有 shadow 闪帧的关键。
                    // 只需保证 isShown() 前置条件成立，不移动 dialog X，也不伪造 Folme 动画。
                    dialog.visibility = View.VISIBLE
                    dialog.alpha = 0f
                    suppressOfficialShadow()
                    check(OfficialVolumeDismissBridge.dismiss(dialog, hookDismiss, log)) {
                        "All official volume dismiss entries failed"
                    }
                    awaitOfficialDismissCompletion(
                        dismissGeneration = dismissGeneration,
                        reason = reason,
                        afterClosed = afterClosed,
                        restoreOriginalDialogState = restoreOriginalDialogState,
                    )
                    log(
                        Log.INFO,
                        TAG,
                        "Official volume dismiss started; awaiting original parent invisibility",
                        null
                    )
                    return
                } catch (throwable: Throwable) {
                    log(Log.ERROR, TAG, "Official volume dismiss failed", throwable)
                    if (!officialDismissCompletion.complete(dismissGeneration, generation.get())) {
                        log(
                            Log.ERROR,
                            TAG,
                            "Unable to complete failed official dismiss lifecycle",
                            null
                        )
                        return
                    }
                }
            }
            cleanupAndComplete(reason, afterClosed, restoreOriginalDialogState)
        }

        /**
         * 等待原版 reason=8 的隐藏状态落位。
         *
         * JADX：`MiuiVolumeDialogMotion.setVolumeDialogVisible(false, …)` 直接将
         * `mVolumeView.getParent()` 设为 INVISIBLE；展开页 `collapse(false, …)` 也是先
         * 执行该步骤后同步收尾。以该父容器为完成信号可以复刻原版时序，避免自行移动 X 或
         * 等待并不存在的 Folme 动画导致 shadow 在清理前短暂复亮。
         */
        private fun awaitOfficialDismissCompletion(
            dismissGeneration: Long,
            reason: String,
            afterClosed: (() -> Unit)?,
            restoreOriginalDialogState: Boolean,
        ) {
            val startedAt = android.os.SystemClock.uptimeMillis()
            val poll = object : Runnable {
                override fun run() {
                    val elapsed = android.os.SystemClock.uptimeMillis() - startedAt
                    when (
                        SystemUiIndependentPanelPolicy.officialDismissCompletionAction(
                            dialogParentVisible = (dialog.parent as? View)?.visibility == View.VISIBLE,
                            elapsedMillis = elapsed,
                            timeoutMillis = OFFICIAL_DISMISS_COMPLETION_TIMEOUT_MILLIS,
                        )
                    ) {
                        SystemUiOfficialDismissCompletionAction.WAIT ->
                            uiHandler.postDelayed(this, OFFICIAL_DISMISS_POLL_INTERVAL_MILLIS)

                        SystemUiOfficialDismissCompletionAction.COMPLETE,
                        SystemUiOfficialDismissCompletionAction.FORCE_COMPLETE -> {
                            if (elapsed >= OFFICIAL_DISMISS_COMPLETION_TIMEOUT_MILLIS) {
                                log(
                                    Log.ERROR,
                                    TAG,
                                    "Official dismiss parent remained visible beyond ${OFFICIAL_DISMISS_COMPLETION_TIMEOUT_MILLIS}ms",
                                    null,
                                )
                            }
                            if (officialDismissCompletion.complete(
                                    dismissGeneration,
                                    generation.get()
                                )
                            ) {
                                cleanupAndComplete(reason, afterClosed, restoreOriginalDialogState)
                            }
                        }
                    }
                }
            }
            // 让 controller dismiss 完整执行完当前主线程消息后再观察父容器状态。
            uiHandler.post(poll)
        }

        /**
         * 直接屏蔽官方独立 shadow。
         *
         * JADX：MiuiVolumeDialogMotion 在 setContentView 中通过 rootView.findViewById(R.id.shadow)
         * 取得该 View，它不是 MiuiVolumeDialogView 的子项，dialog.alpha 无法覆盖它。关闭前将
         * shadow 隐藏；reason=8 把 dialog 父容器设为 INVISIBLE 后再恢复 visibility，但保持
         * alpha=0，由官方下一次 show 的 preDraw 初始化并动画显示。
         */
        private fun suppressOfficialShadow() {
            if (officialShadowSuppression != null) return
            val shadow = findOfficialShadow(dialog.rootView)
            if (shadow == null) {
                log(Log.ERROR, TAG, "Official shadow view was not found in volume root", null)
                return
            }
            officialShadowSuppression = OfficialShadowSuppression(
                view = shadow,
                originalVisibility = shadow.visibility,
                originalAlpha = shadow.alpha,
            )
            shadow.alpha = 0f
            shadow.visibility = View.INVISIBLE
            log(Log.DEBUG, TAG, "Official shadow suppressed", null)
        }

        private fun findOfficialShadow(root: View): View? {
            if (root.id != View.NO_ID) {
                val entryName = runCatching {
                    root.resources.getResourceEntryName(root.id)
                }.getOrNull()
                if (entryName == "shadow") return root
            }
            if (root !is ViewGroup) return null
            for (index in 0 until root.childCount) {
                findOfficialShadow(root.getChildAt(index))?.let { return it }
            }
            return null
        }

        private fun restoreOfficialShadowAfterDismiss(keepHiddenWhileParentVisible: Boolean) {
            val suppression = officialShadowSuppression ?: return
            officialShadowSuppression = null
            try {
                val parentVisible = (dialog.parent as? View)?.visibility == View.VISIBLE
                when (
                    SystemUiOfficialShadowPolicy.action(
                        externalDismiss = keepHiddenWhileParentVisible,
                        dialogParentVisible = parentVisible,
                    )
                ) {
                    SystemUiOfficialShadowAction.KEEP_INVISIBLE -> {
                        // 外部 dismiss 超时后父容器仍可见属于 ROM 状态机异常；shadow 绝不能作为透明
                        // 全屏遮罩继续拦截触摸，保持 INVISIBLE 交给下一次官方 show 重建。
                        suppression.view.alpha = 0f
                        suppression.view.visibility = View.INVISIBLE
                    }

                    SystemUiOfficialShadowAction.RESTORE -> {
                        suppression.view.visibility = suppression.originalVisibility
                        // 父容器已被原版 reason=8 隐藏时，保持 alpha=0 交给下一次官方 show 的 preDraw；
                        // mount 失败等未进入原版 dismiss 的路径则还原原 alpha，避免遗留不可见 shadow。
                        suppression.view.alpha =
                            if (parentVisible) suppression.originalAlpha else 0f
                    }
                }
            } catch (throwable: Throwable) {
                log(Log.ERROR, TAG, "Unable to restore official shadow visibility", throwable)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        private fun cleanupAndComplete(
            reason: String,
            afterClosed: (() -> Unit)?,
            restoreOriginalDialogState: Boolean,
        ) {
            if (!closeFinalized.compareAndSet(false, true)) {
                log(
                    Log.WARN,
                    TAG,
                    "Ignored duplicate independent panel finalization reason=$reason",
                    null
                )
                return
            }
            releaseOfficialColumns()
            try {
                if (::panel.isInitialized) {
                    morphAnimator?.cancel()
                    resizeAnimator?.cancel()
                    slideAwayAnimator?.cancel()
                    if (::expandedMaterial.isInitialized) expandedMaterial.clear(panel)
                    panel.removeAllViews()
                    panel.background = null
                }
                if (::host.isInitialized) {
                    (host.parent as? ViewGroup)?.removeView(host)
                    host.setOnTouchListener(null)
                    host.removeAllViews()
                    host.background = null
                }
            } catch (throwable: Throwable) {
                log(Log.ERROR, TAG, "Unable to remove independent full-window host", throwable)
            }
            try {
                touchInsets.restore()
            } catch (throwable: Throwable) {
                log(
                    Log.ERROR,
                    TAG,
                    "Unable to restore official touchable-region listener",
                    throwable,
                )
            }
            try {
                if (restoreOriginalDialogState) {
                    dialog.alpha = originalAlpha
                    dialog.visibility = originalVisibility
                    dialog.importantForAccessibility = originalImportantForAccessibility
                }
                // 无论由谁关闭都恢复 shadow 的原始可见性记录；外部官方 dismiss 路径不能恢复
                // dialog 自身状态，否则会把已经隐藏的面板回显成折叠侧边栏。
                restoreOfficialShadowAfterDismiss(
                    keepHiddenWhileParentVisible = !restoreOriginalDialogState,
                )
            } catch (throwable: Throwable) {
                log(Log.ERROR, TAG, "Unable to finalize official dialog state", throwable)
            }
            try {
                onClosed(this)
            } catch (throwable: Throwable) {
                log(Log.ERROR, TAG, "Independent panel onClosed callback failed", throwable)
            }
            try {
                afterClosed?.invoke()
            } catch (throwable: Throwable) {
                log(Log.ERROR, TAG, "Independent panel completion callback failed", throwable)
            }
            log(Log.INFO, TAG, "Closed independent app-volume panel reason=$reason", null)
        }

        private data class OfficialShadowSuppression(
            val view: View,
            val originalVisibility: Int,
            val originalAlpha: Float,
        )

        private fun buildMoreButton(slider: SeekBar, openDetails: () -> Unit): ImageView =
            ImageView(targetContext).apply {
                // 官方 MiuiVolumeDialogMotion 的展开按钮不自行消费触摸：仅在 ACTION_DOWN 调用
                // MiuiVolumeSeekBar.doClick() 并返回 false，让同一事件继续落到下层 slider。
                setImageDrawable(resolvePluginDrawable(MORE_ICON_NAMES))
                applyOfficialExpandButtonStyle(this)
                contentDescription = moduleContext.getString(R.string.panel_more_devices)
                isClickable = false
                isFocusable = false
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(
                    dp(INNER_ICON_PADDING_DP),
                    dp(INNER_ICON_PADDING_DP),
                    dp(INNER_ICON_PADDING_DP),
                    dp(INNER_ICON_PADDING_DP),
                )
                OfficialVolumeColumn.bindOfficialExpandButtonTouch(
                    button = this,
                    slider = slider,
                    classLoader = pluginClassLoader,
                    onClick = openDetails,
                    onFailure = { throwable ->
                        log(
                            Log.ERROR,
                            TAG,
                            "Official expand-button touch bridge failed",
                            throwable,
                        )
                    },
                )
            }

        private fun buildIconButton(
            icon: Drawable,
            description: String,
            action: () -> Unit
        ): ImageView =
            ImageView(targetContext).apply {
                setImageDrawable(icon)
                imageTintList = ColorStateList.valueOf(Color.WHITE)
                contentDescription = description
                isClickable = true
                isFocusable = true
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(10), dp(10), dp(10), dp(10))
                setOnClickListener { action() }
            }

        private fun buildMessage(message: String): TextView = TextView(targetContext).apply {
            text = message
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(24), 0, dp(24), 0)
            layoutParams = LinearLayout.LayoutParams(
                dp(EMPTY_CONTENT_WIDTH_DP),
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        private fun resolveDeviceIcon(row: DevicePageRow): Drawable {
            // 参考官方 miplay 设备图标体系：
            // 跟随本机 -> ic_miplay_pc；本机 -> ic_miplay_phone；蓝牙耳机 -> ic_miplay_headset；
            // 蓝牙音响 -> ic_miplay_speaker；其他蓝牙 -> ic_miplay_default；
            // 有线耳机/其他设备 -> ic_wired_headset_microphone。
            if (row.kind == DevicePageRowKind.FOLLOW_SYSTEM) {
                return resolvePluginDrawable(
                    arrayOf(
                        "ic_miplay_pc",
                        "ic_miplay_default",
                        "ic_miui_volume_speaker"
                    )
                )
            }
            val names = when (row.type) {
                OutputDeviceType.BUILT_IN ->
                    arrayOf("ic_miplay_phone", "ic_miplay_default", "ic_miui_volume_speaker")

                OutputDeviceType.WIRED_HEADSET ->
                    arrayOf(
                        "ic_wired_headset_microphone",
                        "ic_miplay_headset",
                        "ic_miui_volume_headset"
                    )

                OutputDeviceType.BLUETOOTH -> bluetoothDeviceIconNames(row)
                OutputDeviceType.USB ->
                    arrayOf(
                        "ic_wired_headset_microphone",
                        "ic_miplay_default",
                        "ic_miui_volume_usb"
                    )

                null, OutputDeviceType.OTHER ->
                    arrayOf(
                        "ic_wired_headset_microphone",
                        "ic_miplay_default",
                        "ic_miui_volume_media"
                    )
            }
            return resolvePluginDrawable(names)
        }

        /**
         * 蓝牙设备细分图标：按设备名关键词区分耳机 / 音响 / 其他蓝牙设备。
         *
         * SoundMan 面板侧拿不到隐藏 DEVICE_OUT_* 常量做 internalType 判定，这里用
         * productName 关键词兜底（与系统对 BLE_HEADSET / BLE_SPEAKER 的直观命名一致）。
         */
        private fun bluetoothDeviceIconNames(row: DevicePageRow): Array<String> {
            val productName =
                ((row.clickTarget as? OutputTarget.Device)?.productName ?: row.name).lowercase()
            val speakerKeywords = arrayOf("speaker", "soundbar", "音箱", "音响", "sound", "bar")
            val headsetKeywords =
                arrayOf("headset", "headphone", "earphone", "earbud", "耳机", "headphones")
            return when {
                speakerKeywords.any { productName.contains(it) } && headsetKeywords.none {
                    productName.contains(
                        it
                    )
                } ->
                    arrayOf("ic_miplay_speaker", "ic_miplay_headset", "ic_miplay_default")

                headsetKeywords.any { productName.contains(it) } ->
                    arrayOf("ic_miplay_headset", "ic_miplay_speaker", "ic_miplay_default")

                else -> arrayOf("ic_miplay_default", "ic_miplay_headset", "ic_miplay_speaker")
            }
        }

        @SuppressLint("UseCompatLoadingForDrawables")
        private fun resolvePluginDrawable(names: Array<String>): Drawable {
            val cacheKey = names.joinToString("|")
            val id = pluginDrawableIdCache.getOrPut(cacheKey) {
                SystemUiVolumeEntryLayout.resourcePackages(targetContext.packageName)
                    .firstNotNullOfOrNull { packageName ->
                        names.firstNotNullOfOrNull { name ->
                            targetContext.resources.getIdentifier(name, "drawable", packageName)
                                .takeIf { it != 0 }
                        }
                    } ?: 0
            }
            if (id != 0) {
                val drawable = targetContext.resources.getDrawable(id, targetContext.theme)
                if (drawable != null) return drawable
                pluginDrawableIdCache.remove(cacheKey)
            }
            log(
                Log.WARN,
                TAG,
                "Plugin drawable missing, using default icon: ${names.joinToString()}",
                null,
            )
            return targetContext.packageManager.defaultActivityIcon
        }

        /**
         * 官方展开面板通过 MiuiVolumeDialogRes.getBgWithContentPadding(context, true) 读取
         * miui_volume_background_padding。应用音量页四边内距及列间距统一使用同一像素值，
         * 避免当前 8dp/4dp 组合显得拥挤且左右边距与元素间隔不一致。
         */
        private fun expandedPanelContentInset(): Int {
            expandedPanelContentInsetPx?.let { return it }
            val resolved = resolvePluginDimension(EXPANDED_PANEL_CONTENT_INSET_NAMES)
                ?: dp(FALLBACK_PANEL_CONTENT_INSET_DP)
            check(resolved > 0) { "Expanded panel content inset must be positive" }
            expandedPanelContentInsetPx = resolved
            log(Log.INFO, TAG, "Expanded panel content inset resolved to ${resolved}px", null)
            return resolved
        }

        /**
         * 从官方插件资源读取 dimension（像素值）。
         *
         * 参考官方 `VolumeColumnRes.getIconSize` / `getIconMarginBottom`：应用图标与更多按钮
         * 的尺寸和 bottomMargin 直接复用官方 `o3_miui_volume_icon_*`，保证与侧边音量条里的
         * 图标定位一致。全部缺失时返回 null，调用方回退自身常量。
         */
        private fun resolvePluginDimension(names: Array<String>): Int? {
            names.forEach { name ->
                SystemUiVolumeEntryLayout.resourcePackages(targetContext.packageName)
                    .forEach { packageName ->
                        val id = targetContext.resources.getIdentifier(name, "dimen", packageName)
                        if (id != 0) {
                            val value = runCatching {
                                targetContext.resources.getDimensionPixelSize(id)
                            }.onFailure { error ->
                                log(
                                    Log.WARN,
                                    TAG,
                                    "Plugin dimen load failed name=$name package=$packageName",
                                    error
                                )
                            }.getOrNull()
                            if (value != null && value > 0) return value
                        }
                    }
            }
            log(Log.WARN, TAG, "Plugin dimen missing: ${names.joinToString()}", null)
            return null
        }

        private fun roundedBackground(color: Int, radius: Float): Drawable =
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(color)
                cornerRadius = radius
            }

        /**
         * 为更多/展开按钮应用与官方完全一致的取色（jadx 逆向 MiuiVolumeDialogView）：
         *
         * - updateExpandButtonTint：bionics 高级材质下 `setImageTintList(null)`（图标用
         *   drawable 原色，不额外上色）；否则用官方颜色资源 `getExpandedIconColorRes`
         *   对应的 blur 色（普通材质路径）。
         * - initExpandButtonBlend：高级材质下 `Util.setMiViewBlurAndBlendColor(button, 3,
         *   MiuiVolumeDialogRes.getExpandedIconBlandColor())` 让图标与玻璃背景融合
         *   （miuix ColorBlendToken 渲染，SoundMan 直接调用官方工具复刻）；否则
         *   `MiBlurCompat.setMiViewBlurModeCompat(button, 0)` 关闭 blur 走静态取色。
         *
         * blend 依赖 view 已 attach 到窗口（blur 采样），未 attach 时先只做 tint，
         * attach 后再补 blend。任何一步失败都回退静态 blur 色资源，绝不让取色失败
         * 拖垮面板。
         */
        @SuppressLint("PrivateApi")
        private fun applyOfficialExpandButtonStyle(button: ImageView) {
            // bionics 判断与官方 updateExpandButtonTint 完全一致（Util.isBionicsAdvancedMaterialEnabled）。
            val bionics = runCatching {
                val util = pluginClassLoader.loadClass("com.android.systemui.miui.volume.Util")
                val method = util.methods.firstOrNull {
                    it.name == "isBionicsAdvancedMaterialEnabled" &&
                            it.parameterCount == 1 && it.parameterTypes[0] == classOf<Context>()
                }
                method?.invoke(null, targetContext) as? Boolean ?: false
            }.getOrDefault(false)
            if (bionics) {
                button.setImageTintList(null)
            } else {
                val tint = resolvePluginColor(MORE_BUTTON_COLOR_NAMES)
                    ?: Color.argb(0xCC, 0xFF, 0xFF, 0xFF)
                button.setImageTintList(ColorStateList.valueOf(tint))
            }
            if (button.isAttachedToWindow) {
                applyOfficialExpandButtonBlend(button)
            } else {
                button.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        button.removeOnAttachStateChangeListener(this)
                        applyOfficialExpandButtonBlend(button)
                    }

                    override fun onViewDetachedFromWindow(v: View) = Unit
                })
            }
        }

        /** 参考官方 MiuiVolumeDialogView.initExpandButtonBlend：高级材质走官方 blend，否则关 blur。 */
        @SuppressLint("PrivateApi")
        private fun applyOfficialExpandButtonBlend(button: ImageView) {
            val advanced = runCatching {
                val util = pluginClassLoader.loadClass("com.android.systemui.miui.volume.Util")
                val method = util.methods.firstOrNull {
                    it.name == "isAdvancedMaterialEffective" &&
                            it.parameterCount == 1 && it.parameterTypes[0] == classOf<Context>()
                }
                method?.invoke(null, targetContext) as? Boolean ?: false
            }.getOrDefault(false)
            if (!advanced) {
                runCatching {
                    val compat = pluginClassLoader.loadClass("miui.systemui.util.MiBlurCompat")
                    compat.getMethod(
                        "setMiViewBlurModeCompat",
                        classOf<View>(),
                        classOf<Int>()
                    )
                        .invoke(null, button, 0)
                }.onFailure { error ->
                    log(Log.WARN, TAG, "Official blur-off failed for expand button", error)
                }
                return
            }
            runCatching {
                val res =
                    pluginClassLoader.loadClass("com.android.systemui.miui.volume.MiuiVolumeDialogRes")
                val blend = res.getMethod("getExpandedIconBlandColor").invoke(null)
                    ?: error("getExpandedIconBlandColor returned null")
                val util = pluginClassLoader.loadClass("com.android.systemui.miui.volume.Util")
                val setBlend = util.methods.firstOrNull {
                    it.name == "setMiViewBlurAndBlendColor" &&
                            it.parameterCount == 3 &&
                            it.parameterTypes[0] == classOf<View>() &&
                            it.parameterTypes[1] == classOf<Int>()
                } ?: error("Util.setMiViewBlurAndBlendColor missing")
                setBlend.invoke(null, button, 3, blend)
            }.onFailure { error ->
                log(
                    Log.WARN,
                    TAG,
                    "Official expand-button blend failed; falling back to static tint",
                    error
                )
                // blend 失败时回到静态取色，保证图标可见。
                val tint = resolvePluginColor(MORE_BUTTON_COLOR_NAMES)
                    ?: Color.argb(0xCC, 0xFF, 0xFF, 0xFF)
                button.setImageTintList(ColorStateList.valueOf(tint))
            }
        }

        /**
         * 从官方插件资源取颜色（官方普通材质图标取色路径）。
         *
         * 参考 jadx 逆向 `MiuiVolumeDialogRes.getExpandedIconColorRes`（needShowDialog=true 返回
         * `miui_volume_expand_button_color_blur_light`）与 `MiuiVolumeDialogView.updateExpandButtonTint`
         * （把该颜色资源 setImageTintList 到展开按钮）。SoundMan 的更多按钮使用同一套官方颜色资源，
         * 保证与侧边音量条官方取色一致：优先 blur_light / blur（与玻璃背景同源的 blur 混合色），
         * 回退 `miui_volume_expand_button_color_cc` 与 `vp_o3_volume_icon_normal`。
         * 全部缺失时返回 null，调用方回退半透明白兜底。
         */
        private fun resolvePluginColor(names: Array<String>): Int? {
            names.forEach { name ->
                SystemUiVolumeEntryLayout.resourcePackages(targetContext.packageName)
                    .forEach { packageName ->
                        val id = targetContext.resources.getIdentifier(name, "color", packageName)
                        if (id != 0) {
                            val color = runCatching {
                                targetContext.resources.getColor(id, targetContext.theme)
                            }.onFailure { error ->
                                log(
                                    Log.WARN,
                                    TAG,
                                    "Plugin color load failed name=$name package=$packageName",
                                    error
                                )
                            }.getOrNull()
                            if (color != null) return color
                        }
                    }
            }
            log(Log.WARN, TAG, "Plugin color missing: ${names.joinToString()}", null)
            return null
        }

        private fun dp(value: Int): Int =
            (value * targetContext.resources.displayMetrics.density + 0.5f).toInt()

        /**
         * 反射创建官方 miuix.animation.utils.SpringInterpolator 实例。
         *
         * 官方 SpringInterpolator 构造器接受 (dampingRatio, response)，
         * 内部自动求解 spring 收束时间（solveDuration），getDuration() 返回自然 duration。
         * 我们用反射调用 setDampingAndResponse 来创建不同参数的 spring，
         * 并通过 getDuration() 获取自然收束时间作为 ValueAnimator duration。
         *
         * 反射必要性：miuix.animation.utils.SpringInterpolator 是 MIUI 私有 API，
         * 不在公开 SDK 中，只能通过 SystemUI 插件 ClassLoader 加载。
         * 已验证入口点：pluginClassLoader 可从 dialog.javaClass.classLoader 获取，
         * 无其他公开路径可到达此类。
         */
        private fun initSpringInterpolators() {
            try {
                val springClass =
                    pluginClassLoader.loadClass("miuix.animation.utils.SpringInterpolator")
                val constructor = springClass.getConstructor(
                    classOf<Float>(),
                    classOf<Float>()
                )
                val setDampingAndResponse = springClass.getMethod(
                    "setDampingAndResponse",
                    classOf<Float>(),
                    classOf<Float>()
                )
                val getDuration = springClass.getMethod("getDuration")

                // hide: spring(0.95, 0.3) — 对齐官方 VolumeShowHideAnimator.hide X 位移动画
                val hideSpring = constructor.newInstance(0.85f, 0.3f)
                setDampingAndResponse.invoke(hideSpring, 0.95f, 0.3f)
                hideSpringInterpolator = hideSpring as android.animation.TimeInterpolator
                hideSpringDurationMillis = getDuration.invoke(hideSpring) as Long

                // enter: spring(0.82, 0.4) — 对齐官方 EASE_EXPAND_SIZE
                val enterSpring = constructor.newInstance(0.82f, 0.4f)
                columnEnterSpringInterpolator = enterSpring as android.animation.TimeInterpolator
                columnEnterSpringDurationMillis = getDuration.invoke(enterSpring) as Long

                // exit: spring(0.9, 0.3) — 对齐官方 EASE_COLLAPSE_SIZE
                val exitSpring = constructor.newInstance(0.9f, 0.3f)
                columnExitSpringInterpolator = exitSpring as android.animation.TimeInterpolator
                columnExitSpringDurationMillis = getDuration.invoke(exitSpring) as Long

                log(
                    Log.DEBUG,
                    TAG,
                    "Spring interpolators initialized from official miuix: " +
                            "hide=${hideSpringDurationMillis}ms, enter=${columnEnterSpringDurationMillis}ms, exit=${columnExitSpringDurationMillis}ms",
                    null
                )
            } catch (t: Throwable) {
                log(
                    Log.ERROR,
                    TAG,
                    "Failed to create official SpringInterpolator, using fallback",
                    t
                )
            }
        }
    }

    private object OfficialVolumeDismissBridge {
        private const val OFFICIAL_DISMISS_REASON = 8
        private const val DIALOG_CONTROLLER_CLASS =
            "com.android.systemui.miui.volume.VolumePanelDialogController"
        private val cache = WeakHashMap<ViewGroup, List<DismissEntry>>()

        /**
         * 多入口官方关闭路线（走 controller 的 dismissH 状态机）：
         * 优先 view-controller callback，其次 dialog-event listener，最后 hook fallback。
         * 相比直接调用 MiuiVolumeDialogView.dismissH(boolean, Runnable)：多入口走官方
         * controller 的状态机（mShowing 等会被正确置位），下次 showH 才能重新打开；
         * 调用方随后等待 `MiuiVolumeDialogView` 的直接父容器变为 INVISIBLE，以复刻 reason=8
         * 的原版关闭收尾时序。
         */
        fun dismiss(
            dialog: ViewGroup,
            hookDismiss: () -> Boolean,
            log: (priority: Int, tag: String, message: String, throwable: Throwable?) -> Unit,
        ): Boolean {
            val entries = synchronized(cache) {
                cache[dialog] ?: bindEntries(dialog, log).also { bound ->
                    if (bound.isNotEmpty()) cache[dialog] = bound
                }
            }
            val entriesByType = entries.associateBy(DismissEntry::type)
            val order = SystemUiIndependentPanelPolicy.officialDismissOrder(
                hasViewControllerCallback = SystemUiOfficialDismissEntry.VIEW_CONTROLLER_CALLBACK in entriesByType,
                hasDialogEventListener = SystemUiOfficialDismissEntry.DIALOG_EVENT_LISTENER in entriesByType,
                hasHookController = true,
            )
            val succeeded = SystemUiOfficialDismissSequence.firstSuccessful(order) { type ->
                if (type == SystemUiOfficialDismissEntry.HOOK_CONTROLLER) {
                    val result = hookDismiss()
                    if (!result) log(Log.ERROR, TAG, "Official dismiss hook fallback failed", null)
                    result
                } else {
                    val entry = checkNotNull(entriesByType[type]) {
                        "Dismiss decision selected missing entry=$type"
                    }
                    try {
                        entry.action()
                        true
                    } catch (throwable: Throwable) {
                        log(
                            Log.ERROR,
                            TAG,
                            "Official dismiss entry failed: ${entry.name}",
                            throwable
                        )
                        false
                    }
                }
            }
            if (succeeded == null) {
                log(
                    Log.ERROR,
                    TAG,
                    "All official volume dismiss entries and hook fallback failed",
                    null
                )
                return false
            }
            val name = entriesByType[succeeded]?.name ?: "hook-captured controller"
            log(Log.INFO, TAG, "Official volume dismissed through $name", null)
            return true
        }

        private fun bindEntries(
            dialog: ViewGroup,
            log: (priority: Int, tag: String, message: String, throwable: Throwable?) -> Unit,
        ): List<DismissEntry> {
            val motionCallback = runCatching { readField(dialog, "mCallback") }
                .onFailure {
                    log(Log.ERROR, TAG, "Unable to bind MiuiVolumeDialogView motion callback", it)
                }
                .getOrNull() ?: return emptyList()
            val dialogController = runCatching {
                motionCallback.javaClass.declaredFields.firstNotNullOfOrNull { field ->
                    field.makeAccessible()
                    field.get(motionCallback)
                        ?.takeIf { it.javaClass.name == DIALOG_CONTROLLER_CLASS }
                } ?: error("VolumePanelDialogController owner was not found from motion callback")
            }.onFailure { log(Log.ERROR, TAG, "Unable to bind VolumePanelDialogController", it) }
                .getOrNull() ?: return emptyList()
            val entries = ArrayList<DismissEntry>(2)
            runCatching {
                val controllerCallback = readField(dialogController, "mCallback")
                    ?: error("VolumePanelDialogController.mCallback is null")
                val dismiss = controllerCallback.javaClass.methods.firstOrNull { method ->
                    method.name == "dismiss" && method.parameterCount == 1 &&
                            method.parameterTypes[0] == classOf<Int>()
                } ?: error("VolumePanelViewController callback dismiss(int) was not found")
                entries += DismissEntry(
                    SystemUiOfficialDismissEntry.VIEW_CONTROLLER_CALLBACK,
                    "view-controller callback",
                ) {
                    dismiss.invoke(controllerCallback, OFFICIAL_DISMISS_REASON)
                }
            }.onFailure {
                log(Log.ERROR, TAG, "Unable to bind view-controller dismiss callback", it)
            }
            runCatching {
                val dialogEventListener = readField(dialogController, "mDialogEventListener")
                    ?: error("VolumePanelDialogController.mDialogEventListener is null")
                val dismiss = dialogEventListener.javaClass.methods.firstOrNull { method ->
                    method.name == "dismiss" && method.parameterCount == 1 &&
                            method.parameterTypes[0] == classOf<Int>()
                } ?: error("VolumePanelDialog.DialogEventListener.dismiss(int) was not found")
                entries += DismissEntry(
                    SystemUiOfficialDismissEntry.DIALOG_EVENT_LISTENER,
                    "dialog-event listener",
                ) {
                    dismiss.invoke(dialogEventListener, OFFICIAL_DISMISS_REASON)
                }
            }.onFailure { log(Log.ERROR, TAG, "Unable to bind dialog-event dismiss callback", it) }
            return entries
        }

        private fun readField(owner: Any, name: String): Any? {
            var type: Class<*>? = owner.javaClass
            while (type != null) {
                val field = runCatching { type.getDeclaredField(name) }.getOrNull()
                if (field != null) {
                    field.makeAccessible()
                    return field.get(owner)
                }
                type = type.superclass
            }
            error("Field $name was not found on ${owner.javaClass.name}")
        }

        private data class DismissEntry(
            val type: SystemUiOfficialDismissEntry,
            val name: String,
            val action: () -> Unit,
        )
    }

    private class OfficialTouchInsetsRegistration(
        private val dialog: ViewGroup,
        private val log: (priority: Int, tag: String, message: String, throwable: Throwable?) -> Unit,
    ) {
        private val listenerType =
            "android.view.ViewTreeObserver\$OnComputeInternalInsetsListener".toClass()
        private val fullFrameListener = Proxy.newProxyInstance(
            dialog.javaClass.classLoader,
            arrayOf(listenerType),
        ) { proxy, method, args ->
            when (method.name) {
                "onComputeInternalInsets" -> {
                    val info = args?.singleOrNull() ?: error("InternalInsetsInfo argument missing")
                    val intType =
                        classOf<Int>()
                    info.javaClass.getMethod("setTouchableInsets", intType)
                        .invoke(info, TOUCHABLE_INSETS_FRAME)
                    null
                }

                "toString" -> "SoundManFullWindowInsetsListener"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.singleOrNull()
                else -> null
            }
        }
        private var paused = false

        fun pause() {
            when (SystemUiIndependentPanelPolicy.insetsListenerAction(paused, panelActive = true)) {
                SystemUiInsetsListenerAction.NONE -> return
                SystemUiInsetsListenerAction.REMOVE -> Unit
                SystemUiInsetsListenerAction.ADD -> error("Unexpected touch listener action while mounting")
            }
            check(listenerType.isInstance(dialog)) {
                "MiuiVolumeDialogView does not implement OnComputeInternalInsetsListener: ${dialog.javaClass.name}"
            }
            updateRegistration(dialog, add = false)
            try {
                updateRegistration(fullFrameListener, add = true)
            } catch (throwable: Throwable) {
                updateRegistration(dialog, add = true)
                throw throwable
            }
            paused = true
            requestInsetsRecompute("paused with full-window touch frame")
        }

        fun restore() {
            when (SystemUiIndependentPanelPolicy.insetsListenerAction(
                paused,
                panelActive = false
            )) {
                SystemUiInsetsListenerAction.NONE -> return
                SystemUiInsetsListenerAction.ADD -> Unit
                SystemUiInsetsListenerAction.REMOVE -> error("Unexpected touch listener action while closing")
            }
            var removalFailure: Throwable? = null
            try {
                updateRegistration(fullFrameListener, add = false)
            } catch (throwable: Throwable) {
                removalFailure = throwable
                log(Log.ERROR, TAG, "Unable to remove full-window touch listener", throwable)
            }
            updateRegistration(dialog, add = true)
            paused = false
            requestInsetsRecompute("official touch region restored")
            removalFailure?.let {
                throw IllegalStateException(
                    "Full-window touch listener removal failed",
                    it
                )
            }
        }

        private fun updateRegistration(listener: Any, add: Boolean) {
            val observer = dialog.viewTreeObserver
            check(observer.isAlive) { "MiuiVolumeDialogView ViewTreeObserver is not alive" }
            val methodName = if (add) {
                "addOnComputeInternalInsetsListener"
            } else {
                "removeOnComputeInternalInsetsListener"
            }
            try {
                classOf<ViewTreeObserver>().getMethod(methodName, listenerType)
                    .invoke(observer, listener)
            } catch (throwable: Throwable) {
                throw IllegalStateException(
                    "Unable to $methodName for ${listener.javaClass.name}",
                    throwable
                )
            }
        }

        private fun requestInsetsRecompute(reason: String) {
            dialog.requestLayout()
            dialog.rootView.requestLayout()
            dialog.rootView.invalidate()
            log(Log.DEBUG, TAG, "Internal touch insets $reason", null)
        }

        private companion object {
            const val TOUCHABLE_INSETS_FRAME = 0
        }
    }

    private class OfficialVolumeColumn private constructor(
        private val instance: Any,
        val view: View,
        val slider: SeekBar,
        private val icon: ImageView,
        private val progressView: View,
        private val progressViewBg: View,
        // OS4 的 VolumeColumn 才有 glass/expand 材质层；OS3（plugin 17.x）没有这两个字段，
        // 仅用于诊断日志，缺省为 null 不影响任何功能路径。
        private val glassBg: View?,
        private val expandBg: View?,
        private val releaseMethod: Method,
        private val updateProgressMethod: Method,
        val packageName: String,
    ) {
        fun release() {
            releaseMethod.invoke(instance)
        }

        /**
         * 原地更新音量百分比，不重建列。
         *
         * 动机：音量调整触发轮询 re-render 时，应用列表没变不应重建整个页面。
         * 通过保存的 [updateProgressMethod]（MiuiVolumeSeekBarProgressView.toProgressWithAnim）
         * 直接更新 slider 位置和填充层，避免页面重建引发收束动画。
         */
        fun updateVolume(percent: Int) {
            require(percent in 0..100) { "percent must be in 0..100" }
            slider.progress = SystemUiOfficialSliderProgress.fromPercent(percent)
            updateProgressMethod.invoke(progressView, false, slider)
        }

        fun prepareStandaloneColumn(
            packageName: String,
            log: (priority: Int, tag: String, message: String, throwable: Throwable?) -> Unit,
        ) {
            // 官方 updateColumnH 在展开态只关闭 VolumeBlurFrameLayout 的 root backdrop blur，
            // slider / progress / glass / expand 层全部保留，由 setSliderResource、
            // setSliderTintColorList 与 setSliderBlendColor 共同生成原版材质。
            val setBlurEnabled = view.javaClass.methods.firstOrNull { method ->
                method.name == "setBlurEnabled" && method.parameterCount == 1 &&
                        method.parameterTypes[0] == classOf<Boolean>()
            } ?: error("Official VolumeColumn root setBlurEnabled(boolean) was not found")
            setBlurEnabled.invoke(view, false)

            // 仅隐藏官方流类型图标，给 SoundMan 的应用图标和更多按钮让位；不改任何材质层。
            icon.setImageDrawable(null)
            icon.imageTintList = null
            icon.background = null
            icon.visibility = View.INVISIBLE
            log(
                Log.INFO,
                TAG,
                "Prepared official expanded VolumeColumn package=$packageName " +
                        "sliderBg=${slider.background?.javaClass?.name ?: "material"} " +
                        "progress=${progressView.background?.javaClass?.name ?: "material"} " +
                        "progressBg=${progressViewBg.background?.javaClass?.name ?: "none"} " +
                        "glass=${glassBg?.background?.javaClass?.name ?: "none"} " +
                        "expand=${expandBg?.background?.javaClass?.name ?: "none"}",
                null,
            )
        }

        companion object {
            /**
             * 一比一复制官方 MiuiVolumeDialogMotion 的展开按钮触摸链：
             *
             * 1. processExpandTouch 给 MiuiVolumeSeekBar 安装 SeekBarOnclickListener；
             * 2. 展开按钮仅在 ACTION_DOWN 标记来源并调用 slider.doClick()；
             * 3. 按钮 OnTouch 始终返回 false，同一手势继续分发给位于下层的 slider；
             * 4. MiuiVolumeSeekBar.doClick(MotionEvent) 自己用 200ms/20px 阈值区分点击与拖动，
             *    超过阈值时把当前 MOVE 改写成 ACTION_DOWN 后交还原生 SeekBar。
             *
             * 唯一替换点是官方 callback.onExpandClicked() 改为 SoundMan 的设备详情回调。
             */
            @SuppressLint("ClickableViewAccessibility")
            fun bindOfficialExpandButtonTouch(
                button: ImageView,
                slider: SeekBar,
                classLoader: ClassLoader,
                onClick: () -> Unit,
                onFailure: (Throwable) -> Unit,
            ) {
                var isExpandButton = false
                try {
                    val setter = slider.javaClass.methods.firstOrNull { method ->
                        method.name == "setSeekBarOnclickListener" && method.parameterCount == 1
                    } ?: error("MiuiVolumeSeekBar.setSeekBarOnclickListener was not found")
                    val listenerType = setter.parameterTypes.single()
                    check(listenerType.isInterface) {
                        "MiuiVolumeSeekBar SeekBarOnclickListener is not an interface: ${listenerType.name}"
                    }
                    val listener = Proxy.newProxyInstance(
                        classLoader,
                        arrayOf(listenerType),
                    ) { proxy, method, arguments ->
                        when (method.name) {
                            "toString" -> "SoundManOfficialExpandSeekBarListener"
                            "hashCode" -> System.identityHashCode(proxy)
                            "equals" -> proxy === arguments?.singleOrNull()
                            "onClick" -> {
                                if (isExpandButton) {
                                    onClick()
                                    isExpandButton = false
                                }
                                null
                            }

                            else -> defaultValue(method.returnType)
                        }
                    }
                    setter.invoke(slider, listener)
                    val doClick = slider.javaClass.getMethod("doClick")
                    button.setOnTouchListener { _, event ->
                        if (event.action == MotionEvent.ACTION_DOWN) {
                            try {
                                isExpandButton = true
                                doClick.invoke(slider)
                            } catch (throwable: Throwable) {
                                onFailure(throwable)
                            }
                        }
                        false
                    }
                } catch (throwable: Throwable) {
                    onFailure(throwable)
                }
            }

            /**
             * 给独立列安装真实 SeekBarAnimListener，把官方 MiuiVolumeSeekBar 的
             * SlideContainerAnim（Folme）拖动动画映射到本列 view 的 scale/translationY。
             *
             * 动机：官方控制器（VolumePanelViewController）通过 initAnimListener 注册回调，
             * 驱动 ringer/expand/dnd 等整体动画。独立列没有这些区域，若像以前那样装 no-op
             * listener，官方 dispatchTouchEvent 的 ACTION_MOVE 每帧仍会 cancel+重启 Folme
             * 动画（getHeightArray 全 0 + 全部回调空转），UI 线程被持续占用，表现为“拖动
             * 十几秒才有反应且完全没有动画”。这里把动画目标收敛到 column.view，
             * 让官方拖动手势（按压缩放、拖动位移）在本列上真实回放。
             *
             * @param slider 官方 MiuiVolumeSeekBar（SeekBarAnimListener 集合里的 setSeekBarAnimListener）
             * @param columnView 本列根 view，动画真正作用的载体
             * @param classLoader 官方类加载器（用于创建 Proxy）
             * @param onFailure 回调执行失败时的上报
             */
            private fun installColumnAnimListener(
                slider: SeekBar,
                columnView: View,
                classLoader: ClassLoader,
                onFailure: (Throwable) -> Unit,
            ) {
                val setter = slider.javaClass.methods.firstOrNull { method ->
                    method.name == "setSeekBarAnimListener" && method.parameterCount == 1
                } ?: error("MiuiVolumeSeekBar.setSeekBarAnimListener was not found")
                val listenerType = setter.parameterTypes.single()
                check(listenerType.isInterface) {
                    "MiuiVolumeSeekBar SeekBarAnimListener is not an interface: ${listenerType.name}"
                }
                val listener = Proxy.newProxyInstance(
                    classLoader,
                    arrayOf(listenerType),
                ) { proxy, method, arguments ->
                    try {
                        when (method.name) {
                            "toString" -> "SoundManColumnAnimListener"
                            "hashCode" -> System.identityHashCode(proxy)
                            "equals" -> proxy === arguments?.singleOrNull()
                            // 官方 getHeightArray 返回 {topMargin, topMargin, topMargin, ringerDivider}；
                            // 独立列无 ringer/dnd 区域，提供官方 VolumeColumn 根高度保证位移计算不越界。
                            "getHeightArray" -> intArrayOf(0, 0, 0, columnView.height)
                            "resetView" -> {
                                columnView.scaleX = 1f
                                columnView.scaleY = 1f
                                columnView.translationY = 0f
                                null
                            }

                            "setScale" -> {
                                val before = arguments?.getOrNull(0) as? Float
                                val after = arguments?.getOrNull(1) as? Float
                                if (before == null || after == null) {
                                    defaultValue(method.returnType)
                                } else {
                                    val delta = after - before
                                    columnView.scaleX += delta
                                    columnView.scaleY += delta
                                    null
                                }
                            }

                            "setVolY" -> {
                                val before = arguments?.getOrNull(0) as? Float
                                val after = arguments?.getOrNull(1) as? Float
                                if (before == null || after == null) {
                                    defaultValue(method.returnType)
                                } else {
                                    columnView.translationY += after - before
                                    null
                                }
                            }
                            // 独立列没有 ringer/dnd/superVolume 区域，这些动画目标在官方
                            // VolumePanelViewController 布局上，这里保持空实现。
                            "setRingerY", "setDndY", "setSuperVolumeY" -> null
                            else -> defaultValue(method.returnType)
                        }
                    } catch (throwable: Throwable) {
                        onFailure(throwable)
                        defaultValue(method.returnType)
                    }
                }
                setter.invoke(slider, listener)
            }

            private fun defaultValue(type: Class<*>): Any? = when (type) {
                java.lang.Boolean.TYPE -> false
                java.lang.Byte.TYPE -> 0.toByte()
                java.lang.Short.TYPE -> 0.toShort()
                Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Float.TYPE -> 0f
                java.lang.Double.TYPE -> 0.0
                Character.TYPE -> '\u0000'
                else -> null
            }

            fun create(
                classLoader: ClassLoader,
                context: Context,
                parent: ViewGroup,
                fakeStream: Int,
                row: LoadedAppRow,
                onTrackingChanged: (Boolean) -> Unit,
                onVolumeCommitted: (SystemUiBuiltinAppRowState, Int) -> Unit,
                onFailure: (Throwable) -> Unit,
            ): OfficialVolumeColumn {
                val columnClass = VOLUME_COLUMN_CLASS.toClass(classLoader, true)
                val column = columnClass.getConstructor().newInstance()
                val booleanType =
                    classOf<Boolean>()
                val intType =
                    classOf<Int>()
                columnClass.getMethod(
                    "initColumn",
                    classOf<Context>(),
                    classOf<ViewGroup>(),
                    intType,
                    booleanType,
                    booleanType,
                    booleanType,
                ).invoke(column, context, parent, fakeStream, true, true, false)
                // 该版本 initColumn 会在绑定 icon 前调用 setExpanded；必须先以折叠态完成 View 初始化，
                // 再切到展开态，否则 observable 回调会访问尚未初始化的 icon。
                columnClass.getMethod("setExpanded", booleanType).invoke(column, true)
                // 严格复刻 VolumePanelViewController.updateColumnH 的展开态顺序：
                // resource -> tint -> size，随后用上一帧折叠态触发 setSliderBlendColor 过渡。
                columnClass.getMethod("setSliderResource", booleanType).invoke(column, true)
                columnClass.getMethod("setSliderTintColorList", booleanType).invoke(column, true)
                columnClass.getMethod("setSize", booleanType, booleanType)
                    .invoke(column, true, false)
                columnClass.getMethod("setSliderBlendColor", booleanType).invoke(column, false)

                val view = columnClass.getMethod("getView").invoke(column) as? View
                    ?: error("VolumeColumn.getView returned non-View")
                val slider = columnClass.getMethod("getSlider").invoke(column) as? SeekBar
                    ?: error("VolumeColumn.getSlider returned non-SeekBar")
                val icon = columnClass.getMethod("getIcon").invoke(column) as? ImageView
                    ?: error("VolumeColumn.getIcon returned non-ImageView")
                val progressView = columnClass.getMethod("getProgressView").invoke(column) as? View
                    ?: error("VolumeColumn.getProgressView returned non-View")
                val progressViewBg =
                    columnClass.getMethod("getProgressViewBg").invoke(column) as? View
                        ?: error("VolumeColumn.getProgressViewBg returned non-View")
                // OS4（plugin 18.x）才有 getGlassBg/getExpandBg；OS3 缺失时仅诊断日志降级，
                // 不作为列创建的硬性条件。
                val glassBg = runCatching { columnClass.getMethod("getGlassBg").invoke(column) as? View }
                    .getOrNull()
                val expandBg = runCatching { columnClass.getMethod("getExpandBg").invoke(column) as? View }
                    .getOrNull()
                val updateSliderRatio = columnClass.getMethod("updateSliderRatio")
                val setTracking = columnClass.getMethod("setTracking", booleanType)
                val setMaxLevel = progressView.javaClass.getMethod("setMaxLevel", intType)
                val toProgressWithAnim = progressView.javaClass.getMethod(
                    "toProgressWithAnim",
                    booleanType,
                    classOf<SeekBar>(),
                )

                icon.contentDescription = null
                slider.max = SystemUiOfficialSliderProgress.MAX
                slider.progress =
                    SystemUiOfficialSliderProgress.fromPercent(row.state.volumePercent)
                slider.contentDescription = row.state.label
                setMaxLevel.invoke(progressView, 100)
                // 与官方 VolumeSeekBarChangeListener 一致：进度填充统一走
                // MiuiVolumeSeekBarProgressView.toProgressWithAnim，而不是每帧手动重设 level/outline。
                toProgressWithAnim.invoke(progressView, false, slider)
                updateSliderRatio.invoke(column)
                val dragSession = SystemUiSliderDragSession()
                slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        seekBar: SeekBar,
                        progress: Int,
                        fromUser: Boolean
                    ) {
                        try {
                            // 官方实现无论来源都更新填充层；用户拖动时使用 Folme 动画，程序更新时直接落位。
                            toProgressWithAnim.invoke(progressView, fromUser, seekBar)
                            if (!fromUser) return
                            val level = SystemUiOfficialSliderProgress.toPercent(progress)
                            dragSession.move(level) { changedLevel ->
                                // 对照官方 VolumeSeekBarChangeListener：跨到新等级即实时下发，
                                // 不再等 ACTION_UP，音量反馈与手指位置同步。
                                onVolumeCommitted(row.state, changedLevel)
                            }
                        } catch (throwable: Throwable) {
                            onFailure(throwable)
                        }
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar) {
                        try {
                            dragSession.start(
                                SystemUiOfficialSliderProgress.toPercent(seekBar.progress)
                            )
                            setTracking.invoke(column, true)
                            onTrackingChanged(true)
                        } catch (throwable: Throwable) {
                            onTrackingChanged(false)
                            onFailure(throwable)
                        }
                    }

                    override fun onStopTrackingTouch(seekBar: SeekBar) {
                        try {
                            dragSession.stop(
                                SystemUiOfficialSliderProgress.toPercent(seekBar.progress)
                            ) { finalLevel ->
                                // 极短点击可能没有产生中间 onProgressChanged，停止时补交最终等级。
                                onVolumeCommitted(row.state, finalLevel)
                            }
                            setTracking.invoke(column, false)
                            onTrackingChanged(false)
                        } catch (throwable: Throwable) {
                            onTrackingChanged(false)
                            onFailure(throwable)
                        }
                    }
                })
                installColumnAnimListener(slider, view, classLoader, onFailure)
                return OfficialVolumeColumn(
                    instance = column,
                    view = view,
                    slider = slider,
                    icon = icon,
                    progressView = progressView,
                    progressViewBg = progressViewBg,
                    glassBg = glassBg,
                    expandBg = expandBg,
                    releaseMethod = columnClass.getMethod("release"),
                    updateProgressMethod = toProgressWithAnim,
                    packageName = row.state.packageName,
                )
            }
        }
    }

    private data class AppColumnBuild(
        val packageName: String,
        val view: View,
        val columnWidth: Int,
        val columnHeight: Int,
    )

    private data class AppsPageBuild(
        val view: View,
        val columnWidth: Int,
        val columnHeight: Int,
        val columnViews: Map<String, View>,
    )

    private data class LoadedAppRow(
        val state: SystemUiBuiltinAppRowState,
        val protocolRow: PanelPlaybackRow,
        val icon: Drawable,
    )

    private data class LoadedSnapshot(
        val snapshot: PanelPlaybackSnapshot,
        val apps: List<LoadedAppRow>
    )

    private data class PendingVolumeSubmission(
        val state: SystemUiBuiltinAppRowState,
        val percent: Int,
    )

    companion object {
        private const val TAG = "SoundMan.BuiltinPanel"
        private const val HOST_TAG = "hk.uwu.soundman:independent_volume_host"
        private const val PANEL_TAG = "hk.uwu.soundman:independent_volume_panel"
        private const val VOLUME_DIALOG_VIEW_CLASS =
            "com.android.systemui.miui.volume.MiuiVolumeDialogView"
        private const val VOLUME_COLUMN_CLASS = "com.android.systemui.miui.volume.VolumeColumn"
        private const val PANEL_EDGE_MARGIN_DP = 12
        private const val PANEL_HORIZONTAL_PADDING_DP = 8
        private const val PANEL_VERTICAL_PADDING_DP = 8
        private const val FALLBACK_PANEL_CONTENT_INSET_DP = 16
        private const val APP_ICON_RASTER_SIZE_DP = 96
        private const val ACTION_INSET_MARGIN_DP = 8

        // 应用图标尺寸（音量条内部底部展示）。
        private const val INNER_ICON_SIZE_DP = 26
        private const val INNER_ICON_PADDING_DP = 6

        // 列进入/退出动画——对齐官方 FolmeEase.spring 参数。
        // EASE_EXPAND_SIZE = spring(0.82, 0.4), EASE_COLLAPSE_SIZE = spring(0.9, 0.3)
        // duration 由官方 SpringInterpolator.getDuration() 自动计算，此处仅为反射失败时的兜底。
        private const val COLUMN_TRANSITION_DURATION_FALLBACK_MILLIS = 600L
        private const val COLUMN_ENTER_START_SCALE = 0.6f
        private const val COLUMN_EXIT_END_SCALE = 0.6f
        private const val COLUMN_ENTER_TRANSLATION_DP = 12
        private const val COLUMN_EXIT_TRANSLATION_DP = 8

        // reason=8 原版关闭会将 dialog 父容器设为 INVISIBLE；主线程下一帧即可观察到。
        // 仅为异常 ROM 保留有限超时，避免官方状态机未落位时无限占用独立 host。
        private const val OFFICIAL_DISMISS_COMPLETION_TIMEOUT_MILLIS = 1_000L
        private const val OFFICIAL_DISMISS_POLL_INTERVAL_MILLIS = 16L
        private const val HEADER_ACTION_SIZE_DP = 36
        private const val HEADER_ICON_SIZE_DP = 26
        private const val MIN_COLUMN_WIDTH_DP = 64
        private const val MAX_COLUMN_WIDTH_DP = 104
        private const val MIN_COLUMN_HEIGHT_DP = 220
        private const val EMPTY_CONTENT_WIDTH_DP = 180
        private const val EMPTY_CONTENT_BODY_HEIGHT_DP = 96
        private const val DEVICE_PAGE_WIDTH_DP = 340
        private const val DEVICE_PAGE_HEADER_HEIGHT_DP = 48
        private const val DEVICE_PAGE_HORIZONTAL_PADDING_DP = 8

        // 设备行内左右留白：图标与圆角边框之间需要更宽的呼吸距离，独立于页面横向 padding。
        private const val DEVICE_ROW_HORIZONTAL_PADDING_DP = 16
        private const val DEVICE_PAGE_ROW_SPACING_DP = 10
        private const val DEVICE_ROW_HEIGHT_DP = 62

        // 设备行数超出扫描设备数（deviceCount）的固定预留行：只补 1 行「跟随系统」。
        // 面板高度按真实行数自适应：行少则矮，行多则跟随变高，超过屏幕封顶后交给
        // ScrollView 滚动（底部自然露出半行，提示用户可继续滚动查看）。
        private const val DEVICE_PAGE_RESERVED_ROWS = 1
        private const val PER_USER_RANGE = 100_000
        private const val PAGE_ANIMATION_DURATION_MILLIS = 350L
        private const val EXPAND_ANIMATION_DURATION_MILLIS = 470L

        // 反射创建官方 SpringInterpolator 失败时的兜底 duration。
        private const val HIDE_SLIDE_ANIMATION_DURATION_FALLBACK_MILLIS = 760L

        private const val COLUMN_TRANSLATION_Z_DP = 20
        private const val POLL_INTERVAL_MILLIS = 750L
        private val EXPAND_INTERPOLATOR = PathInterpolator(0.2f, 0f, 0f, 1f)
        private val EXPANDED_PANEL_CONTENT_INSET_NAMES = arrayOf(
            // MiuiVolumeDialogRes.getBgWithContentPadding(context, true)
            "miui_volume_background_padding",
        )
        private val BACK_ICON_NAMES = arrayOf(
            "ic_arrow_back",
            "miuix_appcompat_action_mode_back_arrow",
            "miuix_appcompat_ic_action_bar_back",
            "ic_miui_volume_collapse",
            "ic_miui_volume_expand",
        )
        private val MORE_ICON_NAMES = arrayOf(
            "ic_miui_volume_more",
            "ic_miui_volume_expand",
            "ic_miui_volume_collapse",
        )

        // 参考官方 MiuiVolumeDialogRes.getExpandedIconColorRes：needShowDialog=true 时展开按钮
        // 取色 = miui_volume_expand_button_color_blur_light，否则 = ..._blur——与玻璃背景同源的
        // blur 混合色（官方高级材质走 ColorBlendToken，blur 色资源是 SoundMan 可用的静态近似）。
        // 缺失再回退 blur 色 cc 变体与 VolumeColumn 图标系列（normal），全部缺失由调用方兜底。
        private val MORE_BUTTON_COLOR_NAMES = arrayOf(
            "miui_volume_expand_button_color_blur_light",
            "miui_volume_expand_button_color_blur",
            "miui_volume_expand_button_color_cc",
            "vp_o3_volume_icon_normal",
        )
        private val sessions = WeakHashMap<ViewGroup, Session>()

        private fun dimension(vararg candidates: Int?): Int =
            candidates.firstOrNull { it != null && it > 0 }
                ?: error("Required live View dimension is unavailable")
    }
}
