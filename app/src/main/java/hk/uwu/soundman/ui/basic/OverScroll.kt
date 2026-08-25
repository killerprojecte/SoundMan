// 基于 miuix 源码修改，添加 offset 属性用于检测超出滚动量
package hk.uwu.soundman.ui.basic

import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollDispatcher
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScrollModifierNode
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidatePlacement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.utils.Platform
import top.yukonga.miuix.kmp.utils.platform
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sign

// ==================== SpringUtils (from miuix source) ====================

private object SpringMath {
    const val MAX_FRAME_DELTA_SECONDS = 0.016f
    const val MIN_FRAME_DELTA_SECONDS = 0.001f
    const val HIGH_VELOCITY_THRESHOLD = 5000.0
    const val CRITICAL_DAMPING_RATIO = 1.0f
    const val STANDARD_SPRING_PERIOD = 0.4f
    const val SLOWER_SPRING_PERIOD_FOR_HIGH_VELOCITY = 0.55f

    fun obtainDampingDistance(normalizedInput: Float, range: Float): Float {
        val x = max(0.0f, min(normalizedInput, 1.0f)).toDouble()
        val dampedFactor = x - x.pow(2.0) + (x.pow(3.0) / 3.0)
        return (dampedFactor * range).toFloat()
    }

    fun obtainTouchDistance(currentPixelOffset: Float, range: Float): Float {
        var absPixelOffset = abs(currentPixelOffset)
        val absMaxOffset = abs(obtainDampingDistance(1.0f, range))
        if (absPixelOffset <= 0f) return 0f
        if (absPixelOffset >= absMaxOffset) absPixelOffset = absMaxOffset
        val base = range - (3.0 * absPixelOffset)
        val part2 = range.toDouble().pow(2.0 / 3.0) * sign(base) * abs(base).pow(1.0 / 3.0)
        return (range - part2).toFloat()
    }
}

private class SpringOperator(dampingRatio: Float, naturalPeriod: Float) {
    private val dampingCoefficient: Double
    private val stiffnessOverMass: Double

    init {
        val angularFrequency = (2.0 * PI) / naturalPeriod
        stiffnessOverMass = angularFrequency * angularFrequency
        dampingCoefficient = 2.0 * dampingRatio * angularFrequency
    }

    fun updateVelocity(
        currentVelocity: Double,
        deltaTime: Float,
        currentPosition: Double,
        targetPosition: Double,
    ): Double {
        val velocityDecayFactor = 1.0 - dampingCoefficient * deltaTime
        val velocityIncreaseFromSpring =
            stiffnessOverMass * (targetPosition - currentPosition) * deltaTime
        return currentVelocity * velocityDecayFactor + velocityIncreaseFromSpring
    }
}

private class SpringEngine {
    private var springOperator: SpringOperator? = null
    var velocity: Double = 0.0
    var currentPos: Double = 0.0
    private var targetPos: Double = 0.0
    private var initialPos: Double = 0.0
    private var initialVelocity: Double = 0.0

    private fun isAtEquilibrium(): Boolean {
        if (initialPos < targetPos && currentPos > targetPos) return true
        if (initialPos <= targetPos || currentPos >= targetPos) {
            return (initialPos == targetPos && sign(initialVelocity) != sign(currentPos)) || abs(
                currentPos - targetPos
            ) < 1.0
        }
        return true
    }

    fun start(startValue: Float, targetValue: Float, initialVel: Float) {
        currentPos = startValue.toDouble()
        initialPos = startValue.toDouble()
        targetPos = targetValue.toDouble()
        velocity = initialVel.toDouble()
        initialVelocity = initialVel.toDouble()
        springOperator = SpringOperator(
            SpringMath.CRITICAL_DAMPING_RATIO,
            if (abs(initialVel) > SpringMath.HIGH_VELOCITY_THRESHOLD) {
                SpringMath.SLOWER_SPRING_PERIOD_FOR_HIGH_VELOCITY
            } else {
                SpringMath.STANDARD_SPRING_PERIOD
            },
        )
    }

    fun step(deltaTime: Float): Boolean {
        val operator = springOperator ?: return false
        val dt = deltaTime.coerceIn(
            SpringMath.MIN_FRAME_DELTA_SECONDS,
            SpringMath.MAX_FRAME_DELTA_SECONDS
        )
        velocity = operator.updateVelocity(velocity, dt, currentPos, targetPos)
        currentPos += dt * velocity
        if (isAtEquilibrium()) {
            currentPos = targetPos
            velocity = 0.0
            return true
        }
        return false
    }
}

private suspend fun SpringEngine.runSettleAnimation(
    startValue: Float,
    targetValue: Float = 0f,
    initialVelocity: Float,
    onFrame: (currentPos: Float) -> Unit,
    onSettle: () -> Unit,
) {
    start(startValue = startValue, targetValue = targetValue, initialVel = initialVelocity)
    var lastFrameTimeNanos = -1L
    var isFinished = false
    try {
        while (!isFinished && currentCoroutineContext().isActive) {
            isFinished = androidx.compose.runtime.withFrameNanos { frameTimeNanos ->
                if (lastFrameTimeNanos == -1L) {
                    lastFrameTimeNanos = frameTimeNanos
                    return@withFrameNanos false
                }
                val dt = (frameTimeNanos - lastFrameTimeNanos) / 1_000_000_000f
                lastFrameTimeNanos = frameTimeNanos
                val finished = step(dt)
                onFrame(currentPos.toFloat())
                finished
            }
        }
    } finally {
        onSettle()
    }
}

