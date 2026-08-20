package hk.uwu.soundman.hook.scopes.system.hidden

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SystemMediaDeviceProbeTest {
    private val probe = SystemMediaDeviceProbe { 0 }

    @Test
    fun pickHighestPriority_bluetoothA2dpOverSpeaker() {
        // bitmask: DEVICE_OUT_BLUETOOTH_A2DP (0x80) | DEVICE_OUT_SPEAKER (0x2)
        val bitmask = 0x80 or 0x2
        val type = probe.pickHighestPriorityInternalType(bitmask)!!
        assertEquals(0x80, type)
    }

    @Test
    fun pickHighestPriority_bleSpeakerOverWired() {
        // bitmask: DEVICE_OUT_BLE_SPEAKER (0x400) | DEVICE_OUT_WIRED_HEADSET (0x10)
        val bitmask = 0x400 or 0x10
        val type = probe.pickHighestPriorityInternalType(bitmask)!!
        assertEquals(0x400, type)
    }

    @Test
    fun pickHighestPriority_wiredOverSpeaker() {
        // bitmask: DEVICE_OUT_WIRED_HEADPHONE (0x20) | DEVICE_OUT_SPEAKER (0x2)
        val bitmask = 0x20 or 0x2
        val type = probe.pickHighestPriorityInternalType(bitmask)!!
        assertEquals(0x20, type)
    }

    @Test
    fun pickHighestPriority_speakerOnly() {
        // bitmask: DEVICE_OUT_SPEAKER (0x2)
        val bitmask = 0x2
        val type = probe.pickHighestPriorityInternalType(bitmask)!!
        assertEquals(0x2, type)
    }

    @Test
    fun pickHighestPriority_earpieceOnly() {
        // bitmask: DEVICE_OUT_EARPIECE (0x1)
        val bitmask = 0x1
        val type = probe.pickHighestPriorityInternalType(bitmask)!!
        assertEquals(0x1, type)
    }

    @Test
    fun pickHighestPriority_emptyBitmaskReturnsNull() {
        assertNull(probe.pickHighestPriorityInternalType(0))
    }

    @Test
    fun pickHighestPriority_unknownBitsReturnNull() {
        // Only bits not in the priority table (e.g. 0x8000)
        assertNull(probe.pickHighestPriorityInternalType(0x8000))
    }

    @Test
    fun pickHighestPriority_bluetoothA2dpOverBle() {
        // bitmask: DEVICE_OUT_BLUETOOTH_A2DP (0x80) | DEVICE_OUT_BLE_HEADSET (0x200)
        val bitmask = 0x80 or 0x200
        val type = probe.pickHighestPriorityInternalType(bitmask)!!
        assertEquals(0x80, type)
    }

    @Test
    fun deviceTypeBit_knownTypes() {
        assertEquals(
            0x2,
            SystemMediaDeviceProbe.deviceTypeBit(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        )
        assertEquals(
            0x80,
            SystemMediaDeviceProbe.deviceTypeBit(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        )
        assertEquals(0x10, SystemMediaDeviceProbe.deviceTypeBit(AudioDeviceInfo.TYPE_WIRED_HEADSET))
        assertEquals(
            0x20,
            SystemMediaDeviceProbe.deviceTypeBit(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
        )
    }

    @Test
    fun deviceTypeBit_unknownTypeReturnsZero() {
        assertEquals(0, SystemMediaDeviceProbe.deviceTypeBit(9999))
    }
}
