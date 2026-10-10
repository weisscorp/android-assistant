package local.voice

import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ScreenReaderTest {
    @Test fun readsDrawnRussianAndEnglishWithoutAccessibilityTextAndRejectsChangedApp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val old = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val oldEnabled = Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        fun setting(name: String, value: String?) {
            automation.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_SECURE_SETTINGS)
            try { assertTrue(Settings.Secure.putString(context.contentResolver, name, value)) }
            finally { automation.dropShellPermissionIdentity() }
        }
        try {
            setting(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "local.voice/local.voice.PhoneAccessibility")
            setting(Settings.Secure.ACCESSIBILITY_ENABLED, "1")
            context.startActivity(Intent().setComponent(ComponentName("local.voice.fixture", "local.voice.fixture.CanvasScreenActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            var frame: PhoneAccessibility.ForegroundSnapshot? = null
            val until = System.currentTimeMillis() + 5000
            while (frame?.app != "local.voice.fixture" && System.currentTimeMillis() < until) {
                instrumentation.runOnMainSync { frame = PhoneAccessibility.connected?.foregroundSnapshot() }
                Thread.sleep(100)
            }
            assertEquals("local.voice.fixture", frame?.app)
            Thread.sleep(700)
            val read = CountDownLatch(1)
            var result: AppScreenReader.Observation? = null
            var error: String? = null
            instrumentation.runOnMainSync {
                frame = PhoneAccessibility.connected!!.foregroundSnapshot()
                AppScreenReader.read(context, frame!!, { true }) { observation, failure -> result = observation; error = failure; read.countDown() }
            }
            assertTrue("OCR timed out", read.await(25, TimeUnit.SECONDS))
            assertNotNull(error, result)
            assertTrue(result!!.visual, result!!.visual.contains("Температура", true))
            assertTrue(result!!.visual, result!!.visual.contains("Влажность", true))
            assertTrue(result!!.visual, result!!.visual.contains("82"))
            assertTrue(result!!.visual, result!!.visual.contains("Rain", true))
            assertTrue(result!!.words.any { it.text == "82" && it.confidence >= 70 && it.right > it.left })
            assertFalse(result!!.accessible.contains("Температура"))
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Thread.sleep(500)
            val rejected = CountDownLatch(1)
            instrumentation.runOnMainSync { AppScreenReader.read(context, frame!!, { true }) { observation, failure ->
                assertNull(observation); assertNotNull(failure); rejected.countDown()
            } }
            assertTrue(rejected.await(2, TimeUnit.SECONDS))
        } finally {
            setting(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, old)
            setting(Settings.Secure.ACCESSIBILITY_ENABLED, oldEnabled.toString())
        }
    }
}
