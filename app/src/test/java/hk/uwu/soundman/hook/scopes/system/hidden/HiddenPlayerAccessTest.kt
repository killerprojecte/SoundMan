package hk.uwu.soundman.hook.scopes.system.hidden

import hk.uwu.soundman.hook.scopes.system.hidden.fakes.FakePlayer
import hk.uwu.soundman.hook.scopes.system.hidden.fakes.FakePlayerIdCard
import hk.uwu.soundman.hook.scopes.system.hidden.fakes.FakePlayerIdCardWithoutField
import hk.uwu.soundman.hook.scopes.system.hidden.fakes.FakePlayerWithoutSetVolume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenPlayerAccessTest {
    @Test
    fun setVolumeForwardsToWrappedPlayer() {
        val fakePlayer = FakePlayer()
        val access = HiddenPlayerAccess(FakePlayerIdCard::class.java)
        val player = access.fromPlayerIdCard(FakePlayerIdCard(fakePlayer))
            ?: error("expected HiddenPlayer")
        player.setVolume(0.42f)
        assertEquals(0.42f, fakePlayer.lastVolume, 0f)
        assertTrue(player.restartForReroute())
        assertEquals(1, fakePlayer.pauseCount)
        assertEquals(1, fakePlayer.startCount)
    }

    @Test
    fun returnsNullWhenIPlayerFieldIsNull() {
        val access = HiddenPlayerAccess(FakePlayerIdCard::class.java)
        assertNull(access.fromPlayerIdCard(FakePlayerIdCard(null)))
    }

    @Test
    fun failsWhenIPlayerFieldIsMissing() {
        val constructError = assertThrows(IllegalStateException::class.java) {
            HiddenPlayerAccess(FakePlayerIdCardWithoutField::class.java)
        }
        assertTrue(constructError.message.orEmpty().contains("mIPlayer"))
        assertTrue(constructError.message.orEmpty().contains(FakePlayerIdCardWithoutField::class.java.name))

        val access = HiddenPlayerAccess(FakePlayerIdCard::class.java)
        val lookupError = assertThrows(IllegalStateException::class.java) {
            access.fromPlayerIdCard(FakePlayerIdCardWithoutField())
        }
        assertTrue(lookupError.message.orEmpty().contains("mIPlayer"))
        assertTrue(lookupError.message.orEmpty().contains(FakePlayerIdCardWithoutField::class.java.name))
    }

    @Test
    fun failsWhenSetVolumeIsMissing() {
        val access = HiddenPlayerAccess(FakePlayerIdCard::class.java)
        val error = assertThrows(IllegalStateException::class.java) {
            access.fromPlayerIdCard(FakePlayerIdCard(FakePlayerWithoutSetVolume()))
        }
        assertTrue(error.message.orEmpty().contains("setVolume"))
        assertTrue(error.message.orEmpty().contains(FakePlayerWithoutSetVolume::class.java.name))
    }

    @Test
    fun unwrapsInvocationTargetExceptionFromSetVolume() {
        val fakePlayer = FakePlayer()
        val original = IllegalStateException("volume boom")
        fakePlayer.throwOnSetVolume = original
        val access = HiddenPlayerAccess(FakePlayerIdCard::class.java)
        val player = access.fromPlayerIdCard(FakePlayerIdCard(fakePlayer))
            ?: error("expected HiddenPlayer")
        assertSame(original, assertThrows(IllegalStateException::class.java) {
            player.setVolume(0.1f)
        })
    }

    @Test
    fun setVolumeThrowsPlayerDeadExceptionWhenBinderIsDead() {
        val fakePlayer = FakePlayer()
        fakePlayer.throwDeadObjectOnSetVolume = true
        val access = HiddenPlayerAccess(FakePlayerIdCard::class.java)
        val player = access.fromPlayerIdCard(FakePlayerIdCard(fakePlayer))
            ?: error("expected HiddenPlayer")
        assertThrows(PlayerDeadException::class.java) {
            player.setVolume(0.5f)
        }
    }

    @Test
    fun restartForRerouteThrowsPlayerDeadExceptionWhenBinderIsDead() {
        val fakePlayer = FakePlayer()
        fakePlayer.throwDeadObjectOnPause = true
        val access = HiddenPlayerAccess(FakePlayerIdCard::class.java)
        val player = access.fromPlayerIdCard(FakePlayerIdCard(fakePlayer))
            ?: error("expected HiddenPlayer")
        assertThrows(PlayerDeadException::class.java) {
            player.restartForReroute()
        }
    }

    @Test
    fun isAliveReturnsTrueForFakePlayerWithoutBinder() {
        val fakePlayer = FakePlayer()
        val access = HiddenPlayerAccess(FakePlayerIdCard::class.java)
        val player = access.fromPlayerIdCard(FakePlayerIdCard(fakePlayer))
            ?: error("expected HiddenPlayer")
        assertTrue(player.isAlive())
        assertNull(player.binder)
    }
}
