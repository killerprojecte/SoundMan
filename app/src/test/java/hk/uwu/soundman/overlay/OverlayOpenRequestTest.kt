package hk.uwu.soundman.overlay

import android.content.Intent
import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayOpenRequestTest {
    @Test
    fun nullExtrasMeanNotFromSidebar() {
        assertFalse(OverlayOpenRequest.parseFromVolumeSidebar(null))
        assertFalse(OverlayOpenRequest.fromExtras(null).fromVolumeSidebar)
    }

    @Test
    fun missingExtraMeansNotFromSidebar() {
        assertFalse(OverlayOpenRequest.parseFromVolumeSidebar(emptyMap()))
        assertFalse(OverlayOpenRequest.parseFromVolumeSidebar(mapOf("other.extra" to true)))
        assertFalse(OverlayOpenRequest.fromExtras(emptyMap()).fromVolumeSidebar)
    }

    @Test
    fun extraTrueMeansFromSidebar() {
        assertTrue(
            OverlayOpenRequest.parseFromVolumeSidebar(
                mapOf(OverlayOpenRequest.EXTRA_FROM_VOLUME_SIDEBAR to true),
            ),
        )
        assertTrue(
            OverlayOpenRequest.fromExtras(
                mapOf(OverlayOpenRequest.EXTRA_FROM_VOLUME_SIDEBAR to true),
            ).fromVolumeSidebar,
        )
    }

    @Test
    fun extrasRoundTripTrueAndFalse() {
        val fromSidebar = OverlayOpenRequest(true)
        val fromDesktop = OverlayOpenRequest(false)

        assertTrue(OverlayOpenRequest.fromExtras(fromSidebar.extras()).fromVolumeSidebar)
        assertFalse(OverlayOpenRequest.fromExtras(fromDesktop.extras()).fromVolumeSidebar)
        assertEquals(true, OverlayOpenRequest.parseFromVolumeSidebar(fromSidebar.extras()))
        assertEquals(false, OverlayOpenRequest.parseFromVolumeSidebar(fromDesktop.extras()))
    }

    @Test
    fun seededPlaybackRoundTripsThroughExtras() {
        val seeds = listOf(SeededPlayback(10123, "com.example.music"))
        val request = OverlayOpenRequest(fromVolumeSidebar = true, seededPlayback = seeds)
        val extras = request.extras()

        assertEquals(seeds, OverlayOpenRequest.parseSeededPlayback(extras))
        assertEquals(seeds, OverlayOpenRequest.fromExtras(extras).seededPlayback)
        assertTrue(OverlayOpenRequest.fromExtras(extras).fromVolumeSidebar)
    }

    @Test
    fun seededPlaybackIsEmptyWithoutExtras() {
        assertTrue(OverlayOpenRequest.fromExtras(null).seededPlayback.isEmpty())
        assertTrue(OverlayOpenRequest.fromExtras(emptyMap()).seededPlayback.isEmpty())
        assertTrue(OverlayOpenRequest(fromVolumeSidebar = false).extras().keys.none { it.contains("SEED") })
    }

    @Test
    fun malformedSeedsAreDropped() {
        val uids = intArrayOf(10123, 10124)
        assertTrue(
            OverlayOpenRequest.parseSeededPlayback(
                mapOf(
                    OverlayOpenRequest.EXTRA_SEED_UIDS to uids,
                    OverlayOpenRequest.EXTRA_SEED_PACKAGES to arrayOf("com.example.music"),
                ),
            ).isEmpty()
        )
        assertTrue(
            OverlayOpenRequest.parseSeededPlayback(
                mapOf(
                    OverlayOpenRequest.EXTRA_SEED_UIDS to intArrayOf(-1),
                    OverlayOpenRequest.EXTRA_SEED_PACKAGES to arrayOf("com.example.music"),
                ),
            ).isEmpty()
        )
        assertTrue(
            OverlayOpenRequest.parseSeededPlayback(
                mapOf(
                    OverlayOpenRequest.EXTRA_SEED_UIDS to intArrayOf(10123),
                    OverlayOpenRequest.EXTRA_SEED_PACKAGES to arrayOf("  "),
                ),
            ).isEmpty()
        )
    }

    @Test
    fun sidebarLaunchCarriesSeeds() {
        val seeds = listOf(SeededPlayback(10123, "com.example.music"))
        val launch = OverlayOpenRequest.sidebarActivityLaunch(seeds)

        assertEquals(seeds, OverlayOpenRequest.fromExtras(launch.extras).seededPlayback)
    }

    @Test
    fun volumeSidebarDismissesWithBackDownThenUp() {
        assertEquals(
            listOf(
                DismissKeyStroke(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK),
                DismissKeyStroke(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK),
            ),
            OverlayOpenRequest.volumeSidebarDismissSequence(),
        )
        assertEquals(0, KeyEvent.ACTION_DOWN)
        assertEquals(1, KeyEvent.ACTION_UP)
        assertEquals(4, KeyEvent.KEYCODE_BACK)
    }

    @Test
    fun sidebarLaunchUsesTrampolineInsteadOfMainActivity() {
        val launch = OverlayOpenRequest.sidebarActivityLaunch()

        assertEquals("hk.uwu.soundman", launch.packageName)
        assertEquals("hk.uwu.soundman.overlay.OverlayLaunchActivity", launch.className)
        assertEquals(OverlayOpenRequest.ACTION_OPEN_OVERLAY, launch.action)
        assertEquals(OverlayOpenRequest(fromVolumeSidebar = true).extras(), launch.extras)
        assertFalse(launch.className.contains("MainActivity"))
    }

    @Test
    fun sidebarLaunchFlagsDoNotBringExistingHomeTaskForward() {
        val flags = OverlayOpenRequest.sidebarActivityFlags()

        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, flags and Intent.FLAG_ACTIVITY_NEW_TASK)
        assertEquals(
            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
            flags and Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
        )
        assertEquals(Intent.FLAG_ACTIVITY_NO_ANIMATION, flags and Intent.FLAG_ACTIVITY_NO_ANIMATION)
        assertEquals(
            Intent.FLAG_ACTIVITY_NO_USER_ACTION,
            flags and Intent.FLAG_ACTIVITY_NO_USER_ACTION
        )
        assertEquals(0, flags and Intent.FLAG_ACTIVITY_CLEAR_TASK)
        assertEquals(0, flags and Intent.FLAG_ACTIVITY_CLEAR_TOP)
        assertEquals(flags, OverlayOpenRequest.sidebarActivityLaunch().flags)
    }
}
