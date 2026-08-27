package hk.uwu.soundman.hook.scopes.systemui.runtime

import hk.uwu.soundman.hook.scopes.systemui.hidden.OfficialExpandedMaterialMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquidGlassPanelPolicyTest {
    @Test
    fun shouldAttachRequiresGlassEnabled() {
        OfficialExpandedMaterialMode.entries.forEach { mode ->
            assertFalse(
                "glass disabled must never attach (mode=$mode)",
                LiquidGlassPanelPolicy.shouldAttach(mode, glassEnabled = false),
            )
        }
        assertFalse(LiquidGlassPanelPolicy.shouldAttach(null, glassEnabled = false))
    }

    @Test
    fun shouldAttachRequiresKnownMaterialMode() {
        assertFalse(LiquidGlassPanelPolicy.shouldAttach(null, glassEnabled = true))
    }

    @Test
    fun shouldAttachSkipsAdvancedOfficialGlass() {
        assertFalse(
            LiquidGlassPanelPolicy.shouldAttach(
                OfficialExpandedMaterialMode.ADVANCED,
                glassEnabled = true,
            ),
        )
    }

    @Test
    fun shouldAttachOnBlurAndStaticMaterials() {
        assertTrue(
            LiquidGlassPanelPolicy.shouldAttach(
                OfficialExpandedMaterialMode.THEME_BLUR,
                glassEnabled = true,
            ),
        )
        assertTrue(
            LiquidGlassPanelPolicy.shouldAttach(
                OfficialExpandedMaterialMode.BLUR_FOR_S,
                glassEnabled = true,
            ),
        )
        assertTrue(
            LiquidGlassPanelPolicy.shouldAttach(
                OfficialExpandedMaterialMode.STATIC,
                glassEnabled = true,
            ),
        )
    }

    @Test
    fun refractionRequiresGlassEnabled() {
        assertFalse(LiquidGlassPanelPolicy.refractionActive(glassEnabled = false, refractionEnabled = true))
        assertFalse(LiquidGlassPanelPolicy.refractionActive(glassEnabled = false, refractionEnabled = false))
        assertFalse(LiquidGlassPanelPolicy.refractionActive(glassEnabled = true, refractionEnabled = false))
        assertTrue(LiquidGlassPanelPolicy.refractionActive(glassEnabled = true, refractionEnabled = true))
    }

    @Test
    fun configDefaultsReplicateHyperIslandTuning() {
        assertEquals(0.16f, LiquidGlassPanelConfig.EDGE_WIDTH)
        assertEquals(0.16f, LiquidGlassPanelConfig.REFRACTION)
        assertEquals(0.42f, LiquidGlassPanelConfig.HIGHLIGHT)
        assertEquals(0.14f, LiquidGlassPanelConfig.SHADOW)
        assertEquals(243, LiquidGlassPanelConfig.LIGHT_DIRECTION)
        assertEquals(0.18f, LiquidGlassPanelConfig.DISPERSION)
        assertTrue(LiquidGlassPanelConfig.GYROSCOPE)
        assertEquals(20, LiquidGlassPanelConfig.CAPTURE_FPS)
        assertEquals(0.3f, LiquidGlassPanelConfig.CAPTURE_SCALE)
        assertEquals(20f, LiquidGlassPanelConfig.CAPTURE_BLUR_RADIUS)
        assertEquals(0x20FFFFFF, LiquidGlassPanelConfig.BLEND_COLOR)

        val config = LiquidGlassPanelConfig(enabled = true, trueRefraction = true)
        assertEquals(LiquidGlassPanelConfig.EDGE_WIDTH, config.edgeWidth)
        assertEquals(LiquidGlassPanelConfig.REFRACTION, config.refraction)
        assertEquals(LiquidGlassPanelConfig.HIGHLIGHT, config.highlight)
        assertEquals(LiquidGlassPanelConfig.SHADOW, config.shadow)
        assertEquals(LiquidGlassPanelConfig.LIGHT_DIRECTION, config.lightDirection)
        assertEquals(LiquidGlassPanelConfig.DISPERSION, config.dispersion)
        assertEquals(LiquidGlassPanelConfig.GYROSCOPE, config.gyroscope)
        assertEquals(LiquidGlassPanelConfig.CAPTURE_FPS, config.captureFps)
        assertEquals(LiquidGlassPanelConfig.CAPTURE_SCALE, config.captureScale)
        assertEquals(LiquidGlassPanelConfig.CAPTURE_BLUR_RADIUS, config.captureBlurRadius)
        assertEquals(LiquidGlassPanelConfig.BLEND_COLOR, config.blendColor)
    }
}
