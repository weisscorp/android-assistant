package local.voice

import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class WeatherNavigationTest {
    @Test fun namedSearchDoesNotReadPreviousPlaceAndAmbiguityRequiresObservedChoice() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val old = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val oldEnabled = Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        fun setting(name: String, value: String?) {
            automation.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_SECURE_SETTINGS)
            try { Settings.Secure.putString(context.contentResolver, name, value) }
            finally { automation.dropShellPermissionIdentity() }
        }
        val component = ComponentName("local.voice.fixture", "local.voice.fixture.WeatherLauncher")
        automation.adoptShellPermissionIdentity(android.Manifest.permission.CHANGE_COMPONENT_ENABLED_STATE)
        try { context.packageManager.setComponentEnabledSetting(component, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP) }
        finally { automation.dropShellPermissionIdentity() }
        val responses = LinkedBlockingQueue<String>()
        var nav: WindyNavigation? = null
        fun start(query: String, menu: Boolean = false) {
            responses.clear()
            context.startActivity(Intent().setComponent(ComponentName("local.voice.fixture", "local.voice.fixture.WeatherActivity"))
                .putExtra("menu", menu)
                .putExtra("nested", menu)
                .putExtra("reset", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            Thread.sleep(700)
            instrumentation.runOnMainSync {
                nav?.cancel()
                nav = WindyNavigation(context, WeatherRequest.parse(query)!!, { true }, { responses.offer(it) }, { null },
                    { forecast, _, error -> responses.offer(forecast?.place ?: error.orEmpty()) }, "local.voice.fixture")
                nav!!.start()
            }
        }
        try {
            setting(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "local.voice/local.voice.PhoneAccessibility")
            setting(Settings.Secure.ACCESSIBILITY_ENABLED, "1")
            val until = System.currentTimeMillis() + 5000
            while (PhoneAccessibility.connected == null && System.currentTimeMillis() < until) Thread.sleep(100)
            // No city: the home card must be read without GPS, Settings or Search.
            start("Погода из windy.app", menu = true)
            assertEquals("Wrong City", responses.poll(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                val ui = PhoneAccessibility.connected!!
                assertTrue(WindyVisual.openCard(ui, ui.foregroundSnapshot()!!))
            }
            Thread.sleep(500)
            assertTrue(PhoneAccessibility.connected!!.readScreen().contains("Модели погоды"))
            assertFalse(PhoneAccessibility.connected!!.readScreen().contains("Нажата надпись дня"))
            start("Какая погода в Москве завтра?", menu = true)
            assertEquals("Москва", responses.poll(20, TimeUnit.SECONDS))
            start("Какая погода в Берлине?")
            assertTrue(responses.poll(20, TimeUnit.SECONDS).orEmpty().contains("несколько мест"))
            instrumentation.runOnMainSync { assertTrue(nav!!.answer(VoiceCommand.Choose(1))) }
            assertEquals("Берлин Западный", responses.poll(12, TimeUnit.SECONDS))
            start("Какая погода в Берлине?")
            assertTrue(responses.poll(20, TimeUnit.SECONDS).orEmpty().contains("несколько мест"))
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Thread.sleep(500)
            instrumentation.runOnMainSync { assertTrue(nav!!.answer(VoiceCommand.Choose(0))) }
            assertTrue(responses.poll(3, TimeUnit.SECONDS).orEmpty().contains("изменились"))
        } finally {
            instrumentation.runOnMainSync { nav?.cancel() }
            setting(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, old)
            setting(Settings.Secure.ACCESSIBILITY_ENABLED, oldEnabled.toString())
        }
    }
}
