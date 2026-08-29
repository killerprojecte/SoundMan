package hk.uwu.soundman.hook.scopes.systemui.runtime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.HardwareRenderer
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.ArrayDeque
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 内置面板的液态玻璃背景 Drawable，渲染分层承袭 HyperIsland 超级岛：
 *
 * 1. 真实折射（trueRefraction 开启且捕获有帧时）：AGSL RuntimeShader 对面板后方
 *    捕获画面做折射位移、色散与混合色，整层不透明，视觉上替换下方的官方 MiBlur
 *    （MiBlur 保留为捕获失败时的兜底，捕获不可用本层直接不绘制）；
 * 2. 边缘高光（enabled 开启时）：SDF 圆角矩形边缘的方向性光影、透镜带与棱镜色散。
 *
 * 本 Drawable 作为 panel 的 background 绘制在内容之下，不持有官方材质状态；
 * 一切渲染失败只静默降级，绝不影响面板功能。HDR 高光与每帧配置热更不在移植范围
 * （HyperIsland 中 HDR 默认关闭、面板为短生命周期视图无热更需求）。
 */
internal class LiquidGlassPanelDrawable(
    context: Context,
    host: View,
    initialConfig: LiquidGlassPanelConfig,
    initialCornerRadius: Float,
    private val log: (priority: Int, tag: String, message: String, throwable: Throwable?) -> Unit,
) : Drawable() {
    private val tiltContext = context
    private val hostView = WeakReference(host)
    private val glassRect = RectF()
    private val refractionShader = runCatching { RuntimeShader(REFRACTION_SHADER) }
        .onFailure { log(Log.ERROR, TAG, "RuntimeShader(refraction) unavailable: ${it.message}", it) }
        .getOrNull()
    private val refractionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = refractionShader
    }
    private val edgeShader = runCatching { RuntimeShader(EDGE_HIGHLIGHT_SHADER) }
        .onFailure { log(Log.ERROR, TAG, "RuntimeShader(edge) unavailable: ${it.message}", it) }
        .getOrNull()
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = edgeShader
    }
    private val screenCapture = LiquidGlassScreenCapture(
        host = host,
        prepareFrame = ::applySystemBlur,
        onFrame = ::onScreenCaptured,
        log = log,
    )
    private val config = initialConfig
    private var cornerRadius = initialCornerRadius.coerceAtLeast(0f)
    private var lightX = DEFAULT_LIGHT_X
    private var lightY = DEFAULT_LIGHT_Y
    private var drawableAlpha = 1f
    private var currentFrame: Bitmap? = null
    private val retiredFrames = ArrayDeque<Bitmap>()
    private val frameReleaseHandler = Handler(Looper.getMainLooper())
    private val blurResourceLock = Any()
    private val blurNode = RenderNode("SoundManLiquidGlassBlur")
    private var blurEffectRadius = -1f
    private var blurEffect: RenderEffect? = null
    private var tiltAttached = false

    init {
        screenCapture.updateSettings(config.captureFps, config.captureScale)
        applyFixedLightDirection()
        updateTiltRegistration()
        updateCaptureState()
    }

    override fun onBoundsChange(bounds: Rect) {
        if (updateGlassRect(bounds)) updateCaptureRegion()
        updateCaptureState()
    }

    override fun draw(canvas: Canvas) {
        updateCaptureState()
        if (!updateGlassRect(bounds)) return
        updateCaptureRegion()

        val width = glassRect.width()
        val height = glassRect.height()
        val radius = cornerRadius.coerceAtMost(minOf(width, height) / 2f)
        if (config.trueRefraction && screenCapture.hasFrame &&
            canvas.isHardwareAccelerated && refractionShader != null
        ) {
            runCatching { drawTrueRefraction(canvas, width, height, radius) }
                .onFailure { log(Log.ERROR, TAG, "True refraction render failed: ${it.message}", it) }
        }
        if (config.enabled && canvas.isHardwareAccelerated && edgeShader != null) {
            drawEdgeHighlight(canvas, width, height, radius)
        }
    }

    private fun drawTrueRefraction(
        canvas: Canvas,
        width: Float,
        height: Float,
        radius: Float,
    ) {
        val runtimeShader = refractionShader ?: return
        val frame = currentFrame?.takeIf { !it.isRecycled } ?: return
        runtimeShader.setFloatUniform("uOrigin", glassRect.left, glassRect.top)
        runtimeShader.setFloatUniform("uScreenOrigin", screenCapture.screenX, screenCapture.screenY)
        runtimeShader.setFloatUniform("uSize", width, height)
        runtimeShader.setFloatUniform("uCornerRadius", radius)
        runtimeShader.setFloatUniform(
            "uRefractionHeight",
            (minOf(width, height) * config.edgeWidth * 1.25f).coerceAtLeast(1f),
        )
        runtimeShader.setFloatUniform(
            "uRefractionAmount",
            -minOf(width, height) * config.refraction * 2f,
        )
        runtimeShader.setFloatUniform("uDepthEffect", 1f)
        runtimeShader.setFloatUniform("uDispersion", config.dispersion * 0.12f)
        runtimeShader.setFloatUniform(
            "uBlendColor",
            Color.red(config.blendColor) / 255f,
            Color.green(config.blendColor) / 255f,
            Color.blue(config.blendColor) / 255f,
            Color.alpha(config.blendColor) / 255f,
        )
        refractionPaint.alpha = (drawableAlpha * 255f).toInt().coerceIn(0, 255)
        canvas.drawRect(glassRect, refractionPaint)
    }

    /** 方向性边缘光影；真实折射已提供折射观感时收敛透镜带，避免边缘双重折射。 */
    private fun drawEdgeHighlight(canvas: Canvas, width: Float, height: Float, radius: Float) {
        val shader = edgeShader ?: return
        shader.setFloatUniform("uOrigin", glassRect.left, glassRect.top)
        shader.setFloatUniform("uSize", width, height)
        shader.setFloatUniform("uCornerRadius", radius)
        shader.setFloatUniform("uLightDir", lightX, lightY)
        val edgeWidthPx = (minOf(width, height) * config.edgeWidth).coerceAtLeast(1f)
        shader.setFloatUniform("uEdgeWidth", edgeWidthPx)
        shader.setFloatUniform(
            "uRefraction",
            if (config.trueRefraction && screenCapture.hasFrame) 0f else config.refraction,
        )
        shader.setFloatUniform("uEdgeAlpha", config.highlight * drawableAlpha * EDGE_HIGHLIGHT_SCALE)
        shader.setFloatUniform("uEdgeShadow", config.shadow * drawableAlpha * EDGE_SHADOW_SCALE)
        shader.setFloatUniform("uDispersion", config.dispersion * EDGE_DISPERSION_SCALE)
        canvas.drawRect(
            glassRect.left - EDGE_AA_PADDING,
            glassRect.top - EDGE_AA_PADDING,
            glassRect.right + EDGE_AA_PADDING,
            glassRect.bottom + EDGE_AA_PADDING,
            edgePaint,
        )
    }

    private fun updateCaptureState() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            hostView.get()?.post { updateCaptureState() }
            return
        }
        val canCapture = shouldCaptureNow()
        screenCapture.updateViewSnapshot(canCapture)
        if (canCapture) {
            screenCapture.start()
        } else {
            stopCapture()
        }
    }

    private fun stopCapture() {
        screenCapture.stop()
        currentFrame?.let(::retireFrameLater)
        currentFrame = null
        while (retiredFrames.isNotEmpty()) retireFrameLater(retiredFrames.removeFirst())
    }

    private fun shouldCaptureNow(): Boolean {
        if (!config.trueRefraction || refractionShader == null) return false
        if (!isVisible || drawableAlpha <= 0f || bounds.isEmpty) return false
        val host = hostView.get() ?: return false
        return isActuallyVisible(host)
    }

    private fun isActuallyVisible(view: View): Boolean {
        if (!view.isAttachedToWindow || !view.isShown || view.windowVisibility != View.VISIBLE ||
            view.width <= 0 || view.height <= 0
        ) return false
        var current: View? = view
        while (current != null) {
            if (current.alpha <= 0.01f) return false
            current = current.parent as? View
        }
        return true
    }

    private fun onScreenCaptured(
        bitmap: Bitmap,
        scaleX: Float,
        scaleY: Float,
        cropX: Float,
        cropY: Float,
    ) {
        val runtimeShader = refractionShader ?: return
        val previousFrame = currentFrame
        currentFrame = bitmap
        runtimeShader.setInputShader(
            "uContent",
            BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP),
        )
        runtimeShader.setFloatUniform("uCaptureScale", scaleX, scaleY)
        runtimeShader.setFloatUniform("uCaptureOrigin", cropX, cropY)
        if (previousFrame !== bitmap && previousFrame?.isRecycled == false) {
            retiredFrames.addLast(previousFrame)
            if (retiredFrames.size > RETAINED_FRAME_COUNT) {
                retiredFrames.removeFirst().recycle()
            }
        }
        hostView.get()?.postInvalidateOnAnimation()
    }

    private fun retireFrameLater(bitmap: Bitmap) {
        if (bitmap.isRecycled) return
        frameReleaseHandler.postDelayed(
            { if (!bitmap.isRecycled) bitmap.recycle() },
            STOPPED_FRAME_RELEASE_DELAY_MS,
        )
    }

    /** 捕获帧的模糊：RenderNode + RenderEffect 走硬件管线，经隐藏 API 生成位图。 */
    @SuppressLint("BlockedPrivateApi")
    private fun applySystemBlur(bitmap: Bitmap): Bitmap {
        val radius = config.captureBlurRadius.coerceIn(0f, 20f)
        if (radius <= 0f || bitmap.isRecycled) return bitmap
        val blurred = runCatching {
            synchronized(blurResourceLock) {
                blurNode.setPosition(0, 0, bitmap.width, bitmap.height)
                val recordingCanvas = blurNode.beginRecording(bitmap.width, bitmap.height)
                recordingCanvas.drawBitmap(bitmap, 0f, 0f, null)
                blurNode.endRecording()
                if (blurEffect == null || blurEffectRadius != radius) {
                    blurEffectRadius = radius
                    blurEffect = RenderEffect.createBlurEffect(
                        radius,
                        radius,
                        Shader.TileMode.CLAMP,
                    )
                    blurNode.setRenderEffect(blurEffect)
                }
                try {
                    createHardwareBitmapMethod.invoke(
                        null,
                        blurNode,
                        bitmap.width,
                        bitmap.height,
                    ) as? Bitmap
                        ?: error("HardwareRenderer.createHardwareBitmap returned no bitmap")
                } finally {
                    blurNode.discardDisplayList()
                }
            }
        }
            .onFailure { error ->
                log(Log.ERROR, TAG, "True refraction system blur failed: ${error.message}", error)
            }
            .getOrNull() ?: return bitmap
        if (blurred !== bitmap && !bitmap.isRecycled) bitmap.recycle()
        return blurred
    }

    private fun updateCaptureRegion() {
        screenCapture.updateCaptureRegion(
            glassRect,
            config.captureBlurRadius / config.captureScale.coerceAtLeast(0.1f),
            minOf(glassRect.width(), glassRect.height()) * config.refraction * 2f,
        )
    }

    private fun updateGlassRect(bounds: Rect): Boolean {
        glassRect.set(bounds)
        return !glassRect.isEmpty
    }

    private fun applyFixedLightDirection() {
        val angle = config.lightDirection * PI.toFloat() / 180f
        setLightDirection(cos(angle), sin(angle))
    }

    private fun updateTiltRegistration() {
        val shouldAttach = config.enabled && config.gyroscope
        if (shouldAttach == tiltAttached) return
        tiltAttached = shouldAttach
        if (shouldAttach) {
            runCatching { LiquidGlassTiltController.attach(tiltContext, this) }
                .onFailure { log(Log.WARN, TAG, "Liquid glass tilt attach failed: ${it.message}", it) }
        } else {
            runCatching { LiquidGlassTiltController.detach(this) }
        }
    }

    /** 由共享姿态传感器监听更新光源方向。 */
    fun setLightDirection(x: Float, y: Float) {
        val length = hypot(x, y).coerceAtLeast(0.0001f)
        lightX = x / length
        lightY = y / length
        invalidateSelf()
        hostView.get()?.postInvalidateOnAnimation()
    }

    fun applyTilt(x: Float, y: Float) {
        val angle = config.lightDirection * PI.toFloat() / 180f
        setLightDirection(
            cos(angle) - x * TILT_STRENGTH,
            sin(angle) + y * TILT_STRENGTH,
        )
    }

    override fun setAlpha(alpha: Int) {
        drawableAlpha = alpha.coerceIn(0, 255) / 255f
        updateCaptureState()
        invalidateSelf()
    }

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        updateCaptureState()
        return changed
    }

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    fun release() {
        if (tiltAttached) runCatching { LiquidGlassTiltController.detach(this) }
        tiltAttached = false
        screenCapture.release()
        stopCapture()
        hostView.clear()
        callback = null
        synchronized(blurResourceLock) {
            blurNode.discardDisplayList()
            blurNode.setRenderEffect(null)
            blurEffect = null
        }
    }

    private companion object {
        const val TAG = "SoundMan.LiquidGlass"
        const val DEFAULT_LIGHT_X = -0.45f
        const val DEFAULT_LIGHT_Y = -0.89f
        const val TILT_STRENGTH = 2.2f
        const val RETAINED_FRAME_COUNT = 2
        const val STOPPED_FRAME_RELEASE_DELAY_MS = 150L
        const val EDGE_HIGHLIGHT_SCALE = 0.56f
        const val EDGE_SHADOW_SCALE = 0.34f
        const val EDGE_DISPERSION_SCALE = 0.16f
        const val EDGE_AA_PADDING = 1f
        val createHardwareBitmapMethod: Method by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            resolveCreateHardwareBitmapMethod()
        }

        @SuppressLint("BlockedPrivateApi")
        fun resolveCreateHardwareBitmapMethod(): Method {
            return HardwareRenderer::class.java.getDeclaredMethod(
                "createHardwareBitmap",
                RenderNode::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }
        }

        val EDGE_GEOMETRY_SHADER = """
            float sdRoundedBox(float2 p, float2 halfSize, float radius) {
                float2 q = abs(p) - halfSize + float2(radius);
                return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - radius;
            }

            float edgeCoverage(float distance) {
                return 1.0 - smoothstep(-0.75, 0.75, distance);
            }

            float3 edgeGeometry(
                float2 p,
                float2 halfSize,
                float radius,
                float edgeWidth,
                float2 lightDir
            ) {
                float distance = sdRoundedBox(p, halfSize, radius);
                float2 dx = float2(1.0, 0.0);
                float2 dy = float2(0.0, 1.0);
                float2 normal = normalize(float2(
                    sdRoundedBox(p + dx, halfSize, radius)
                        - sdRoundedBox(p - dx, halfSize, radius),
                    sdRoundedBox(p + dy, halfSize, radius)
                        - sdRoundedBox(p - dy, halfSize, radius)
                ) + float2(0.0001));
                float facing = dot(normal, normalize(lightDir));
                float edge = pow(smoothstep(-edgeWidth, 0.0, distance), 2.2);
                return float3(distance, facing, edge);
            }
        """.trimIndent()

        val EDGE_HIGHLIGHT_SHADER = """
            uniform float2 uOrigin;
            uniform float2 uSize;
            uniform float uCornerRadius;
            uniform float2 uLightDir;
            uniform float uEdgeWidth;
            uniform float uRefraction;
            uniform float uEdgeAlpha;
            uniform float uEdgeShadow;
            uniform float uDispersion;

            $EDGE_GEOMETRY_SHADER

            half4 main(float2 fragCoord) {
                float2 halfSize = uSize * 0.5;
                float2 p = fragCoord - uOrigin - halfSize;
                float3 geometry = edgeGeometry(
                    p, halfSize, uCornerRadius, uEdgeWidth, uLightDir
                );
                float distance = geometry.x;
                float coverage = edgeCoverage(distance);
                if (coverage <= 0.0) return half4(0.0);

                float facing = geometry.y;
                float edge = geometry.z;
                float bright = max(facing, 0.0) * edge * uEdgeAlpha;
                float opposite = max(-facing, 0.0) * edge * uEdgeShadow;
                float lensBand = pow(smoothstep(-uEdgeWidth * 2.2, -uEdgeWidth * 0.25, distance), 2.0)
                    * (1.0 - edge) * uRefraction;
                float dispersion = facing * edge * uDispersion * 0.22;
                float alpha = clamp(bright + opposite + lensBand, 0.0, 1.0);
                float3 primary = float3(1.0 + max(dispersion, 0.0), 0.99,
                    0.96 + max(-dispersion, 0.0)) * (bright + lensBand * 0.45);
                float3 secondary = float3(0.92, 0.96, 1.0) * opposite;
                return half4(
                    half3((primary + secondary) * coverage),
                    half(alpha * coverage)
                );
            }
        """.trimIndent()

        val REFRACTION_SHADER = """
            uniform shader uContent;
            uniform float2 uOrigin;
            uniform float2 uScreenOrigin;
            uniform float2 uCaptureScale;
            uniform float2 uCaptureOrigin;
            uniform float2 uSize;
            uniform float uCornerRadius;
            uniform float uRefractionHeight;
            uniform float uRefractionAmount;
            uniform float uDepthEffect;
            uniform float uDispersion;
            uniform float4 uBlendColor;

            float sdRoundedBox(float2 p, float2 halfSize, float radius) {
                float2 q = abs(p) - halfSize + float2(radius);
                return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - radius;
            }

            float2 gradRoundedBox(float2 p, float2 halfSize, float radius) {
                float2 q = abs(p) - halfSize + float2(radius);
                float2 s = sign(p);
                s.x = s.x == 0.0 ? 1.0 : s.x;
                s.y = s.y == 0.0 ? 1.0 : s.y;
                if (q.x >= 0.0 || q.y >= 0.0) {
                    return s * normalize(max(q, 0.0) + float2(0.0001));
                }
                float gx = step(q.y, q.x);
                return s * float2(gx, 1.0 - gx);
            }

            float circleMap(float x) {
                return 1.0 - sqrt(max(1.0 - x * x, 0.0));
            }

            half4 sampleContent(float2 screenCoord) {
                half4 content = uContent.eval(
                    (screenCoord - uCaptureOrigin) * uCaptureScale
                );
                return half4(
                    mix(content.rgb, half3(uBlendColor.rgb), half(uBlendColor.a)),
                    content.a
                );
            }

            half4 main(float2 fragCoord) {
                float2 halfSize = uSize * 0.5;
                float2 p = fragCoord - uOrigin - halfSize;
                float distance = sdRoundedBox(p, halfSize, uCornerRadius);
                if (distance > 0.0) return half4(0.0);

                float2 screenCoord = fragCoord + uScreenOrigin;
                if (-distance >= uRefractionHeight) {
                    half4 content = sampleContent(screenCoord);
                    return half4(content.rgb, 1.0);
                }

                float depth = clamp(-min(distance, 0.0) / uRefractionHeight, 0.0, 1.0);
                float fade = 1.0 - depth * depth * (3.0 - 2.0 * depth);
                float displacement = circleMap(1.0 - depth) * uRefractionAmount * fade;
                float2 centerDirection = normalize(p + float2(0.0001));
                float2 normal = normalize(
                    gradRoundedBox(p, halfSize, uCornerRadius) +
                        uDepthEffect * fade * centerDirection
                );
                float2 refracted = screenCoord + normal * displacement;
                float positionFactor = abs(p.x * p.y) /
                    max(halfSize.x * halfSize.y, 1.0);
                float2 dispersion = displacement * normal * uDispersion *
                    (0.4 + 0.6 * positionFactor);
                half red = sampleContent(refracted + dispersion).r;
                half4 center = sampleContent(refracted);
                half blue = sampleContent(refracted - dispersion).b;
                return half4(half3(red, center.g, blue), 1.0);
            }
        """.trimIndent()
    }
}

