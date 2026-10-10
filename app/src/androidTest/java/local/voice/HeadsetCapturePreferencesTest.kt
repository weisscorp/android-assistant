package local.voice

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HeadsetCapturePreferencesTest {
    @Test fun calibrationSurvivesReloadIsScopedToHeadsetAndFailedProbeClearsIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("audio_routes", 0)
        val before = prefs.all.mapValues { it.value as Int }
        try {
            val first = "7:test-helmet-one"
            val other = "7:test-helmet-two"
            assertEquals(1, HeadsetCapturePreferences.save(context, first, listOf(
                CaptureSourcePolicy.Measurement(7, 112000, 0, 0, false),
                CaptureSourcePolicy.Measurement(1, 112000, 80000, 400, false))))
            assertEquals(1, HeadsetCapturePreferences.source(context, first))
            assertNull(HeadsetCapturePreferences.source(context, other))
            assertFalse("Headset identity leaked into preference keys", prefs.all.keys.any { it.contains("test-helmet") })
            assertNull(HeadsetCapturePreferences.save(context, first, listOf(
                CaptureSourcePolicy.Measurement(1, 112000, 0, 0, false))))
            assertNull(HeadsetCapturePreferences.source(context, first))
        } finally {
            val edit = prefs.edit().clear()
            before.forEach { (key, value) -> edit.putInt(key, value) }
            edit.commit()
        }
    }
}
