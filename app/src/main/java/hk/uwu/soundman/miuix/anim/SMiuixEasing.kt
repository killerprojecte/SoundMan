// Copyright 2025, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package hk.uwu.soundman.miuix.anim

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import kotlin.math.PI

/**
 * Creates a [SpringSpec] from damping and response parameters.
 *
 * S version: visibilityThreshold parameter removed (original miuix has default param,
 * so this is backward compatible, but we create a standalone version for completeness).
 *
 * @param damping The damping ratio. 1.0 = critically damped (no overshoot),
 *   < 1.0 = underdamped (oscillates), > 1.0 = overdamped.
 * @param response The response time in seconds. Smaller values = faster animation.
 */
fun <T> sFolmeSpring(
    damping: Float,
    response: Float,
): SpringSpec<T> {
    val stiffness = ((2.0 * PI / response) * (2.0 * PI / response)).toFloat()
    return spring(dampingRatio = damping, stiffness = stiffness)
}
