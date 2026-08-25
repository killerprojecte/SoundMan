// Copyright 2025, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package hk.uwu.soundman.miuix.basic

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.kyant.capsule.ContinuousCapsule
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.absoluteValue

/**
 * A [SSwitch] component with Miuix style.
 *
 * This is an external reimplementation of the modified Switch from SSchedule-ref.
 * It uses [SSwitchColors] instead of the original [top.yukonga.miuix.kmp.basic.SwitchColors]
 * because the original's color accessor methods are internal.
 *
 * @param checked The checked state of the [SSwitch].
 * @param onCheckedChange The callback to be called when the state of the [SSwitch] changes.
 * @param modifier The modifier to be applied to the [SSwitch].
 * @param colors The [SSwitchColors] of the [SSwitch].
 * @param enabled Whether the [SSwitch] is enabled.
 */
@Composable
fun SSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    colors: SSwitchColors = SSwitchDefaults.sSwitchColors(),
    enabled: Boolean = true,
) {
    val currentOnCheckedChange by rememberUpdatedState(onCheckedChange)
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isDragged by interactionSource.collectIsDraggedAsState()
    val isHovered by interactionSource.collectIsHoveredAsState()

    val hapticFeedback = LocalHapticFeedback.current
    val currentHapticFeedback by rememberUpdatedState(hapticFeedback)
    var hasVibrated by remember { mutableStateOf(false) }
    var hasVibratedOnce by remember { mutableStateOf(false) }
    var rawDragOffset by remember { mutableFloatStateOf(0f) }
    var currentDragInteraction by remember { mutableStateOf<DragInteraction.Start?>(null) }

    val capsuleShape = ContinuousCapsule()
    val thumbOffsetSpringSpec = remember { spring<Dp>(dampingRatio = 0.7f, stiffness = 987f) }
    val thumbScaleSpringSpec = remember { spring<Float>(dampingRatio = 0.6f, stiffness = 987f) }

    var dragOffset by remember { mutableFloatStateOf(0f) }
    val thumbOffsetState = animateDpAsState(
        targetValue = (if (checked) 25.dp else 4.dp) + dragOffset.dp,
        animationSpec = thumbOffsetSpringSpec,
    )

    val thumbScaleState = animateFloatAsState(
        targetValue = if (!enabled) {
            1f
        } else if (isPressed || isDragged || isHovered) {
            1.127f
        } else {
            1f
        },
        animationSpec = thumbScaleSpringSpec,
    )

    val thumbColorState = animateColorAsState(
        if (checked) colors.checkedThumbColor(enabled) else colors.uncheckedThumbColor(enabled),
    )

    val backgroundColorState = animateColorAsState(
        if (checked) colors.checkedTrackColor(enabled) else colors.uncheckedTrackColor(enabled),
        animationSpec = spring(dampingRatio = 0.99f, stiffness = 438.6f),
    )

    val hasCallback = onCheckedChange != null
    val toggleableModifier = if (hasCallback) {
        remember(checked, enabled, interactionSource) {
            Modifier.toggleable(
                value = checked,
                onValueChange = { v ->
                    currentOnCheckedChange?.invoke(v)
                    currentHapticFeedback.performHapticFeedback(
                        if (v) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff,
                    )
                },
                enabled = enabled,
                role = Role.Switch,
                interactionSource = interactionSource,
            )
        }
    } else {
        Modifier.semantics {
            role = Role.Switch
            toggleableState = ToggleableState(checked)
            if (!enabled) disabled()
        }
    }

    Box(
        modifier = modifier
            .wrapContentSize(Alignment.Center)
            .size(49.dp, 28.dp)
            .clip(capsuleShape)
            .drawBehind {
                drawRect(backgroundColorState.value)
            }
            .hoverable(
                interactionSource = interactionSource,
                enabled = enabled,
            )
            .then(toggleableModifier),
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .align(Alignment.CenterStart)
                .offset {
                    IntOffset(thumbOffsetState.value.roundToPx(), 0)
                }
                .graphicsLayer {
                    scaleX = thumbScaleState.value
                    scaleY = thumbScaleState.value
                }
                .drawBehind {
                    drawCircle(color = thumbColorState.value)
                }
                .then(
                    if (enabled) {
                        Modifier.draggable(
                            orientation = Orientation.Horizontal,
                            state = rememberDraggableState { dragAmount ->
                                rawDragOffset += dragAmount / 2f
                                dragOffset = if (checked) {
                                    rawDragOffset.coerceIn(-21f, 0f)
                                } else {
                                    rawDragOffset.coerceIn(0f, 21f)
                                }

                                if (dragOffset in -11f..-10f || dragOffset in 10f..11f) {
                                    hasVibratedOnce = false
                                } else if (dragOffset in -20f..-1f || dragOffset in 1f..20f) {
                                    hasVibrated = false
                                } else if (!hasVibrated) {
                                    if ((checked && dragOffset == -21f) || (!checked && dragOffset == 0f)) {
                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.ToggleOff)
                                        hasVibrated = true
                                        hasVibratedOnce = true
                                    } else if ((checked && dragOffset == 0f) || (!checked && dragOffset == 21f)) {
                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.ToggleOn)
                                        hasVibrated = true
                                        hasVibratedOnce = true
                                    }
                                }
                            },
                            onDragStarted = { _ ->
                                currentDragInteraction =
                                    DragInteraction.Start().also { interactionSource.tryEmit(it) }
                                hasVibrated = true
                                hasVibratedOnce = false
                                rawDragOffset = 0f
                            },
                            onDragStopped = {
                                if (dragOffset.absoluteValue > 21f / 2f) currentOnCheckedChange?.invoke(
                                    !checked
                                )
                                if (!hasVibratedOnce && dragOffset.absoluteValue >= 1f) {
                                    if ((checked && dragOffset <= -11f) || (!checked && dragOffset <= 10f)) {
                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.ToggleOff)
                                    } else if ((checked && dragOffset >= -10f) || (!checked && dragOffset >= 11f)) {
                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.ToggleOn)
                                    }
                                }
                                currentDragInteraction?.let {
                                    interactionSource.tryEmit(
                                        DragInteraction.Stop(it)
                                    )
                                }
                                dragOffset = 0f
                                rawDragOffset = 0f
                            },
                        )
                    } else {
                        Modifier
                    },
                ),
        )
    }
}

