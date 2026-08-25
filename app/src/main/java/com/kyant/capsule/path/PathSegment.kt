package com.kyant.capsule.path

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import com.kyant.capsule.core.Point
import java.lang.Math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Patched PathSegment: replaces Android's Path.arcTo() with cubic Bézier approximation
 * to eliminate aliasing artifacts on small corner radii.
 *
 * Original source: https://github.com/Kyant0/Capsule (Apache-2.0)
 * Patch from: NexioSchedule fork
 */
@Immutable
sealed interface PathSegment {

    val from: Point
    val to: Point

    fun drawTo(path: Path)

    data class Line(
        override val from: Point,
        override val to: Point
    ) : PathSegment {

        override fun drawTo(path: Path) {
            path.lineTo(to.x.toFloat(), to.y.toFloat())
        }
    }

    data class Arc(
        val center: Point,
        val radius: Double,
        val startAngle: Double,
        val sweepAngle: Double
    ) : PathSegment {

        override val from: Point
            get() = Point(
                center.x + cos(startAngle) * radius,
                center.y + sin(startAngle) * radius
            )

        override val to: Point
            get() = Point(
                center.x + cos(startAngle + sweepAngle) * radius,
                center.y + sin(startAngle + sweepAngle) * radius
            )

        override fun drawTo(path: Path) {
            // Use cubic Bézier segments to approximate the arc (max 90° each).
            // Android's native arcTo uses polyline approximation which produces
            // visible aliasing on small corner radii.
            val segmentCount = ceil(abs(sweepAngle) / (PI * 0.5)).toInt().coerceAtLeast(1)
            val segmentSweep = sweepAngle / segmentCount
            val k = 4.0 / 3.0 * tan(segmentSweep / 4.0)

            var angle = startAngle
            repeat(segmentCount) {
                val angle1 = angle + segmentSweep
                val p1 = Point(
                    center.x + radius * (cos(angle) - k * sin(angle)),
                    center.y + radius * (sin(angle) + k * cos(angle))
                )
                val p2 = Point(
                    center.x + radius * (cos(angle1) + k * sin(angle1)),
                    center.y + radius * (sin(angle1) - k * cos(angle1))
                )
                val p3 = Point(
                    center.x + radius * cos(angle1),
                    center.y + radius * sin(angle1)
                )
                path.cubicTo(
                    p1.x.toFloat(), p1.y.toFloat(),
                    p2.x.toFloat(), p2.y.toFloat(),
                    p3.x.toFloat(), p3.y.toFloat()
                )
                angle = angle1
            }
        }
    }

    data class Circle(
        val center: Point,
        val radius: Double
    ) : PathSegment {

        override val from: Point
            get() = Point(center.x + radius, center.y)

        override val to: Point
            get() = from

        override fun drawTo(path: Path) {
            path.addOval(
                Rect(
                    (center.x - radius).toFloat(),
                    (center.y - radius).toFloat(),
                    (center.x + radius).toFloat(),
                    (center.y + radius).toFloat()
                )
            )
        }
    }

    data class Cubic(
        val p0: Point,
        val p1: Point,
        val p2: Point,
        val p3: Point
    ) : PathSegment {

        override val from: Point
            get() = p0

        override val to: Point
            get() = p3

        override fun drawTo(path: Path) {
            path.cubicTo(
                p1.x.toFloat(), p1.y.toFloat(),
                p2.x.toFloat(), p2.y.toFloat(),
                p3.x.toFloat(), p3.y.toFloat()
            )
        }
    }
}
