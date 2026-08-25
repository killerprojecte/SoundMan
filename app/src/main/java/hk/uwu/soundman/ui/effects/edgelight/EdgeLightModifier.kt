package hk.uwu.soundman.ui.effects.edgelight

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil

internal class EdgeLightElement(
    val shape: androidx.compose.ui.graphics.Shape,
    val edgeLight: () -> EdgeLight?
) : ModifierNodeElement<EdgeLightNode>() {

    override fun create(): EdgeLightNode {
        return EdgeLightNode(shape, edgeLight)
    }

    override fun update(node: EdgeLightNode) {
        node.shape = shape
        node.edgeLight = edgeLight
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "edgeLight"
        properties["shape"] = shape
        properties["edgeLight"] = edgeLight
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EdgeLightElement) return false
        if (shape != other.shape) return false
        if (edgeLight != other.edgeLight) return false
        return true
    }

    override fun hashCode(): Int {
        var result = shape.hashCode()
        result = 31 * result + edgeLight.hashCode()
        return result
    }
}

internal class EdgeLightNode(
    var shape: androidx.compose.ui.graphics.Shape,
    var edgeLight: () -> EdgeLight?
) : DrawModifierNode, Modifier.Node() {

    override val shouldAutoInvalidate: Boolean = false

    private var edgeLightLayer: GraphicsLayer? = null

    private val paint =
        Paint().apply {
            style = PaintingStyle.Stroke
        }
    private var clipPath: Path? = null

    private val shaderCache = EdgeLightShaderCache()

    override fun ContentDrawScope.draw() {
        val edgeLight = edgeLight()
        if (edgeLight == null || edgeLight.width.value <= 0f) {
            return drawContent()
        }

        drawContent()

        val edgeLightLayer = edgeLightLayer
        if (edgeLightLayer != null) {
            val size = size
            val density: Density = this
            val layoutDirection = layoutDirection

            val safeSize =
                IntSize(
                    ceil(size.width).toInt() + 2,
                    ceil(size.height).toInt() + 2
                )

            val outline = shape.createOutline(size, layoutDirection, density)
            val clipPath =
                if (outline is Outline.Rounded) {
                    clipPath ?: Path().also { clipPath = it }
                } else {
                    null
                }

            configurePaint(edgeLight)

            edgeLightLayer.alpha = edgeLight.intensity
            edgeLightLayer.blendMode = edgeLight.style.blendMode
            edgeLightLayer.record(safeSize) {
                translate(1f, 1f) {
                    val canvas = drawContext.canvas
                    canvas.save()
                    canvas.clipOutline(outline, clipPath)
                    canvas.drawOutline(outline, paint)
                    canvas.restore()
                }
            }

            translate(-1f, -1f) {
                drawLayer(edgeLightLayer)
            }
        }
    }

    override fun onAttach() {
        val graphicsContext = requireGraphicsContext()
        edgeLightLayer = graphicsContext.createGraphicsLayer()
    }

    override fun onDetach() {
        val graphicsContext = requireGraphicsContext()
        edgeLightLayer?.let { layer ->
            graphicsContext.releaseGraphicsLayer(layer)
            edgeLightLayer = null
        }
        clipPath = null
        shaderCache.clear()
    }

    private fun DrawScope.configurePaint(edgeLight: EdgeLight) {
        paint.color = edgeLight.style.color
        paint.strokeWidth = ceil(edgeLight.width.toPx().coerceAtMost(size.minDimension / 2f)) * 2f
        paint.blur(edgeLight.blurRadius.toPx())
        if (isRuntimeShaderSupported() && edgeLight.style !is EdgeLightStyle.Uniform) {
            val shader = createEdgeLightShader(
                edgeLight = edgeLight,
                size = size,
                shape = shape,
                layoutDirection = layoutDirection,
                density = this,
                shaderCache = shaderCache
            )
            if (shader != null) {
                paint.setRuntimeShader(shader)
            }
        }
    }
}

fun Modifier.edgeLight(
    shape: androidx.compose.ui.graphics.Shape,
    edgeLight: EdgeLight = EdgeLight.Default
): Modifier {
    return this.then(
        EdgeLightElement(
            shape = shape,
            edgeLight = { edgeLight }
        )
    )
}

fun Modifier.edgeLight(
    shape: androidx.compose.ui.graphics.Shape,
    edgeLight: () -> EdgeLight? = { EdgeLight.Default }
): Modifier {
    return this.then(
        EdgeLightElement(
            shape = shape,
            edgeLight = edgeLight
        )
    )
}

fun Modifier.edgeLightIf(
    shape: androidx.compose.ui.graphics.Shape,
    condition: Boolean,
    edgeLight: EdgeLight = EdgeLight.Default
): Modifier {
    return this.then(
        EdgeLightElement(
            shape = shape,
            edgeLight = if (condition) {
                { edgeLight }
            } else {
                { null }
            }
        )
    )
}
