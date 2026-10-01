package hk.uwu.soundman.hook.scopes.systemui.runtime

import android.view.View
import hk.uwu.soundman.model.MediaPresence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntryPresencePolicyTest {
    @Test
    fun showsOnlyWhenCollapsedAndPlaying() {
        assertEquals(View.VISIBLE, EntryPresencePolicy.visibility(false, MediaPresence.PLAYING))
        assertTrue(EntryPresencePolicy.isVisible(false, MediaPresence.PLAYING))
    }

    @Test
    fun hidesWhenNoMediaIsPlaying() {
        assertEquals(View.GONE, EntryPresencePolicy.visibility(false, MediaPresence.IDLE))
        assertFalse(EntryPresencePolicy.isVisible(false, MediaPresence.IDLE))
    }

    @Test
    fun expandedAlwaysHidesEvenWhilePlaying() {
        assertEquals(View.GONE, EntryPresencePolicy.visibility(true, MediaPresence.PLAYING))
        assertEquals(View.GONE, EntryPresencePolicy.visibility(true, MediaPresence.IDLE))
        assertEquals(View.GONE, EntryPresencePolicy.visibility(true, MediaPresence.UNKNOWN))
    }

    @Test
    fun unknownPresenceKeepsEntryVisible() {
        // 探测失败不能把入口藏掉：少显示一颗按钮只是少个入口，永远不显示等于模块失灵。
        assertEquals(View.VISIBLE, EntryPresencePolicy.visibility(false, MediaPresence.UNKNOWN))
    }

    @Test
    fun presenceFoldsProbeResult() {
        assertEquals(MediaPresence.PLAYING, MediaPresence.from(listOf("com.example")))
        assertEquals(MediaPresence.IDLE, MediaPresence.from(emptyList<Any>()))
        assertEquals(MediaPresence.UNKNOWN, MediaPresence.from(null))
    }
}
