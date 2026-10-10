package local.voice

import org.junit.Assert.*
import org.junit.Test

class HeadsetSelectionTest {
    @Test fun automaticSelectionUsesCurrentAudioDevice() {
        val devices = listOf(HeadsetSelection.Device(1, "usb", false), HeadsetSelection.Device(2, "helmet", true), HeadsetSelection.Device(3, "buds", true))
        assertEquals(3, HeadsetSelection.choose(devices, current = 3)!!.id)
        assertEquals(1, HeadsetSelection.choose(devices, current = 1)!!.id)
        assertEquals(1, HeadsetSelection.choose(devices, saved = "usb")!!.id)
    }
    @Test fun savedHelmetNeverFallsBackToAnotherDeviceOrAnAmbiguousIdentity() {
        val other = HeadsetSelection.Device(1, "buds", true)
        assertNull(HeadsetSelection.choose(listOf(other), saved = "helmet"))
        assertNull(HeadsetSelection.choose(listOf(other, other.copy(id = 2)), saved = "buds"))
        assertNull(HeadsetSelection.choose(emptyList()))
    }
    @Test fun unavailableSavedHeadsetFallsBackToCurrentDeviceInAutomaticMode() {
        val devices = listOf(HeadsetSelection.Device(1, "usb", false), HeadsetSelection.Device(2, "buds", true))
        assertEquals(1, HeadsetSelection.choose(devices, saved = "helmet", current = 1, allowFallback = true)!!.id)
        assertNull(HeadsetSelection.choose(emptyList(), saved = "helmet", allowFallback = true))
    }

}
