package hk.uwu.soundman.ui.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AboutTopBarPolicyTest {
    @Test
    fun smallTitleUsesStrictlyGreaterThanHalfProgressThreshold() {
        assertFalse(AboutTopBarPolicy.showSmallTitle(0.5f))
        assertTrue(AboutTopBarPolicy.showSmallTitle(0.5001f))
    }

    @Test
    fun scrollProgressClampsAtTheConfiguredFadeDistance() {
        assertEquals(0.5f, AboutTopBarPolicy.scrollProgress(0, 194, 388f))
        assertEquals(1f, AboutTopBarPolicy.scrollProgress(0, 500, 389f))
        assertEquals(1f, AboutTopBarPolicy.scrollProgress(1, 0, 389f))
    }
}