// ==================== OverScrollState (modified to expose offset) ====================

/**
 * Modified OverScrollState that exposes the overscroll offset.
 */
@Stable
class OverScrollState {
    var isOverScrollActive by mutableStateOf(false)
        internal set

    /** 超出滚动偏移量，正值表示向下超出，负值表示向上超出 */
    var offset by mutableFloatStateOf(0f)
        internal set
}

val LocalOverScrollState = compositionLocalOf { OverScrollState() }

// ==================== Modifier extensions ====================

@Stable
fun Modifier.overScrollVertical(
    nestedScrollToParent: Boolean = true,
    isEnabled: () -> Boolean = { platform() == Platform.Android || platform() == Platform.IOS },
): Modifier = overScrollOutOfBound(
    isVertical = true,
    nestedScrollToParent = nestedScrollToParent,
    isEnabled = isEnabled
)

@Stable
fun Modifier.overScrollHorizontal(
    nestedScrollToParent: Boolean = true,
    isEnabled: () -> Boolean = { platform() == Platform.Android || platform() == Platform.IOS },
): Modifier = overScrollOutOfBound(
    isVertical = false,
    nestedScrollToParent = nestedScrollToParent,
    isEnabled = isEnabled
)

@Stable
fun Modifier.overScrollOutOfBound(
    isVertical: Boolean = true,
    nestedScrollToParent: Boolean = true,
    isEnabled: () -> Boolean = { platform() == Platform.Android || platform() == Platform.IOS },
): Modifier {
    if (!isEnabled()) return this
    return this
        .clipToBounds()
        .then(OverscrollElement(isVertical, nestedScrollToParent))
}

// ==================== OverscrollNode ====================

private data class OverscrollElement(
    val isVertical: Boolean,
    val nestedScrollToParent: Boolean,
) : ModifierNodeElement<OverscrollNode>() {
    override fun create(): OverscrollNode = OverscrollNode(isVertical, nestedScrollToParent)
    override fun update(node: OverscrollNode) {
        node.update(isVertical, nestedScrollToParent)
        node.invalidatePlacement()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "overScrollOutOfBound"
        properties["isVertical"] = isVertical
        properties["nestedScrollToParent"] = nestedScrollToParent
    }
}

