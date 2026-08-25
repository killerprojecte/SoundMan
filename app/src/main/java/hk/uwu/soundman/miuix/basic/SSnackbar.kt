// Copyright 2025, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package hk.uwu.soundman.miuix.basic

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.AccessibilityManager
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SnackbarColors
import top.yukonga.miuix.kmp.basic.SnackbarData
import top.yukonga.miuix.kmp.basic.SnackbarDefaults
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.SnackbarResult
import top.yukonga.miuix.kmp.basic.SnackbarVisuals
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Close
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

/**
 * State of the [SSnackbarHost].
 *
 * This is a reimplementation of [top.yukonga.miuix.kmp.basic.SnackbarHostState] because
 * the original's [currentSnackbars] and [removeEntry] members are internal and cannot
 * be accessed from outside the original miuix package.
 *
 * It allows to show a Snackbar with a message and an optional action.
 */
@Stable
class SSnackbarHostState {
    private val entries = mutableStateListOf<SSnackbarEntry>()
    internal val currentSnackbars: List<SSnackbarEntry> get() = entries
    suspend fun newestSnackbarData(): SnackbarData? = mutex.withLock {
        entries.firstOrNull { it.visible }?.data
    }

    suspend fun oldestSnackbarData(): SnackbarData? = mutex.withLock {
        entries.lastOrNull { it.visible }?.data
    }

    private val mutex = Mutex()
    private var idCounter = 0L

    internal suspend fun removeEntry(entry: SSnackbarEntry) {
        mutex.withLock {
            entries.remove(entry)
        }
    }

    /**
     * Shows a Snackbar with the provided [message].
     *
     * @param message text to be shown in the Snackbar
     * @param actionLabel optional action label to be shown in the Snackbar
     * @param withDismissAction whether to show a dismiss action in the Snackbar
     * @param duration duration of the Snackbar
     * @return result of the Snackbar
     */
    suspend fun showSnackbar(
        message: String,
        actionLabel: String? = null,
        withDismissAction: Boolean = false,
        duration: SnackbarDuration = SnackbarDuration.Short,
    ): SnackbarResult {
        val result = CompletableDeferred<SnackbarResult>()
        val visuals = SnackbarVisuals(message, actionLabel, withDismissAction, duration)

        mutex.withLock {
            val currentId = ++idCounter
            val data = object : SnackbarData {
                override val visuals = visuals
                private val snackbarMutex = Mutex()
                private var completed = false

                override suspend fun dismiss() {
                    snackbarMutex.withLock {
                        if (completed) return
                        completed = true
                    }
                    if (!result.isCompleted) result.complete(SnackbarResult.Dismissed)
                    clear()
                }

                override suspend fun performAction() {
                    snackbarMutex.withLock {
                        if (completed) return
                        completed = true
                    }
                    if (!result.isCompleted) result.complete(SnackbarResult.ActionPerformed)
                    clear()
                }

                private suspend fun clear() {
                    this@SSnackbarHostState.mutex.withLock {
                        val index = entries.indexOfFirst { it.id == currentId }
                        if (index != -1) entries[index] = entries[index].copy(visible = false)
                    }
                }
            }
            val entry = SSnackbarEntry(currentId, data)
            entries.add(0, entry)
        }

        return result.await()
    }

    @Immutable
    internal data class SSnackbarEntry(
        val id: Long,
        val data: SnackbarData,
        val visible: Boolean = true,
    )
}

/**
 * Convert [SnackbarDuration] to milliseconds, taking into account accessibility settings.
 */
internal fun SnackbarDuration.toMillis(
    hasAction: Boolean,
    accessibilityManager: AccessibilityManager?,
): Long {
    val original = when (this) {
        SnackbarDuration.Indefinite -> Long.MAX_VALUE
        SnackbarDuration.Long -> 10000L
        SnackbarDuration.Short -> 4000L
        is SnackbarDuration.Custom -> durationMillis
    }
    if (accessibilityManager == null) {
        return original
    }
    return accessibilityManager.calculateRecommendedTimeoutMillis(
        originalTimeoutMillis = original,
        containsIcons = true,
        containsText = true,
        containsControls = hasAction,
    )
}

private enum class SSnackbarSwipeToDismissValue {
    StartToEnd,
    EndToStart,
    Settled,
}

/**
 * Host for Snackbars to be shown.
 *
 * This is an external reimplementation of the modified SnackbarHost from SSchedule-ref.
 * It uses [SSnackbarHostState] instead of the original [top.yukonga.miuix.kmp.basic.SnackbarHostState]
 * because the original's internal members are not accessible from outside the miuix package.
 *
 * @param state state of the [SSnackbarHost]
 * @param modifier modifier to be applied to the [SSnackbarHost]
 * @param canSwipeToDismiss flag of can be dismissed by swipe of the current [SSnackbarHost]
 * @param content content of the [SSnackbarHost]
 */
