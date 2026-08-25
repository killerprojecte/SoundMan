package hk.uwu.soundman.ui.basic

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import com.kyant.backdrop.Backdrop
import hk.uwu.soundman.log.AppLog
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 为第三方 [Backdrop] 提供一次性失效保护。
 *
 * 动机：Backdrop 的坐标和图层只在其创建容器存活期间有效；页面、弹窗或动画切换期间的
 * 失效来源不应让整个 Compose 绘制树崩溃。发生异常后，本包装会停止采样并让调用方的普通
 * 材质层继续显示，同时只输出一次错误日志。
 */
@Composable
internal fun rememberSafeBackdrop(
    backdrop: Backdrop?,
    owner: String,
): Backdrop? = backdrop?.let { source ->
    remember(source, owner) { FailSafeBackdrop(source, owner) }
}

/** 只在当前 Backdrop 仍可安全采样时委托绘制的 Backdrop 实现。 */
private class FailSafeBackdrop(
    private val delegate: Backdrop,
    private val owner: String,
) : Backdrop {
    private val failed = AtomicBoolean(false)
    private val reported = AtomicBoolean(false)

    override val isCoordinatesDependent: Boolean
        get() = guarded(defaultValue = false) { delegate.isCoordinatesDependent }

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
    ) {
        guarded(defaultValue = Unit) {
            with(delegate) {
                drawBackdrop(
                    density = density,
                    coordinates = coordinates,
                    layerBlock = layerBlock,
                )
            }
        }
    }

    private fun <T> guarded(defaultValue: T, block: () -> T): T {
        if (failed.get()) return defaultValue
        return try {
            block()
        } catch (error: Throwable) {
            failed.set(true)
            if (reported.compareAndSet(false, true)) {
                AppLog.error(
                    "Backdrop sampling failed for $owner; falling back to a plain material",
                    error
                )
            }
            defaultValue
        }
    }
}
