package local.voice

import android.Manifest
import android.app.UiAutomation
import android.content.Intent
import android.content.ComponentName
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AppSelectionTest {
    @Test fun spokenChoicePreservesContinuationAndCancellationDiscardsIt() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val oldServices = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val oldEnabled = Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        fun settings(name: String, value: String?) {
            automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS)
            try { assertTrue(Settings.Secure.putString(context.contentResolver, name, value)) }
            finally { automation.dropShellPermissionIdentity() }
        }
        val launcher = ComponentName("local.voice.fixture", "local.voice.fixture.FieldActivity")
        val oldLauncherState = context.packageManager.getComponentEnabledSetting(launcher)
        fun setLauncherState(mode: String, expected: Int) {
            context.startActivity(Intent().setComponent(ComponentName("local.voice.fixture", "local.voice.fixture.LauncherControlActivity"))
                .putExtra("mode", mode).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val until = System.currentTimeMillis() + 5000
            while (System.currentTimeMillis() < until) {
                if (context.packageManager.getComponentEnabledSetting(launcher) == expected) return
                Thread.sleep(50)
            }
            fail("Fixture did not change its launcher state to $mode")
        }
        fun launcherState(enabled: Boolean) = setLauncherState(if (enabled) "enable" else "disable",
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
        val statuses = CopyOnWriteArrayList<String>()
        val listens = List(10) { CountDownLatch(1) }
        var offeredField: PhoneAccessibility.FieldTarget? = null
        var count = 0
        var conversation: VoiceConversation? = null
        fun step(text: String, index: Int) {
            instrumentation.runOnMainSync { conversation!!.respond(text) }
            assertTrue("Did not listen after $text: $statuses", listens[index].await(30, TimeUnit.SECONDS))
        }
        fun field() = automation.rootInActiveWindow.findAccessibilityNodeInfosByViewId("local.voice.fixture:id/message").single().text.toString()
        try {
            settings(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "local.voice/local.voice.PhoneAccessibility")
            settings(Settings.Secure.ACCESSIBILITY_ENABLED, "1")
            val until = System.currentTimeMillis() + 5000
            while (PhoneAccessibility.connected == null && System.currentTimeMillis() < until) Thread.sleep(100)
            assertNotNull(PhoneAccessibility.connected)
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            automation.waitForIdle(200, 5000)
            instrumentation.runOnMainSync {
                val tools = DeviceTools(context)
                assertEquals("Exact app label must select that app", "local.voice", tools.execute(VoiceCommand.Open("Ассистент"))!!.openedPackage)
                tools.clear()
            }
            instrumentation.runOnMainSync {
                conversation = VoiceConversation(context, { status ->
                    statuses.add(status)
                    if (status.startsWith("Заменить текст")) offeredField = PhoneAccessibility.connected?.fieldTarget()
                    if (status == "Слушаю…" && count < listens.size) listens[count++].countDown()
                }, {}, requireHeadset = false).also { it.start() }
            }
            assertTrue(listens[0].await(30, TimeUnit.SECONDS))
            // A shared partial label is ambiguous; the full exact label above is not.
            step("открой Ассист и напиши Выбранное приложение", 1)
            val offered = statuses.singleOrNull { it.startsWith("Какое приложение открыть?") }
            assertNotNull("No ambiguous app choice: $statuses", offered)
            offered!!
            assertTrue(offered, offered.contains("1: Ассистент.") && offered.contains("2: Ассистент: тест полей."))
            assertEquals("local.voice", automation.rootInActiveWindow.packageName.toString())
            step("подтверждаю", 2)
            assertTrue(statuses.any { it.startsWith("Сначала выберите приложение") })
            step("пятое приложение", 3)
            assertTrue(statuses.any { it.startsWith("Такого варианта нет") })
            step("второе приложение", 4)
            assertEquals("local.voice.fixture", automation.rootInActiveWindow.packageName.toString())
            assertTrue(statuses.any { it.startsWith("Заменить текст") && it.contains("Выбранное приложение") })
            assertNotEquals("Text was written without confirmation", "Выбранное приложение", field())
            step("подтверждаю", 5)
            assertEquals("Statuses=$statuses; offered=$offeredField; now=${PhoneAccessibility.connected?.fieldTarget()}", "Выбранное приложение", field())
            assertTrue(statuses.any { it.startsWith("Текст записан") })
            step("открой Ассист и напиши Отменённая связка", 6)
            step("отмена", 7)
            step("второе приложение", 8)
            assertTrue(statuses.any { it.startsWith("Нет списка для выбора") })
            step("подтверждаю", 9)
            assertTrue(statuses.any { it.startsWith("Нет действия для подтверждения") })
            assertEquals("Statuses=$statuses; offered=$offeredField; now=${PhoneAccessibility.connected?.fieldTarget()}", "Выбранное приложение", field())
            instrumentation.runOnMainSync { conversation!!.close() }
            val tools = DeviceTools(context)
            var selected: DeviceTools.Reply? = null
            instrumentation.runOnMainSync {
                assertTrue(tools.execute(VoiceCommand.Open("Ассист"))!!.appSelection)
                selected = tools.execute(VoiceCommand.Choose(1))
            }
            assertEquals("local.voice.fixture", selected!!.openedPackage)
            launcherState(false)
            assertTrue(selected!!.beforeOpening!!.invoke()!!.startsWith("Список приложений изменился"))
            assertTrue(selected!!.afterSpeech!!.invoke()!!.startsWith("Список приложений изменился"))
            launcherState(true)
            instrumentation.runOnMainSync { assertTrue(tools.execute(VoiceCommand.Open("Ассист"))!!.appSelection) }
            launcherState(false)
            instrumentation.runOnMainSync { selected = tools.execute(VoiceCommand.Choose(1)) }
            assertNull(selected!!.openedPackage)
            assertNull(selected!!.afterSpeech)
            assertTrue(selected!!.text.startsWith("Список приложений изменился"))
            tools.clear()
        } finally {
            instrumentation.runOnMainSync { conversation?.close() }
            val restore = when (oldLauncherState) {
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> "default"
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> "enable"
                else -> "disable"
            }
            setLauncherState(restore, oldLauncherState)
            settings(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, oldServices)
            settings(Settings.Secure.ACCESSIBILITY_ENABLED, oldEnabled.toString())
        }
    }
}