@Composable
fun SSnackbarHost(
    state: SSnackbarHostState,
    modifier: Modifier = Modifier,
    canSwipeToDismiss: Boolean = true,
    content: @Composable (SnackbarData) -> Unit = { SSnackbar(it) },
) {
    Box(modifier = modifier, contentAlignment = Alignment.BottomCenter) {
        LazyColumn(
            reverseLayout = true,
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(bottom = 12.dp),
        ) {
            itemsIndexed(state.currentSnackbars, key = { _, entry -> entry.id }) { index, entry ->
                val visibleState = remember { MutableTransitionState(false) }
                val accessibilityManager = LocalAccessibilityManager.current

                val anchoredDraggableState = remember {
                    AnchoredDraggableState(
                        initialValue = SSnackbarSwipeToDismissValue.Settled,
                    )
                }

                visibleState.targetState = entry.visible

                if (!visibleState.targetState && visibleState.isIdle) {
                    LaunchedEffect(entry) { state.removeEntry(entry) }
                }

                LaunchedEffect(entry) {
                    val duration = entry.data.visuals.duration.toMillis(
                        entry.data.visuals.actionLabel != null,
                        accessibilityManager,
                    )
                    delay(duration.milliseconds)

                    if (anchoredDraggableState.currentValue != SSnackbarSwipeToDismissValue.Settled) return@LaunchedEffect
                    entry.data.dismiss()
                }

                LaunchedEffect(anchoredDraggableState.currentValue) {
                    if (anchoredDraggableState.currentValue != SSnackbarSwipeToDismissValue.Settled) {
                        entry.data.dismiss()
                    }
                }

                AnimatedVisibility(
                    modifier = Modifier
                        .onSizeChanged { size ->
                            val width = size.width.toFloat()

                            val anchors = DraggableAnchors {
                                SSnackbarSwipeToDismissValue.Settled at 0f
                                SSnackbarSwipeToDismissValue.StartToEnd at width
                                SSnackbarSwipeToDismissValue.EndToStart at -width
                            }
                            anchoredDraggableState.updateAnchors(anchors)
                        }
                        .anchoredDraggable(
                            state = anchoredDraggableState,
                            orientation = Orientation.Horizontal,
                            enabled = entry.visible && canSwipeToDismiss,
                            flingBehavior = AnchoredDraggableDefaults.flingBehavior(
                                state = anchoredDraggableState,
                                positionalThreshold = { distance: Float -> distance * 0.5f },
                            ),
                        )
                        .offset {
                            val offset = try {
                                anchoredDraggableState.requireOffset()
                            } catch (_: IllegalStateException) {
                                0f
                            }
                            IntOffset(offset.roundToInt(), 0)
                        }
                        .zIndex((state.currentSnackbars.size - index).toFloat())
                        .then(if (entry.visible) Modifier.animateItem() else Modifier),
                    visibleState = visibleState,
                    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut() + shrinkVertically(
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                        shrinkTowards = Alignment.Bottom,
                    ),
                ) {
                    content(entry.data)
                }
            }
        }
    }
}

/**
 * A Snackbar is a temporary message that appears at the bottom of the screen.
 *
 * This is an external reimplementation that uses [STextButton] instead of the
 * original TextButton (which lacks the textStyle/textColor parameters in the original miuix).
 *
 * @param data data of the [SSnackbar]
 * @param modifier modifier to be applied to the [SSnackbar]
 * @param cornerRadius corner radius of the [SSnackbar]
 * @param colors colors of the [SSnackbar]
 * @param insideMargin margin inside the [SSnackbar]
 */
@Composable
fun SSnackbar(
    data: SnackbarData,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = SnackbarDefaults.CornerRadius,
    colors: SnackbarColors = SnackbarDefaults.snackbarColors(),
    insideMargin: PaddingValues = SnackbarDefaults.InsideMargin,
) {
    val visuals = data.visuals
    val scope = rememberCoroutineScope()

    CompositionLocalProvider(
        LocalContentColor provides colors.contentColor,
    ) {
        Box(
            modifier = modifier
                .semantics(mergeDescendants = false) {
                    isTraversalGroup = true
                    liveRegion = LiveRegionMode.Polite
                }
                .padding(SnackbarDefaults.OuterPadding)
                .dropShadow(
                    shape = RoundedCornerShape(cornerRadius),
                    shadow = Shadow(
                        radius = 10.dp,
                        color = Color.Black,
                        alpha = 0.1f,
                    ),
                )
                .squircleBackground(color = colors.containerColor, cornerRadius = cornerRadius)
                .pointerInput(Unit) {
                    detectTapGestures { /* Consume click */ }
                },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .padding(insideMargin),
            ) {
                Text(
                    text = visuals.message,
                    color = colors.contentColor,
                    style = MiuixTheme.textStyles.body2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )

                if (!visuals.actionLabel.isNullOrEmpty()) {
                    val actionLabel = visuals.actionLabel!!
                    val onAction by rememberUpdatedState(data::performAction)
                    STextButton(
                        text = actionLabel,
                        onClick = { scope.launch { onAction() } },
                        modifier = Modifier.padding(start = 12.dp),
                        cornerRadius = SnackbarDefaults.ActionCornerRadius,
                        minWidth = 26.dp,
                        minHeight = 26.dp,
                        colors = SButtonDefaults.sPrimaryButtonColors(
                            color = colors.actionContainerColor,
                            textColor = colors.actionContentColor,
                        ),
                        insideMargin = SnackbarDefaults.ActionInsideMargin,
                        textStyle = TextStyle(fontSize = 15.sp),
                    )
                }

                if (visuals.withDismissAction) {
                    val onDismiss by rememberUpdatedState(data::dismiss)
                    Icon(
                        imageVector = MiuixIcons.Basic.Close,
                        contentDescription = "Dismiss",
                        tint = colors.dismissActionContentColor,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .size(20.dp)
                            .clickable(
                                indication = null,
                                interactionSource = null,
                            ) {
                                scope.launch { onDismiss() }
                            },
                    )
                }
            }
        }
    }
}
