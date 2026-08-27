package hk.uwu.soundman.hook.scopes.systemui.runtime

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import java.lang.ref.WeakReference
import java.lang.reflect.Method

/**
 * 液态玻璃的实时屏幕捕获，承袭 HyperIsland 的 RefractiveScreenCapture。
 *
 * 在 SystemUI 进程内通过隐藏 API `IWindowManager.captureDisplay` 同步截取指定区域，
 * 捕获时排除宿主窗口自身的 Surface（音量对话框窗口），拿到的就是面板背后的真实
 * 屏幕内容（应用、壁纸、状态栏）。排除链路双保险：优先 `setExcludeLayers(SurfaceControl[])`
 * 传入本窗口 root Surface，不可用时回退 `setExcludeOrIncludeLayerNames` 按图层名排除
 * 音量窗口（HyperIsland 的排除清单里该图层名为 `VolumePanelDialogController#`）。
 *
 * 所有捕获工作序列化到全进程单例捕获线程（[LiquidGlassCaptureThread]）；
 * generation token 保证停用后迟到的旧帧不会复活状态。
 */
internal class LiquidGlassScreenCapture(
    host: View,
    private val prepareFrame: (Bitmap) -> Bitmap,
    private val onFrame: (Bitmap, Float, Float, Float, Float) -> Unit,
    private val log: (priority: Int, tag: String, message: String, throwable: Throwable?) -> Unit,
) {
    private val host = WeakReference(host)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var worker: Handler? = null

    @Volatile
    private var generation = 0

    @Volatile
    private var captureAccess: CaptureAccess? = null
    private var captureFps = LiquidGlassPanelConfig.CAPTURE_FPS
    private var captureScale = LiquidGlassPanelConfig.CAPTURE_SCALE
    private val location = IntArray(2)

    @Volatile
    private var viewSnapshot = ViewSnapshot.EMPTY

    @Volatile
    private var captureRegion: Rect? = null

    @Volatile
    var hasFrame = false
        private set

    @Volatile
    var screenX = 0f
        private set

    @Volatile
    var screenY = 0f
        private set

    fun updateSettings(fps: Int, scale: Float) {
        val nextFps = fps.coerceIn(1, 90)
        val nextScale = scale.coerceIn(0.1f, 1f)
        if (captureFps == nextFps && captureScale == nextScale) return
        if (worker != null) stop()
        captureFps = nextFps
        captureScale = nextScale
    }

    fun start() {
        if (worker != null) return
        val snapshot = viewSnapshot
        if (!snapshot.canCapture) return
        val captureWorker = LiquidGlassCaptureThread.acquire()
        worker = captureWorker
        generation++
        val token = generation
        captureWorker.post { initializeCapture(token, snapshot.displayId) }
    }

    private fun initializeCapture(token: Int, displayId: Int) {
        if (token != generation) return
        if (!viewSnapshot.canCapture) {
            mainHandler.post { if (token == generation) stop() }
            return
        }
        val access = runCatching {
            CaptureAccess.create(
                displayId,
                captureScale,
                { captureRegion },
                { viewSnapshot.excludeLayers },
            )
        }
            .onFailure { log(Log.ERROR, TAG, "$TAG unavailable: ${it.message}", it) }
            .getOrNull()
        if (access == null) {
            mainHandler.post { if (token == generation) stop() }
            return
        }
        captureAccess = access
        log(
            Log.INFO,
            TAG,
            "Screen capture started path=${access.path} fps=$captureFps scale=$captureScale",
            null,
        )
        capture(token)
    }

    fun stop() {
        if (worker == null && captureAccess == null && !hasFrame) return
        generation++
        worker = null
        captureAccess = null
        hasFrame = false
        host.get()?.postInvalidateOnAnimation()
    }

    fun release() {
        stop()
        host.clear()
    }

    /** 刷新宿主可见性与排除图层快照；不可捕获时清空快照。 */
    fun updateViewSnapshot(canCapture: Boolean) {
        val view = host.get() ?: return
        if (!canCapture) {
            if (viewSnapshot.canCapture) viewSnapshot = ViewSnapshot.EMPTY
            return
        }
        runCatching {
            view.getLocationOnScreen(location)
            screenX = location[0].toFloat()
            screenY = location[1].toFloat()
            val displayId = view.display?.displayId ?: 0
            if (viewSnapshot.canCapture && viewSnapshot.displayId == displayId) return
            viewSnapshot = ViewSnapshot(
                canCapture = true,
                displayId = displayId,
                excludeLayers = CaptureAccess.currentExcludeLayers(view),
            )
        }.onFailure {
            viewSnapshot = ViewSnapshot.EMPTY
        }
    }

    /** 以面板在屏幕上的矩形为中心，外扩模糊半径与折射距离得到捕获区域。 */
    fun updateCaptureRegion(
        localBounds: RectF,
        blurRadius: Float,
        refractionDistance: Float,
    ) {
        val view = host.get() ?: return
        if (localBounds.isEmpty) return
        runCatching {
            view.getLocationOnScreen(location)
            screenX = location[0].toFloat()
            screenY = location[1].toFloat()
            val metrics = view.resources.displayMetrics
            val padding = kotlin.math.ceil(
                maxOf(blurRadius * 2f, refractionDistance * 1.25f, 1f),
            ).toInt()
            val left = kotlin.math.floor(screenX + localBounds.left - padding).toInt()
                .coerceIn(0, metrics.widthPixels)
            val top = kotlin.math.floor(screenY + localBounds.top - padding).toInt()
                .coerceIn(0, metrics.heightPixels)
            val right = kotlin.math.ceil(screenX + localBounds.right + padding).toInt()
                .coerceIn(0, metrics.widthPixels)
            val bottom = kotlin.math.ceil(screenY + localBounds.bottom + padding).toInt()
                .coerceIn(0, metrics.heightPixels)
            if (right <= left || bottom <= top) {
                captureRegion = null
            } else {
                val current = captureRegion
                if (current == null || current.left != left || current.top != top ||
                    current.right != right || current.bottom != bottom
                ) {
                    captureRegion = Rect(left, top, right, bottom)
                }
            }
        }
    }

    private fun scheduleCapture(token: Int, delay: Long) {
        worker?.postDelayed({ capture(token) }, delay)
    }

    private fun capture(token: Int) {
        if (token != generation) return
        if (!viewSnapshot.canCapture) {
            mainHandler.post { if (token == generation) stop() }
            return
        }
        val access = captureAccess ?: return
        val startedAt = SystemClock.uptimeMillis()
        val result = runCatching { access.capture() }
        val frame = result.getOrNull()
        if (result.isFailure || frame == null) {
            log(
                Log.ERROR,
                TAG,
                "Screen capture failed, falling back to native blur: " +
                    (result.exceptionOrNull()?.message ?: "empty frame"),
                result.exceptionOrNull(),
            )
            mainHandler.post { if (token == generation) stop() }
            return
        }
        if (token != generation) {
            if (!frame.bitmap.isRecycled) frame.bitmap.recycle()
            return
        }
        val preparedBitmap = runCatching { prepareFrame(frame.bitmap) }
            .onFailure { error ->
                log(Log.ERROR, TAG, "Screen capture frame preparation failed: ${error.message}", error)
                if (!frame.bitmap.isRecycled) frame.bitmap.recycle()
            }
            .getOrNull() ?: run {
            mainHandler.post { if (token == generation) stop() }
            return
        }
        mainHandler.post {
            if (token != generation) {
                if (!preparedBitmap.isRecycled) preparedBitmap.recycle()
                return@post
            }
            runCatching {
                onFrame(
                    preparedBitmap,
                    frame.scaleX,
                    frame.scaleY,
                    frame.cropX,
                    frame.cropY,
                )
            }.onSuccess {
                hasFrame = true
            }.onFailure { error ->
                log(Log.ERROR, TAG, "Screen capture frame delivery failed: ${error.message}", error)
                if (!preparedBitmap.isRecycled) preparedBitmap.recycle()
                if (token == generation) stop()
            }
        }
        val elapsed = SystemClock.uptimeMillis() - startedAt
        scheduleCapture(token, (captureIntervalMs - elapsed).coerceAtLeast(0L))
    }

    private val captureIntervalMs: Long
        get() = (1000L / captureFps.coerceAtLeast(1)).coerceAtLeast(1L)

    private data class CapturedFrame(
        val bitmap: Bitmap,
        val scaleX: Float,
        val scaleY: Float,
        val cropX: Float,
        val cropY: Float,
    )

    private data class ViewSnapshot(
        val canCapture: Boolean,
        val displayId: Int,
        val excludeLayers: Any?,
    ) {
        companion object {
            val EMPTY = ViewSnapshot(false, 0, null)
        }
    }

    private class CaptureAccess(
        val path: String,
        private val captureFrame: () -> CapturedFrame?,
    ) {
        fun capture(): CapturedFrame? = captureFrame()

        companion object {
            /** 音量对话框窗口的 SurfaceFlinger 图层名（HyperIsland 排除清单同源）。 */
            private val EXCLUDED_LAYER_NAMES = arrayOf("VolumePanelDialogController#")

            fun create(
                displayId: Int,
                captureScale: Float,
                getCaptureRegion: () -> Rect?,
                getExcludeLayers: () -> Any?,
            ): CaptureAccess {
                val screenCaptureClass = Class.forName("android.window.ScreenCapture")
                return createWindowManagerAccess(
                    displayId,
                    screenCaptureClass,
                    captureScale,
                    getCaptureRegion,
                    getExcludeLayers,
                )
            }

            private fun createWindowManagerAccess(
                displayId: Int,
                screenCaptureClass: Class<*>,
                captureScale: Float,
                getCaptureRegion: () -> Rect?,
                getExcludeLayers: () -> Any?,
            ): CaptureAccess {
                val builderClass = Class.forName(
                    "android.window.ScreenCapture\$CaptureArgs\$Builder",
                )
                val constructor = builderClass.declaredConstructors.firstOrNull {
                    it.parameterCount == 0
                }?.apply { isAccessible = true } ?: error("CaptureArgs.Builder unavailable")
                val captureArgsClass = Class.forName("android.window.ScreenCapture\$CaptureArgs")
                val buildMethod = findMethod(builderClass, "build")
                    ?: error("CaptureArgs unavailable")
                val windowManagerGlobal = Class.forName("android.view.WindowManagerGlobal")
                val service = findMethod(windowManagerGlobal, "getWindowManagerService")
                    ?.invoke(null) ?: error("IWindowManager unavailable")
                val createListener = findMethod(screenCaptureClass, "createSyncCaptureListener")
                    ?: error("sync capture listener unavailable")
                val captureMethod = service.javaClass.methods.firstOrNull { method ->
                    method.name == "captureDisplay" &&
                        method.parameterCount == 3 &&
                        method.parameterTypes[0] == Int::class.javaPrimitiveType &&
                        method.parameterTypes[1].isAssignableFrom(captureArgsClass)
                }?.apply { isAccessible = true }
                    ?: service.javaClass.declaredMethods.firstOrNull { method ->
                        method.name == "captureDisplay" &&
                            method.parameterCount == 3 &&
                            method.parameterTypes[0] == Int::class.javaPrimitiveType &&
                            method.parameterTypes[1].isAssignableFrom(captureArgsClass)
                    }?.apply { isAccessible = true }
                    ?: error("IWindowManager.captureDisplay unavailable")
                return CaptureAccess(
                    path = "iwm-local",
                    captureFrame = {
                        val region = getCaptureRegion()
                            ?: return@CaptureAccess null
                        val builder = constructor.newInstance()
                        configureCaptureBuilder(
                            builderClass,
                            builder,
                            getExcludeLayers(),
                            region,
                            captureScale,
                        )
                        val args = buildMethod.invoke(builder)
                            ?: error("CaptureArgs unavailable")
                        val listener = createListener.invoke(null)
                            ?: error("sync capture listener creation failed")
                        captureMethod.invoke(service, displayId, args, listener)
                        val buffer = findMethod(listener.javaClass, "getBuffer")?.invoke(listener)
                            ?: error("sync capture returned no buffer")
                        val bitmap = findMethod(buffer.javaClass, "asBitmap")?.invoke(buffer) as? Bitmap
                            ?: return@CaptureAccess null
                        CapturedFrame(
                            bitmap = bitmap,
                            scaleX = bitmap.width.toFloat() / region.width(),
                            scaleY = bitmap.height.toFloat() / region.height(),
                            cropX = region.left.toFloat(),
                            cropY = region.top.toFloat(),
                        )
                    },
                )
            }

            /** 宿主窗口 root 的 SurfaceControl，作为捕获排除图层。 */
            fun currentExcludeLayers(view: View): Any? {
                val surfaceClass = runCatching {
                    Class.forName("android.view.SurfaceControl")
                }.getOrNull() ?: return null
                val rootView = view.rootView ?: view
                val viewRoot = findMethod(rootView.javaClass, "getViewRootImpl")
                    ?.invoke(rootView) ?: return null
                val surface = findMethod(viewRoot.javaClass, "getSurfaceControl")
                    ?.invoke(viewRoot) ?: return null
                if (!surfaceClass.isInstance(surface)) return null
                val isValid = findMethod(surfaceClass, "isValid")?.invoke(surface) as? Boolean
                if (isValid == false) return null
                return java.lang.reflect.Array.newInstance(surfaceClass, 1).also {
                    java.lang.reflect.Array.set(it, 0, surface)
                }
            }

            private fun configureCaptureBuilder(
                builderClass: Class<*>,
                builder: Any,
                excludeArray: Any?,
                region: Rect,
                captureScale: Float,
            ) {
                val setSourceCrop = findMethod(builderClass, "setSourceCrop", Rect::class.java)
                    ?: error("local capture crop unsupported")
                setSourceCrop.invoke(builder, region)
                val setSize = findMethod(
                    builderClass,
                    "setSize",
                    Int::class.javaPrimitiveType!!,
                    Int::class.javaPrimitiveType!!,
                )
                if (setSize != null) {
                    setSize.invoke(
                        builder,
                        (region.width() * captureScale).toInt().coerceAtLeast(1),
                        (region.height() * captureScale).toInt().coerceAtLeast(1),
                    )
                } else {
                    val oneScale = findMethod(
                        builderClass,
                        "setFrameScale",
                        Float::class.javaPrimitiveType!!,
                    )
                    val twoScale = findMethod(
                        builderClass,
                        "setFrameScale",
                        Float::class.javaPrimitiveType!!,
                        Float::class.javaPrimitiveType!!,
                    )
                    when {
                        oneScale != null -> oneScale.invoke(builder, captureScale)
                        twoScale != null -> twoScale.invoke(builder, captureScale, captureScale)
                        else -> error("capture scale unsupported")
                    }
                }
                findMethod(
                    builderClass,
                    "setCaptureMode",
                    Int::class.javaPrimitiveType!!,
                )?.invoke(builder, 1)
                val setLayerNames = findMethod(
                    builderClass,
                    "setExcludeOrIncludeLayerNames",
                    Array<String>::class.java,
                )
                setLayerNames?.invoke(builder, EXCLUDED_LAYER_NAMES)
                val setExcludeLayers = builderClass.methods.firstOrNull {
                    it.name == "setExcludeLayers" && it.parameterCount == 1
                } ?: builderClass.declaredMethods.firstOrNull {
                    it.name == "setExcludeLayers" && it.parameterCount == 1
                }?.apply { isAccessible = true }
                if (excludeArray != null && setExcludeLayers != null) {
                    setExcludeLayers.invoke(builder, excludeArray)
                } else if (setLayerNames == null) {
                    error("no supported capture exclusion mechanism")
                }
            }

            private fun findMethod(
                clazz: Class<*>,
                name: String,
                vararg types: Class<*>,
            ): Method? {
                runCatching {
                    return clazz.getMethod(name, *types).apply { isAccessible = true }
                }
                var current: Class<*>? = clazz
                while (current != null) {
                    runCatching {
                        return current.getDeclaredMethod(name, *types).apply { isAccessible = true }
                    }
                    current = current.superclass
                }
                return null
            }
        }
    }

    private companion object {
        const val TAG = "SoundMan.LiquidGlass.Capture"
    }
}

/** 把全部真实折射捕获序列化到一条进程级捕获线程。 */
internal object LiquidGlassCaptureThread {
    private val thread = android.os.HandlerThread("SoundManLiquidGlass").apply { start() }
    private val handler = Handler(thread.looper)

    fun acquire(): Handler = handler
}
