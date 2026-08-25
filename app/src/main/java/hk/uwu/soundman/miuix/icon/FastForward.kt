// Copyright 2025, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package hk.uwu.soundman.miuix.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val FastForward: ImageVector
    get() {
        if (_fastForward != null) return _fastForward!!
        _fastForward = ImageVector.Builder("FastForward", 20.dp, 20.dp, 1024f, 1024f).apply {
            group(
                scaleX = 0.76f,
                scaleY = 0.76f,
                pivotX = 512f,
                pivotY = 512f,
            ) {
                path(
                    fill = SolidColor(Color.Black),
                    fillAlpha = 1f,
                    pathFillType = PathFillType.EvenOdd,
                ) {
                    // First chevron
                    moveTo(461.376f, 945.216f)
                    arcTo(47.808f, 47.808f, 0f, false, true, 462.72f, 877.376f)
                    lineTo(842.752f, 512.064f)
                    lineTo(462.72f, 146.688f)
                    arcTo(48f, 48f, 0f, false, true, 529.088f, 77.312f)
                    lineTo(945.088f, 477.376f)
                    arcTo(48.256f, 48.256f, 0f, false, true, 945.088f, 546.688f)
                    lineTo(529.088f, 946.56f)
                    arcTo(47.744f, 47.744f, 0f, false, true, 461.312f, 945.216f)
                    close()

                    // Second chevron
                    moveTo(77.312f, 945.216f)
                    arcTo(47.808f, 47.808f, 0f, false, true, 78.656f, 877.376f)
                    lineTo(458.688f, 512.064f)
                    lineTo(78.72f, 146.688f)
                    arcTo(48f, 48f, 0f, false, true, 145.152f, 77.312f)
                    lineTo(561.152f, 477.376f)
                    arcTo(48.256f, 48.256f, 0f, false, true, 561.152f, 546.688f)
                    lineTo(145.152f, 946.56f)
                    arcTo(47.808f, 47.808f, 0f, false, true, 77.312f, 945.216f)
                    close()
                }
            }
        }.build()
        return _fastForward!!
    }

private var _fastForward: ImageVector? = null
