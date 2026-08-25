package hk.uwu.soundman.ui

import hk.uwu.soundman.ui.basic.CollapsibleScrollConsumption
import hk.uwu.soundman.ui.basic.TopBarBackdropMode
import hk.uwu.soundman.ui.basic.TopBarStyle
import hk.uwu.soundman.ui.basic.TopBarStyleResolver
import org.junit.Assert.assertEquals
import org.junit.Test

class TopBarStyleResolverTest {
    @Test
    fun glassStyleSamplesOnlyWhenBackdropAndRuntimeShaderAreAvailable() {
        val sampled = TopBarStyleResolver.resolve(
            style = TopBarStyle.LargeGlass,
            hasBackdrop = true,
            supportsBackdropSampling = true,
        )
        val fallbackWithoutSource = TopBarStyleResolver.resolve(
            style = TopBarStyle.LargeGlass,
            hasBackdrop = false,
            supportsBackdropSampling = true,
        )
        val fallbackWithoutCapability = TopBarStyleResolver.resolve(
            style = TopBarStyle.CompactGlass,
            hasBackdrop = true,
            supportsBackdropSampling = false,
        )

        assertEquals(TopBarBackdropMode.Sampled, sampled.backdropMode)
        assertEquals(TopBarBackdropMode.Fallback, fallbackWithoutSource.backdropMode)
        assertEquals(TopBarBackdropMode.Fallback, fallbackWithoutCapability.backdropMode)
    }

    @Test
    fun transparentAndSolidStylesNeverAttemptBackdropSampling() {
        val transparent = TopBarStyleResolver.resolve(
            style = TopBarStyle.Transparent,
            hasBackdrop = true,
            supportsBackdropSampling = true,
        )
        val solid = TopBarStyleResolver.resolve(
            style = TopBarStyle.Solid,
            hasBackdrop = true,
            supportsBackdropSampling = true,
        )

        assertEquals(TopBarBackdropMode.None, transparent.backdropMode)
        assertEquals(0f, transparent.tintAlpha)
        assertEquals(TopBarBackdropMode.None, solid.backdropMode)
        assertEquals(1f, solid.tintAlpha)
    }

    @Test
    fun scrollConsumptionReturnsOnlyTheHeightChangeWithinBounds() {
        assertEquals(
            -24f,
            CollapsibleScrollConsumption.consumeHeightOffset(
                currentOffset = -16f,
                offsetLimit = -40f,
                availableOffset = -80f,
            ),
        )
        assertEquals(
            16f,
            CollapsibleScrollConsumption.consumeHeightOffset(
                currentOffset = -16f,
                offsetLimit = -40f,
                availableOffset = 80f,
            ),
        )
        assertEquals(
            0f,
            CollapsibleScrollConsumption.consumeHeightOffset(
                currentOffset = -40f,
                offsetLimit = -40f,
                availableOffset = -8f,
            ),
        )
    }
}
