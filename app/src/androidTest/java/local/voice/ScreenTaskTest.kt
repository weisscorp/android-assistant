package local.voice

import android.Manifest
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class ScreenTaskTest {
    @Test fun realModelOpenTapConfirmFreshResultAndCancel() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val oldServices = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val oldEnabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED)
        fun settings(name: String, value: String?) {
            automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS)
            try { assertTrue(Settings.Secure.putString(context.contentResolver, name, value)) }
            finally { automation.dropShellPermissionIdentity() }
        }
        val statuses = CopyOnWriteArrayList<String>()
        val observations = CopyOnWriteArrayList<String>()
        val listens = List(10) { CountDownLatch(1) }
        var count = 0
        var conversation: VoiceConversation? = null
        val changeDuringPlanning = AtomicBoolean(false)
        fun step(text: String, index: Int) {
            instrumentation.runOnMainSync { conversation!!.respond(text) }
            assertTrue("No listening after $text: $statuses", listens[index].await(100, TimeUnit.SECONDS))
        }
        fun field() = automation.rootInActiveWindow.findAccessibilityNodeInfosByViewId("local.voice.fixture:id/message").single().text.toString()
        try {
            settings(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "local.voice/local.voice.PhoneAccessibility")
            settings(Settings.Secure.ACCESSIBILITY_ENABLED, "1")
            val until = System.currentTimeMillis() + 5000
            while (PhoneAccessibility.connected == null && System.currentTimeMillis() < until) Thread.sleep(100)
            assertNotNull(PhoneAccessibility.connected)
            context.startActivity(Intent().setComponent(ComponentName("local.voice.fixture", "local.voice.fixture.FieldActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            automation.waitForIdle(200, 5000)
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            automation.waitForIdle(200, 5000)
            instrumentation.runOnMainSync {
                conversation = VoiceConversation(context, { status ->
                    statuses.add(status)
                    if (status.startsWith("Выбираю следующий") && changeDuringPlanning.getAndSet(false)) {
                        val root = automation.rootInActiveWindow
                        assertEquals("local.voice.fixture", root.packageName.toString())
                        val input = root.findAccessibilityNodeInfosByViewId("local.voice.fixture:id/message").single()
                        val args = android.os.Bundle().apply {
                            putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Обновлённый черновик")
                        }
                        assertTrue("Fixture change was not accepted", input.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args))
                    }
                    if (status.startsWith("Выбираю следующий") || status.startsWith("Экран изменился во время")) {
                        observations.add("$status: ${PhoneAccessibility.connected?.foregroundSnapshot()}; ${PhoneAccessibility.connected?.elements()}")
                    }
                    if (status == "Слушаю…" && count < listens.size) listens[count++].countDown()
                }, {}, requireHeadset = false)
                conversation!!.start()
            }
            assertTrue("Start failed: $statuses", listens[0].await(40, TimeUnit.SECONDS))
            step("выполни задачу открой Ассист, нажми Изменить черновик и прочитай результат", 1)
            assertTrue("No application choice: $statuses", statuses.any { it.contains("Какое приложение открыть") })
            step("подтверждаю", 2)
            assertTrue(statuses.any { it.startsWith("Сначала выберите приложение") })
            step("второе приложение", 3)
            assertTrue("No confirmation after app choice: $statuses; observations=$observations", statuses.any { it.startsWith("Нажать кнопку Изменить черновик?", ignoreCase = true) })
            assertEquals("Черновик", field())
            step("подтверждаю", 4)
            assertEquals("Изменено другим пользователем", field())
            assertTrue("No fresh result: $statuses", statuses.any { it == "Работа по экрану закончена." })
            val genericStart = statuses.size
            changeDuringPlanning.set(true)
            step("выполни задачу замени содержимое сообщения на Буду скоро", 5)
            assertTrue("Changed observation was not replanned: $statuses; observations=$observations; modelFailure=${LocalBrain.lastFailure}", statuses.drop(genericStart).any { it == "Экран изменился. Проверю его заново перед действием." })
            assertTrue("No semantic write offer: $statuses", statuses.drop(genericStart).any { it.startsWith("Заменить текст") && it.contains("Буду скоро", ignoreCase = true) })
            assertEquals("Обновлённый черновик", field())
            step("подтверждаю", 6)
            assertEquals("Буду скоро", field())
            assertTrue("Generic task did not finish: $statuses", statuses.drop(genericStart).any { it == "Работа по экрану закончена." })
            assertTrue(statuses.drop(genericStart).any { it.startsWith("Текст записан") })
            assertFalse("No invented delivery: $statuses", statuses.any { it.contains("Сообщение отправлено", ignoreCase = true) })
            step("выполни задачу напиши в поле Сообщение Отменённая запись", 7)
            step("отмена", 8)
            step("подтверждаю", 9)
            assertEquals("Буду скоро", field())
            assertTrue(statuses.any { it.startsWith("Нет действия для подтверждения") })
        } finally {
            instrumentation.runOnMainSync { conversation?.close() }
            settings(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, oldServices)
            settings(Settings.Secure.ACCESSIBILITY_ENABLED, oldEnabled)
        }
    }
}
