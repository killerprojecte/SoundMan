package hk.uwu.soundman.ipc

import android.os.Binder
import hk.uwu.soundman.model.AppAudioRule
import hk.uwu.soundman.model.AudioDeviceIdentity
import hk.uwu.soundman.model.AudioOutputDevice
import hk.uwu.soundman.model.OutputDeviceType
import hk.uwu.soundman.model.OutputTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundManProtocolTest {
    @Test
    fun requestBinderIntentExtrasContainVersionAndOfferBinder() {
        val offer = Binder()
        val extras = SoundManProtocol.requestBinderExtras(offer)

        assertEquals(SoundManProtocol.VERSION, extras[SoundManProtocol.EXTRA_PROTOCOL_VERSION])
        assertSame(offer, extras[SoundManProtocol.EXTRA_HOST_OFFER])
        assertEquals(
            setOf(SoundManProtocol.EXTRA_PROTOCOL_VERSION, SoundManProtocol.EXTRA_HOST_OFFER),
            extras.keys,
        )
        assertEquals(4, SoundManProtocol.VERSION)
    }

    @Test
    fun requestBinderIntentExtrasDoNotContainLegacyBootstrapKeys() {
        val extras = SoundManProtocol.requestBinderExtras(Binder())

        assertFalse(extras.containsKey("bootstrapPayload"))
        assertFalse(extras.containsKey("bootstrapBinder"))
        assertFalse(extras.keys.any { it.contains("bootstrap", ignoreCase = true) })
    }

    @Test
    fun encodeDecodeBuiltInDevicePreservesEmptyAddress() {
        val device = AudioOutputDevice(
            type = OutputDeviceType.BUILT_IN,
            candidates = listOf(AudioDeviceIdentity(SPEAKER_INTERNAL_TYPE, "")),
            productName = "Speaker",
        )

        val encoded = SoundManProtocol.candidateArraysFromIdentities(device.candidates)
        val decodedCandidates = SoundManProtocol.identitiesFromCandidateArrays(
            encoded.types,
            encoded.addresses,
        )
        val decoded = AudioOutputDevice(device.type, decodedCandidates, device.productName)

        assertEquals("", decoded.identity.address)
        assertEquals(OutputDeviceType.BUILT_IN, decoded.type)
        assertEquals(SPEAKER_INTERNAL_TYPE, decoded.identity.internalType)
        assertEquals("Speaker", decoded.productName)
    }

    @Test
    fun encodeDecodeSnapshotAllowsEmptyCandidateAddress() {
        val snapshot = SoundManProtocol.Snapshot(
            revision = 85L,
            playback = listOf(SoundManProtocol.PlaybackEntry(10123, "com.spotify.music", 1)),
            outputDevices = listOf(
                AudioOutputDevice(
                    type = OutputDeviceType.BUILT_IN,
                    candidates = listOf(AudioDeviceIdentity(SPEAKER_INTERNAL_TYPE, "")),
                    productName = "Speaker",
                ),
            ),
        )

        val encoded = SoundManProtocol.candidateArraysFromIdentities(snapshot.outputDevices.single().candidates)
        val decodedCandidates = SoundManProtocol.identitiesFromCandidateArrays(
            encoded.types,
            encoded.addresses,
        )

        assertEquals(1, snapshot.playback.size)
        assertEquals("com.spotify.music", snapshot.playback.single().packageName)
        assertEquals("", decodedCandidates.single().address)
        assertEquals(SPEAKER_INTERNAL_TYPE, decodedCandidates.single().internalType)
    }

    @Test
    fun playbackPackageStillRejectsBlank() {
        val blankPackage = assertThrows(IllegalArgumentException::class.java) {
            SoundManProtocol.PlaybackEntry(10123, "", 1)
        }
        assertTrue(blankPackage.message.orEmpty().contains("packageName"))

        val whitespacePackage = assertThrows(IllegalArgumentException::class.java) {
            SoundManProtocol.PlaybackEntry(10123, "  ", 1)
        }
        assertTrue(whitespacePackage.message.orEmpty().contains("packageName"))

        val blankRulePackage = assertThrows(IllegalArgumentException::class.java) {
            AppAudioRule(
                packageName = "",
                uid = 10123,
                volumePercent = 100,
                outputTarget = OutputTarget.FollowSystem,
                revision = 1L,
            )
        }
        assertTrue(blankRulePackage.message.orEmpty().contains("packageName"))

        val emptyAddress = AudioDeviceIdentity(SPEAKER_INTERNAL_TYPE, "")
        val encoded = SoundManProtocol.candidateArraysFromIdentities(listOf(emptyAddress))
        val decoded = SoundManProtocol.identitiesFromCandidateArrays(encoded.types, encoded.addresses)
        assertEquals("", decoded.single().address)
    }

    @Test
    fun identitiesFromCandidateArraysRejectsNullArray() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            SoundManProtocol.identitiesFromCandidateArrays(intArrayOf(SPEAKER_INTERNAL_TYPE), null)
        }
        assertTrue(error.message.orEmpty().contains("candidateAddresses"))
    }

    @Test
    fun identitiesFromCandidateArraysRejectsNullElement() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            SoundManProtocol.identitiesFromCandidateArrays(
                intArrayOf(SPEAKER_INTERNAL_TYPE),
                arrayOf<String?>(null),
            )
        }
        assertTrue(error.message.orEmpty().contains("candidateAddresses"))
    }

    @Test
    fun identitiesFromCandidateArraysRejectsLengthMismatch() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            SoundManProtocol.identitiesFromCandidateArrays(
                intArrayOf(SPEAKER_INTERNAL_TYPE, EARPIECE_INTERNAL_TYPE),
                arrayOf(""),
            )
        }
        assertTrue(error.message.orEmpty().contains("length"))
    }

    @Test
    fun panelDeviceAndTargetIdentitiesUseTheSharedProtocolShape() {
        val device = AudioOutputDevice(
            type = OutputDeviceType.USB,
            candidates = listOf(AudioDeviceIdentity(0x4000, "card=2;device=0")),
            productName = "USB DAC",
        )

        assertEquals(
            "USB:USB DAC:16384@card=2;device=0",
            SoundManProtocol.encodeDeviceIdentity(device),
        )
        assertEquals(
            SoundManProtocol.encodeDeviceIdentity(device),
            SoundManProtocol.encodeTargetIdentity(device.target),
        )
        assertEquals(
            "follow-system",
            SoundManProtocol.encodeTargetIdentity(OutputTarget.FollowSystem)
        )
    }

    companion object {
        private const val SPEAKER_INTERNAL_TYPE = 0x2
        private const val EARPIECE_INTERNAL_TYPE = 0x1
    }
}
