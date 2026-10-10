package local.voice

import org.junit.Assert.*
import org.junit.Test

class CaptureSourcePolicyTest {
    @Test fun workingCommunicationOrMicCanReplaceAnEmptyRecognitionStream() {
        val measurements = listOf(
            CaptureSourcePolicy.Measurement(6, 112000, 0, 0, false),
            CaptureSourcePolicy.Measurement(1, 112000, 90000, 400, false),
            CaptureSourcePolicy.Measurement(7, 112000, 85000, 350, false))
        assertEquals(7, CaptureSourcePolicy.recommend(measurements))
        assertEquals(1, CaptureSourcePolicy.recommend(measurements.filter { it.source != 7 }))
        assertEquals(1, CaptureSourcePolicy.select(phone = false, bluetooth = true, calibrated = 1))
    }
    @Test fun zeroStreamsSilencingAndRoutingClicksNeverQualifyAsCalibration() {
        assertNull(CaptureSourcePolicy.recommend(listOf(
            CaptureSourcePolicy.Measurement(7, 112000, 0, 0, false),
            CaptureSourcePolicy.Measurement(1, 112000, 100000, 1200, true),
            CaptureSourcePolicy.Measurement(6, 112000, 300, 1100, false))))
    }
    @Test fun unsupportedOrShortMeasurementsCannotSelectAnAudioSource() {
        assertNull(CaptureSourcePolicy.recommend(listOf(
            CaptureSourcePolicy.Measurement(4, 112000, 100000, 700, false),
            CaptureSourcePolicy.Measurement(6, 160, 160, 700, false),
            CaptureSourcePolicy.Measurement(1, 112000, 120000, 700, false))))
    }
    @Test fun calibrationNeverChangesPhoneOrNonBluetoothCapture() {
        assertEquals(1, CaptureSourcePolicy.select(phone = true, bluetooth = true, calibrated = 6))
        assertEquals(7, CaptureSourcePolicy.select(phone = false, bluetooth = false, calibrated = 6))
        assertEquals(7, CaptureSourcePolicy.select(phone = false, bluetooth = true, calibrated = 4))
        assertEquals(7, CaptureSourcePolicy.select(phone = false, bluetooth = true, calibrated = null))
    }
    @Test fun emptyTransportIsDetectedBeforeTheFifteenSecondRecognitionTimeout() {
        val watch = ZeroInputWatchdog(100)
        assertFalse(watch.failed(1000, ShortArray(160), false))
        assertFalse(watch.failed(4099, ShortArray(160), false))
        assertTrue(watch.failed(4100, ShortArray(160), false))
    }
    @Test fun quietSignalAndNormalPausesAreNeverAnEmptyTransport() {
        val watch = ZeroInputWatchdog(100)
        assertFalse(watch.failed(200, shortArrayOf(0, 1, 0), false))
        assertFalse(watch.failed(20000, ShortArray(160), false))
    }
    @Test fun androidSilencingIsNotDiagnosedAsAnEmptyBluetoothTransport() {
        assertFalse(ZeroInputWatchdog(0).failed(10000, ShortArray(160), true))
    }
}
