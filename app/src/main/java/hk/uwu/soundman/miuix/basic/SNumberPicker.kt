// Copyright 2025, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package hk.uwu.soundman.miuix.basic

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.flow.distinctUntilChanged
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * S version of [top.yukonga.miuix.kmp.basic.NumberPicker].
 *
 * A [SNumberPicker] component with Miuix style.
 *
 * Modification: fastRoundToInt → roundToInt (4 places).
 *
 * A vertical scroll picker that displays a range of numbers with the selected value centered.
 * Items fade out and scale down as they move away from the center.
 *
 * @param value The current selected value. If outside [range], it will be coerced into the range.
 * @param onValueChange The callback invoked when the selected value changes.
 * @param modifier The modifier to be applied to the [SNumberPicker].
 * @param enabled Whether the [SNumberPicker] is enabled for user interaction.
 * @param range The range of selectable values.
 * @param label A function that converts a value to its display string.
 * @param visibleItemCount The number of visible items. Must be odd and at least 3.
 * @param wrapAround Whether the picker wraps around from the last item to the first (infinite scrolling).
 * @param colors The [SNumberPickerColors] for this [SNumberPicker].
 * @param textStyle The [TextStyle] for the picker items.
 * @param itemHeight The height of each item in the picker.
 */
@Composable
fun SNumberPicker(
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    range: IntRange = 0..10,
    label: (Int) -> String = { it.toString() },
    visibleItemCount: Int = 5,
    wrapAround: Boolean = false,
    colors: SNumberPickerColors = SNumberPickerDefaults.colors(),
    textStyle: TextStyle = MiuixTheme.textStyles.title1,
    itemHeight: Dp = SNumberPickerDefaults.ItemHeight,
) {
    require(visibleItemCount % 2 == 1 && visibleItemCount >= 3) {
        "visibleItemCount must be odd and at least 3, but was $visibleItemCount"
    }
    require(range.first <= range.last) {
        "range must not be empty"
    }

    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val itemCount = range.last - range.first + 1
    val coercedValue = value.coerceIn(range)
    val currentIndex = coercedValue - range.first
    val halfVisibleCount = visibleItemCount / 2
    val hapticFeedback = LocalHapticFeedback.current

    var dragOffset by remember { mutableFloatStateOf(0f) }
    val flingAnimatable = remember { Animatable(0f) }
    var isDragging by remember { mutableStateOf(false) }
    var isUserScrolling by remember { mutableStateOf(false) }
    var itemHeightPx by remember { mutableIntStateOf(0) }

    val totalOffset by remember {
        derivedStateOf { dragOffset + flingAnimatable.value }
    }

    LaunchedEffect(coercedValue) {
        if (!isDragging && dragOffset == 0f) {
            flingAnimatable.snapTo(0f)
        }
    }

    val effectiveIndex by remember(currentIndex, itemCount, wrapAround) {
        derivedStateOf {
            val rawIndex = currentIndex + totalOffset.roundToInt()
            if (wrapAround) {
                ((rawIndex % itemCount) + itemCount) % itemCount
            } else {
                rawIndex.coerceIn(0, itemCount - 1)
            }
        }
    }

    var lastHapticIndex by remember { mutableIntStateOf(currentIndex) }
    LaunchedEffect(Unit) {
        snapshotFlow { effectiveIndex }
            .distinctUntilChanged()
            .collect { index ->
                if (index != lastHapticIndex) {
                    if (isUserScrolling) {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                    lastHapticIndex = index
                }
            }
    }

    val totalHeight = itemHeight * visibleItemCount

    val draggableState = rememberDraggableState { delta ->
        if (itemHeightPx > 0) {
            val newOffset = dragOffset - delta / itemHeightPx
            dragOffset = if (wrapAround) {
                newOffset
            } else {
                newOffset.coerceIn(
                    -(currentIndex.toFloat()),
                    (itemCount - 1 - currentIndex).toFloat(),
                )
            }
        }
    }

    val displayValue = label(coercedValue)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(totalHeight)
            .clipToBounds()
            .semantics {
                contentDescription = "$displayValue, ${range.first} - ${range.last}"
            }
            .onSizeChanged { size ->
                itemHeightPx = size.height / visibleItemCount
            }
            .then(
                if (enabled) {
                    Modifier.draggable(
                        orientation = Orientation.Vertical,
                        state = draggableState,
                        onDragStarted = {
                            flingAnimatable.stop()
                            dragOffset += flingAnimatable.value
                            flingAnimatable.snapTo(0f)
                            isDragging = true
                            isUserScrolling = true
                        },
                        onDragStopped = { velocity ->
                            isDragging = false
                            if (itemHeightPx > 0) {
                                val currentDragOffset = dragOffset
                                dragOffset = 0f
                                flingAnimatable.snapTo(currentDragOffset)

                                val velocityInItems = -velocity / itemHeightPx
                                val decay = exponentialDecay<Float>(frictionMultiplier = 2f)
                                if (!wrapAround) {
                                    val min = -(currentIndex.toFloat())
                                    val max = (itemCount - 1 - currentIndex).toFloat()
                                    flingAnimatable.updateBounds(min, max)
                                }
                                flingAnimatable.animateDecay(velocityInItems, decay)
                                flingAnimatable.updateBounds(
                                    Float.NEGATIVE_INFINITY,
                                    Float.POSITIVE_INFINITY
                                )
                                val snappedTarget = flingAnimatable.value.roundToInt().toFloat()
                                flingAnimatable.animateTo(
                                    targetValue = snappedTarget,
                                    animationSpec = spring(dampingRatio = 1f, stiffness = 400f),
                                )
                                val offsetInt = flingAnimatable.value.roundToInt()
                                val newIndex = if (wrapAround) {
                                    ((currentIndex + offsetInt) % itemCount + itemCount) % itemCount
                                } else {
                                    (currentIndex + offsetInt).coerceIn(0, itemCount - 1)
                                }
                                val newValue = range.first + newIndex
                                if (newValue != coercedValue) {
                                    currentOnValueChange(newValue)
                                }
                                isUserScrolling = false
                                flingAnimatable.snapTo(0f)
                            }
                        },
                    )
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (itemHeightPx > 0) {
            val currentTotalOffset = totalOffset
            val centerItemOffset = currentTotalOffset - currentTotalOffset.roundToInt()
            val roundedOffset = currentTotalOffset.roundToInt()
            val selectedColor = colors.selectedTextColor(enabled)
            val unselectedColor = colors.unselectedTextColor(enabled)
            val resolvedTextStyle =
                if (textStyle.fontWeight == null) textStyle.copy(fontWeight = FontWeight.SemiBold) else textStyle

            for (i in -halfVisibleCount - 1..halfVisibleCount + 1) {
                val rawItemIndex = currentIndex + i + roundedOffset
                val itemIndex = if (wrapAround) {
                    ((rawItemIndex % itemCount) + itemCount) % itemCount
                } else {
                    if (rawItemIndex !in 0..<itemCount) continue
                    rawItemIndex
                }

                val distanceFromCenter = i.toFloat() - centerItemOffset
                val normalizedDistance =
                    (abs(distanceFromCenter) / (halfVisibleCount + 0.5f)).coerceIn(0f, 1f)

                val alpha = (1f - normalizedDistance) * (1f - normalizedDistance * 0.5f)
                val scale = 1f - 0.2f * normalizedDistance
                val yOffset = distanceFromCenter * itemHeightPx

                val textColor = Color(
                    red = lerp(selectedColor.red, unselectedColor.red, normalizedDistance),
                    green = lerp(selectedColor.green, unselectedColor.green, normalizedDistance),
                    blue = lerp(selectedColor.blue, unselectedColor.blue, normalizedDistance),
                    alpha = lerp(selectedColor.alpha, unselectedColor.alpha, normalizedDistance),
                )

                Text(
                    text = label(range.first + itemIndex),
                    modifier = Modifier
                        .graphicsLayer {
                            this.alpha = alpha
                            scaleX = scale
                            scaleY = scale
                            translationY = yOffset
                        },
                    style = resolvedTextStyle,
                    color = textColor,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Contains default values used by [SNumberPicker].
 */
object SNumberPickerDefaults {

    val ItemHeight = 45.dp

    /**
     * Creates the default [SNumberPickerColors] for a [SNumberPicker].
     */
    @Composable
    fun colors(
        selectedTextColor: Color = MiuixTheme.colorScheme.onSurface,
        unselectedTextColor: Color = MiuixTheme.colorScheme.onSurfaceSecondary,
        disabledSelectedTextColor: Color = MiuixTheme.colorScheme.disabledOnSecondary,
        disabledUnselectedTextColor: Color = MiuixTheme.colorScheme.disabledOnSecondary,
    ): SNumberPickerColors = remember(
        selectedTextColor,
        unselectedTextColor,
        disabledSelectedTextColor,
        disabledUnselectedTextColor,
    ) {
        SNumberPickerColors(
            selectedTextColor = selectedTextColor,
            unselectedTextColor = unselectedTextColor,
            disabledSelectedTextColor = disabledSelectedTextColor,
            disabledUnselectedTextColor = disabledUnselectedTextColor,
        )
    }
}

/**
 * The colors for a [SNumberPicker].
 */
@Immutable
data class SNumberPickerColors(
    private val selectedTextColor: Color,
    private val unselectedTextColor: Color,
    private val disabledSelectedTextColor: Color,
    private val disabledUnselectedTextColor: Color,
) {

    @Stable
    fun selectedTextColor(enabled: Boolean): Color =
        if (enabled) selectedTextColor else disabledSelectedTextColor

    @Stable
    fun unselectedTextColor(enabled: Boolean): Color =
        if (enabled) unselectedTextColor else disabledUnselectedTextColor
}