/**
 * 所有活动玻璃 Drawable 共享一个姿态传感器监听（承袭 HyperIsland
 * LiquidGlassTiltController）：设备倾斜时光源方向随之偏移，形成"液态"观感。
 */
private object LiquidGlassTiltController : SensorEventListener {
    private const val TAG = "SoundMan.LiquidGlass.Tilt"
    private const val FILTER_ALPHA = 0.16f

    private val targets = Collections.newSetFromMap(
        WeakHashMap<LiquidGlassPanelDrawable, Boolean>(),
    )
    private var sensorManager: SensorManager? = null
    private var activeSensor: Sensor? = null
    private var filteredTiltX = 0f
    private var filteredTiltY = 0f
    private val rotationMatrix = FloatArray(9)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun attach(context: Context, drawable: LiquidGlassPanelDrawable) {
        synchronized(targets) {
            targets.add(drawable)
            if (activeSensor != null) return

            // SystemUI 上下文可能没有 applicationContext；传感器注册失败
            // 绝不能阻断玻璃 Drawable 的创建。
            val sensorContext = context.applicationContext ?: context
            val manager = runCatching {
                sensorContext.getSystemService(SensorManager::class.java)
            }.getOrNull() ?: run {
                android.util.Log.w(TAG, "SensorManager unavailable")
                return
            }
            val sensor = manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
                ?: manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
                ?: manager.getDefaultSensor(Sensor.TYPE_GRAVITY)
                ?: run {
                    android.util.Log.w(TAG, "no rotation-vector or gravity sensor")
                    return
                }
            val registered = runCatching {
                manager.registerListener(
                    this,
                    sensor,
                    SensorManager.SENSOR_DELAY_GAME,
                    mainHandler,
                )
            }.getOrDefault(false)
            if (registered) {
                sensorManager = manager
                activeSensor = sensor
                filteredTiltX = 0f
                filteredTiltY = 0f
            }
        }
    }