private class OverscrollNode(
    var isVertical: Boolean,
    var nestedScrollToParent: Boolean,
) : DelegatingNode(), CompositionLocalConsumerModifierNode, LayoutModifierNode,
    NestedScrollConnection {
    private val density: Density get() = currentValueOf(LocalDensity)
    private val windowInfo: WindowInfo get() = currentValueOf(LocalWindowInfo)
    private val overScrollState: OverScrollState get() = currentValueOf(LocalOverScrollState)
    private val dispatcher = NestedScrollDispatcher()
    private val springEngine = SpringEngine()
    private var animationJob: Job? = null
    private val offsetThreshold = 1f
    private var lastPlacedOffset = 0f

    var offset = 0f
        private set(value) {
            if (field != value) {
                field = value
                overScrollState.offset = value
                val rounded = round(value)
                if (rounded != lastPlacedOffset) {
                    lastPlacedOffset = rounded
                    if (isAttached) invalidatePlacement()
                }
            }
        }

    private var rawTouchAccumulation = 0f
    private var scrollRange: Float = 0f
    private var cachedScrollRangeDensity: Density? = null
    private var cachedScrollRangeWindowInfo: WindowInfo? = null

    override fun onAttach() {
        super.onAttach()
        updateScrollRange()
        delegate(nestedScrollModifierNode(this, dispatcher))
    }

    override fun onDetach() {
        super.onDetach()
        resetState()
    }

    fun update(isVertical: Boolean, nestedScrollToParent: Boolean) {
        val rangeChanged = this.isVertical != isVertical
        this.isVertical = isVertical
        this.nestedScrollToParent = nestedScrollToParent
        if (rangeChanged && isAttached) updateScrollRange()
    }

    private fun updateScrollRange() {
        val currentDensity = density
        val currentWindowInfo = windowInfo
        if (currentDensity == cachedScrollRangeDensity && currentWindowInfo == cachedScrollRangeWindowInfo) return
        cachedScrollRangeDensity = currentDensity
        cachedScrollRangeWindowInfo = currentWindowInfo
        scrollRange = with(currentDensity) {
            if (isVertical) currentWindowInfo.containerDpSize.height.toPx()
            else currentWindowInfo.containerDpSize.width.toPx()
        }
    }

    private fun resetState() {
        offset = 0f
        rawTouchAccumulation = 0f
        if (isAttached) {
            overScrollState.isOverScrollActive = false
            overScrollState.offset = 0f
        }
    }

    private fun startSpringAnimation(initialVelocity: Float = 0f) {
        if (abs(offset) <= offsetThreshold && initialVelocity == 0f) {
            resetState()
            return
        }
        animationJob?.cancel()
        animationJob = coroutineScope.launch {
            springEngine.runSettleAnimation(
                startValue = offset,
                initialVelocity = initialVelocity,
                onFrame = { currentPos -> offset = currentPos },
                onSettle = { if (abs(offset) <= offsetThreshold) resetState() },
            )
        }
    }

    private fun applyDrag(delta: Float) {
        if (delta == 0f) return
        rawTouchAccumulation += delta
        rawTouchAccumulation = rawTouchAccumulation.coerceIn(-scrollRange, scrollRange)
        val normalized = min(abs(rawTouchAccumulation) / scrollRange, 1.0f)
        val dampedDist = SpringMath.obtainDampingDistance(normalized, scrollRange)
        offset = sign(rawTouchAccumulation) * dampedDist
    }

    private fun syncRawAccumulationFromOffset() {
        rawTouchAccumulation = sign(offset) * SpringMath.obtainTouchDistance(offset, scrollRange)
    }

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints
    ): MeasureResult {
        updateScrollRange()
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(0, 0) {
                if (isVertical) translationY = round(offset)
                else translationX = round(offset)
                clip = true
            }
        }
    }

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (!isAttached) return Offset.Zero
        val isActive = abs(offset) > offsetThreshold
        if (overScrollState.isOverScrollActive != isActive) overScrollState.isOverScrollActive =
            isActive
        if (source != NestedScrollSource.UserInput) return dispatcher.dispatchPreScroll(
            available,
            source
        )
        if (animationJob?.isActive == true) syncRawAccumulationFromOffset()
        animationJob?.cancel()
        val parentConsumed = if (nestedScrollToParent) dispatcher.dispatchPreScroll(
            available,
            source
        ) else Offset.Zero
        val realAvailable = available - parentConsumed
        val delta = if (isVertical) realAvailable.y else realAvailable.x
        if (abs(offset) <= offsetThreshold || sign(delta) == sign(rawTouchAccumulation)) return parentConsumed
        if (sign(delta) != sign(rawTouchAccumulation)) {
            val actualConsumed =
                if (abs(rawTouchAccumulation) <= abs(delta)) -rawTouchAccumulation else delta
            if (abs(rawTouchAccumulation) <= abs(delta)) resetState() else applyDrag(actualConsumed)
            return if (isVertical) Offset(parentConsumed.x, actualConsumed + parentConsumed.y)
            else Offset(actualConsumed + parentConsumed.x, parentConsumed.y)
        }
        applyDrag(delta)
        return if (isVertical) Offset(parentConsumed.x, available.y) else Offset(
            available.x,
            parentConsumed.y
        )
    }

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource
    ): Offset {
        if (!isAttached) return Offset.Zero
        val isActive = abs(offset) > offsetThreshold
        if (overScrollState.isOverScrollActive != isActive) overScrollState.isOverScrollActive =
            isActive
        if (source != NestedScrollSource.UserInput) return dispatcher.dispatchPostScroll(
            consumed,
            available,
            source
        )
        animationJob?.cancel()
        val parentConsumed = if (nestedScrollToParent) dispatcher.dispatchPostScroll(
            consumed,
            available,
            source
        ) else Offset.Zero
        val realAvailable = available - parentConsumed
        val delta = if (isVertical) realAvailable.y else realAvailable.x
        applyDrag(delta)
        return if (isVertical) Offset(parentConsumed.x, available.y) else Offset(
            available.x,
            parentConsumed.y
        )
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (!isAttached) return Velocity.Zero
        val isActive = abs(offset) > offsetThreshold
        if (overScrollState.isOverScrollActive != isActive) overScrollState.isOverScrollActive =
            isActive
        animationJob?.cancel()
        val parentConsumed =
            if (nestedScrollToParent) dispatcher.dispatchPreFling(available) else Velocity.Zero
        val realAvailable = available - parentConsumed
        val velocity = if (isVertical) realAvailable.y else realAvailable.x
        if (abs(offset) > offsetThreshold) {
            startSpringAnimation(velocity)
            return parentConsumed + if (isVertical) Velocity(0f, realAvailable.y / 2.13333f)
            else Velocity(realAvailable.x / 2.13333f, 0f)
        }
        return parentConsumed
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        if (!isAttached) return Velocity.Zero
        val isActive = abs(offset) > offsetThreshold
        if (overScrollState.isOverScrollActive != isActive) overScrollState.isOverScrollActive =
            isActive
        animationJob?.cancel()
        val parentConsumed = if (nestedScrollToParent) dispatcher.dispatchPostFling(
            consumed,
            available
        ) else Velocity.Zero
        val realAvailable = available - parentConsumed
        val velocity = (if (isVertical) realAvailable.y else realAvailable.x) / 1.53333f
        startSpringAnimation(velocity)
        return parentConsumed + if (isVertical) Velocity(0f, velocity) else Velocity(velocity, 0f)
    }
}
