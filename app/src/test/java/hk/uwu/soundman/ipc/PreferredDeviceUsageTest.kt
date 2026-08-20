package hk.uwu.soundman.ipc

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferredDeviceUsageTest {

    @After
    fun resetAlarmFirst() {
        PreferredDeviceUsage.alarmFirst = false
    }
    @Test
    fun followSystemStaysMedia() {
        val usages = PreferredDeviceUsage.allocate(
            listOf(PreferredDeviceSync.followSystem(10)),
        )
        assertEquals(PreferredDeviceUsage.USAGE_MEDIA, usages[10])
        assertFalse(PreferredDeviceUsage.shouldRewrite(usages.getValue(10)))
    }

    @Test
    fun singleOccupiedDeviceDoesNotDisguise() {
        val speakerA = PreferredDeviceSync.forced(1, 2, "")
        val speakerB = PreferredDeviceSync.forced(2, 2, "")
        val usages = PreferredDeviceUsage.allocate(listOf(speakerA, speakerB))
        assertEquals(PreferredDeviceUsage.USAGE_MEDIA, usages[1])
        assertEquals(PreferredDeviceUsage.USAGE_MEDIA, usages[2])
        assertFalse(PreferredDeviceUsage.shouldRewrite(usages.getValue(1)))
    }

    @Test
    fun onlyBluetoothStaysMedia() {
        val usages = PreferredDeviceUsage.allocate(
            listOf(PreferredDeviceSync.forced(2, 8, "AA:BB")),
        )
        assertEquals(PreferredDeviceUsage.USAGE_MEDIA, usages[2])
    }

    @Test
    fun additionalDeviceIsDisguisedAsRingtone() {
        val speaker = PreferredDeviceSync.forced(1, 2, "")
        val bt = PreferredDeviceSync.forced(2, 8, "AA:BB")
        val usages = PreferredDeviceUsage.allocate(listOf(speaker, bt))
        assertEquals(PreferredDeviceUsage.USAGE_MEDIA, usages[2])
        assertEquals(PreferredDeviceUsage.USAGE_NOTIFICATION_RINGTONE, usages[1])
        assertTrue(PreferredDeviceUsage.shouldRewrite(usages.getValue(1)))
        assertFalse(PreferredDeviceUsage.shouldRewrite(usages.getValue(2)))
    }

    @Test
    fun thirdDeviceIsDisguisedAsAlarm() {
        val hints = listOf(
            PreferredDeviceSync.forced(1, 2, ""),
            PreferredDeviceSync.forced(2, 8, "A"),
            PreferredDeviceSync.forced(3, 11, "usb"),
        )
        val usages = PreferredDeviceUsage.allocate(hints)
        assertEquals(PreferredDeviceUsage.USAGE_MEDIA, usages[2])
        assertEquals(PreferredDeviceUsage.USAGE_NOTIFICATION_RINGTONE, usages[3])
        assertEquals(PreferredDeviceUsage.USAGE_ALARM, usages[1])
    }

    @Test
    fun mixedFollowSystemDoesNotCreateDisguise() {
        val follow = PreferredDeviceSync.followSystem(1)
        val speaker = PreferredDeviceSync.forced(2, 2, "")
        val usages = PreferredDeviceUsage.allocate(listOf(follow, speaker))
        assertEquals(PreferredDeviceUsage.USAGE_MEDIA, usages[1])
        assertEquals(PreferredDeviceUsage.USAGE_MEDIA, usages[2])
    }

    @Test
    fun followSystemOnSpeakerWithForcedBluetoothTriggersDisguise() {
        val follow = PreferredDeviceSync.followSystem(1)
        val bt = PreferredDeviceSync.forced(2, 8, "AA:BB")
        val systemDevice = PreferredDeviceSync.DeviceSpec(2, "")
        val usages = PreferredDeviceUsage.allocate(listOf(follow, bt), systemDevice)
        assertEquals(PreferredDeviceUsage.USAGE_MEDIA, usages[1])
        assertEquals(PreferredDeviceUsage.USAGE_NOTIFICATION_RINGTONE, usages[2])
        assertTrue(PreferredDeviceUsage.shouldRewrite(usages.getValue(2)))
        assertFalse(PreferredDeviceUsage.shouldRewrite(usages.getValue(1)))
    }

    @Test
    fun followSystemOnBluetoothWithForcedSpeakerTriggersDisguise() {
        val follow = PreferredDeviceSync.followSystem(1)
        val speaker = PreferredDeviceSync.forced(2, 2, "")
        val systemDevice = PreferredDeviceSync.DeviceSpec(8, "AA:BB")
        val usages = PreferredDeviceUsage.allocate(listOf(follow, speaker), systemDevice)
        assertEquals(PreferredDeviceUsage.USAGE_MEDIA, usages[1])
        assertEquals(PreferredDeviceUsage.USAGE_NOTIFICATION_RINGTONE, usages[2])
        assertTrue(PreferredDeviceUsage.shouldRewrite(usages.getValue(2)))
    }

    @Test
    fun followSystemSameDeviceAsForcedNoDisguise() {
        val follow = PreferredDeviceSync.followSystem(1)
        val speaker = PreferredDeviceSync.forced(2, 2, "")
        val systemDevice = PreferredDeviceSync.DeviceSpec(2, "")
        val usages = PreferredDeviceUsage.allocate(listOf(follow, speaker), systemDevice)
        assertEquals(PreferredDeviceUsage.USAGE_MEDIA, usages[1])
        assertEquals(PreferredDeviceUsage.USAGE_MEDIA, usages[2])
        assertFalse(PreferredDeviceUsage.shouldRewrite(usages.getValue(1)))
        assertFalse(PreferredDeviceUsage.shouldRewrite(usages.getValue(2)))
    }

    @Test
    fun followSystemWithNullSystemDeviceNoDisguise() {
        val follow = PreferredDeviceSync.followSystem(1)
        val bt = PreferredDeviceSync.forced(2, 8, "AA:BB")
        val usages = PreferredDeviceUsage.allocate(listOf(follow, bt), systemDevice = null)
        assertEquals(PreferredDeviceUsage.USAGE_MEDIA, usages[1])
        assertEquals(PreferredDeviceUsage.USAGE_MEDIA, usages[2])
    }

    @Test
    fun systemDeviceKeepsMediaForcedGetsDisguised() {
        val follow = PreferredDeviceSync.followSystem(1)
        val forcedBt = PreferredDeviceSync.forced(2, 8, "AA:BB")
        val forcedSpeaker = PreferredDeviceSync.forced(3, 2, "")
        val systemDevice = PreferredDeviceSync.DeviceSpec(2, "")
        val usages =
            PreferredDeviceUsage.allocate(listOf(follow, forcedBt, forcedSpeaker), systemDevice)
        // System device (speaker) stays MEDIA; forced speaker shares the same deviceKey so also MEDIA.
        // Bluetooth is the different device, gets disguised as RINGTONE.
        assertEquals(PreferredDeviceUsage.USAGE_MEDIA, usages[1])
        assertEquals(PreferredDeviceUsage.USAGE_NOTIFICATION_RINGTONE, usages[2])
        assertEquals(PreferredDeviceUsage.USAGE_MEDIA, usages[3])
    }

    @Test
    fun withAllocatedUsagesWritesBack() {
        val allocated = PreferredDeviceUsage.withAllocatedUsages(
            listOf(
                PreferredDeviceSync.forced(8, 2, ""),
                PreferredDeviceSync.forced(3, 8, "AA"),
            ),
        )
        assertEquals(
            PreferredDeviceUsage.USAGE_NOTIFICATION_RINGTONE,
            allocated.single { it.uid == 8 }.usage
        )
        assertEquals(PreferredDeviceUsage.USAGE_MEDIA, allocated.single { it.uid == 3 }.usage)
    }

    @Test
    fun streamTypeMatchesDisguise() {
        assertEquals(
            PreferredDeviceUsage.STREAM_MUSIC,
            PreferredDeviceUsage.streamType(PreferredDeviceUsage.USAGE_MEDIA)
        )
        assertEquals(
            PreferredDeviceUsage.STREAM_RING,
            PreferredDeviceUsage.streamType(PreferredDeviceUsage.USAGE_NOTIFICATION_RINGTONE)
        )
        assertEquals(
            PreferredDeviceUsage.STREAM_ALARM,
            PreferredDeviceUsage.streamType(PreferredDeviceUsage.USAGE_ALARM)
        )
    }

    @Test
    fun fourthDeviceIsRejected() {
        val hints = listOf(
            PreferredDeviceSync.forced(1, 2, ""),
            PreferredDeviceSync.forced(2, 8, "A"),
            PreferredDeviceSync.forced(3, 11, "usb"),
            PreferredDeviceSync.forced(4, 3, "wired"),
        )
        val error = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            PreferredDeviceUsage.allocate(hints)
        }
        assertTrue(error.message.orEmpty().contains("too many distinct occupied devices"))
    }

    @Test
    fun nameMapsKnownUsages() {
        assertEquals("MEDIA", PreferredDeviceUsage.name(PreferredDeviceUsage.USAGE_MEDIA))
        assertEquals("ALARM", PreferredDeviceUsage.name(PreferredDeviceUsage.USAGE_ALARM))
        assertEquals(
            "RINGTONE",
            PreferredDeviceUsage.name(PreferredDeviceUsage.USAGE_NOTIFICATION_RINGTONE)
        )
        assertEquals("99", PreferredDeviceUsage.name(99))
    }

    @Test
    fun describeIncludesUidAndUsage() {
        val described = PreferredDeviceUsage.describe(
            listOf(
                PreferredDeviceSync.forced(8, 2, "")
                    .copy(usage = PreferredDeviceUsage.USAGE_NOTIFICATION_RINGTONE),
            ),
        )
        assertTrue(described.contains("uid=8"))
        assertTrue(described.contains("usage=RINGTONE"))
    }

    @Test
    fun alarmFirstSecondDeviceIsAlarm() {
        PreferredDeviceUsage.alarmFirst = true
        val speaker = PreferredDeviceSync.forced(1, 2, "")
        val bt = PreferredDeviceSync.forced(2, 8, "AA:BB")
        val usages = PreferredDeviceUsage.allocate(listOf(speaker, bt))
        assertEquals(PreferredDeviceUsage.USAGE_MEDIA, usages[2])
        assertEquals(PreferredDeviceUsage.USAGE_ALARM, usages[1])
        assertTrue(PreferredDeviceUsage.shouldRewrite(usages.getValue(1)))
    }

    @Test
    fun alarmFirstThirdDeviceIsRingtone() {
        PreferredDeviceUsage.alarmFirst = true
        val hints = listOf(
            PreferredDeviceSync.forced(1, 2, ""),
            PreferredDeviceSync.forced(2, 8, "A"),
            PreferredDeviceSync.forced(3, 11, "usb"),
        )
        val usages = PreferredDeviceUsage.allocate(hints)
        assertEquals(PreferredDeviceUsage.USAGE_MEDIA, usages[2])
        assertEquals(PreferredDeviceUsage.USAGE_ALARM, usages[3])
        assertEquals(PreferredDeviceUsage.USAGE_NOTIFICATION_RINGTONE, usages[1])
    }

    @Test
    fun alarmFirstFollowSystemWithForcedBluetoothUsesAlarm() {
        PreferredDeviceUsage.alarmFirst = true
        val follow = PreferredDeviceSync.followSystem(1)
        val bt = PreferredDeviceSync.forced(2, 8, "AA:BB")
        val systemDevice = PreferredDeviceSync.DeviceSpec(2, "")
        val usages = PreferredDeviceUsage.allocate(listOf(follow, bt), systemDevice)
        assertEquals(PreferredDeviceUsage.USAGE_MEDIA, usages[1])
        assertEquals(PreferredDeviceUsage.USAGE_ALARM, usages[2])
        assertTrue(PreferredDeviceUsage.shouldRewrite(usages.getValue(2)))
    }
}