    fun detach(drawable: LiquidGlassPanelDrawable) {
        synchronized(targets) {
            targets.remove(drawable)
            if (targets.isNotEmpty()) return
            runCatching { sensorManager?.unregisterListener(this) }
            sensorManager = null
            activeSensor = null
            filteredTiltX = 0f
            filteredTiltY = 0f
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val tilt = when (event.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR,
            Sensor.TYPE_ROTATION_VECTOR
            -> rotationVectorTilt(event.values)

            Sensor.TYPE_GRAVITY -> {
                val scale = SensorManager.GRAVITY_EARTH.coerceAtLeast(0.0001f)
                event.values[0] / scale to event.values[1] / scale
            }

            else -> return
        }

        filteredTiltX += (tilt.first.coerceIn(-1f, 1f) - filteredTiltX) * FILTER_ALPHA
        filteredTiltY += (tilt.second.coerceIn(-1f, 1f) - filteredTiltY) * FILTER_ALPHA
        val snapshot = synchronized(targets) { targets.toList() }
        snapshot.forEach { it.applyTilt(filteredTiltX, filteredTiltY) }
    }

    private fun rotationVectorTilt(values: FloatArray): Pair<Float, Float> {
        SensorManager.getRotationMatrixFromVector(rotationMatrix, values)
        // R 把设备坐标变换到世界坐标；第三行即世界竖直方向在设备 X/Y 轴上的投影。
        return rotationMatrix[6] to rotationMatrix[7]
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