/**
 * Object providing default values and color schemes for [SSwitch].
 *
 * Note: The original miuix SwitchColors has internal accessor methods, so we use
 * [SSwitchColors] with public accessors instead.
 * The original used LocalColors.current (which is internal) for dynamic color checks,
 * but since MiuixTheme.colorScheme is the same as LocalColors.current, we use
 * MiuixTheme.colorScheme directly.
 */
object SSwitchDefaults {

    /**
     * The default colors for the [SSwitch].
     */
    @Composable
    fun sSwitchColors(
        checkedThumbColor: Color = MiuixTheme.colorScheme.onPrimary,
        uncheckedThumbColor: Color = MiuixTheme.colorScheme.onSecondary,
        disabledCheckedThumbColor: Color = MiuixTheme.colorScheme.disabledOnPrimary,
        disabledUncheckedThumbColor: Color = MiuixTheme.colorScheme.disabledOnSecondary,
        checkedTrackColor: Color = MiuixTheme.colorScheme.primary,
        uncheckedTrackColor: Color = if (MiuixTheme.colorScheme.background.luminance() < 0.5f) Color(
            0xFF424242
        ) else Color(0xFFECECEC),
        disabledCheckedTrackColor: Color = MiuixTheme.colorScheme.disabledPrimary,
        disabledUncheckedTrackColor: Color = MiuixTheme.colorScheme.disabledSecondary,
    ): SSwitchColors = remember(
        checkedThumbColor,
        uncheckedThumbColor,
        disabledCheckedThumbColor,
        disabledUncheckedThumbColor,
        checkedTrackColor,
        uncheckedTrackColor,
        disabledCheckedTrackColor,
        disabledUncheckedTrackColor,
    ) {
        SSwitchColors(
            checkedThumbColor = checkedThumbColor,
            uncheckedThumbColor = uncheckedThumbColor,
            disabledCheckedThumbColor = disabledCheckedThumbColor,
            disabledUncheckedThumbColor = disabledUncheckedThumbColor,
            checkedTrackColor = checkedTrackColor,
            uncheckedTrackColor = uncheckedTrackColor,
            disabledCheckedTrackColor = disabledCheckedTrackColor,
            disabledUncheckedTrackColor = disabledUncheckedTrackColor,
        )
    }
}

/**
 * Color scheme for [SSwitch].
 *
 * Unlike the original [top.yukonga.miuix.kmp.basic.SwitchColors], the accessor methods
 * here are public (not internal) so they can be used from outside the original miuix package.
 *
 * @param checkedThumbColor The thumb color when checked and enabled.
 * @param uncheckedThumbColor The thumb color when unchecked and enabled.
 * @param disabledCheckedThumbColor The thumb color when checked and disabled.
 * @param disabledUncheckedThumbColor The thumb color when unchecked and disabled.
 * @param checkedTrackColor The track color when checked and enabled.
 * @param uncheckedTrackColor The track color when unchecked and enabled.
 * @param disabledCheckedTrackColor The track color when checked and disabled.
 * @param disabledUncheckedTrackColor The track color when unchecked and disabled.
 */
@Immutable
data class SSwitchColors(
    private val checkedThumbColor: Color,
    private val uncheckedThumbColor: Color,
    private val disabledCheckedThumbColor: Color,
    private val disabledUncheckedThumbColor: Color,
    private val checkedTrackColor: Color,
    private val uncheckedTrackColor: Color,
    private val disabledCheckedTrackColor: Color,
    private val disabledUncheckedTrackColor: Color,
) {
    @Stable
    fun checkedThumbColor(enabled: Boolean): Color =
        if (enabled) checkedThumbColor else disabledCheckedThumbColor

    @Stable
    fun uncheckedThumbColor(enabled: Boolean): Color =
        if (enabled) uncheckedThumbColor else disabledUncheckedThumbColor

    @Stable
    fun checkedTrackColor(enabled: Boolean): Color =
        if (enabled) checkedTrackColor else disabledCheckedTrackColor

    @Stable
    fun uncheckedTrackColor(enabled: Boolean): Color =
        if (enabled) uncheckedTrackColor else disabledUncheckedTrackColor
}
