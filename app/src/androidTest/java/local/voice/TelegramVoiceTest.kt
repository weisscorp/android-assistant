package local.voice

import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** Real conversation/TTS/listening/launcher/AX assembly, input through recognized-text API. */
@RunWith(AndroidJUnit4::class)
class TelegramVoiceTest {
    @Test fun requestSelectsPersonConfirmsEachMutationAndReturnsToListening() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val oldServices = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val oldEnabled = Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        val fieldLauncher = ComponentName("local.voice.fixture", "local.voice.fixture.FieldActivity")
        val telegramLauncher = ComponentName("local.voice.fixture", "local.voice.fixture.TelegramLauncher")
        val oldField = context.packageManager.getComponentEnabledSetting(fieldLauncher)
        val oldTelegram = context.packageManager.getComponentEnabledSetting(telegramLauncher)
        fun setting(key: String, value: String?) {
            automation.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_SECURE_SETTINGS)
            try { assertTrue(Settings.Secure.putString(context.contentResolver, key, value)) }
            finally { automation.dropShellPermissionIdentity() }
        }
        fun launcher(mode: String, field: Int, telegram: Int) {
            context.startActivity(Intent().setComponent(ComponentName("local.voice.fixture", "local.voice.fixture.LauncherControlActivity"))
                .putExtra("mode", mode).putExtra("field_state", field).putExtra("telegram_state", telegram).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val deadline = System.currentTimeMillis() + 5000
            while (System.currentTimeMillis() < deadline) {
                if (context.packageManager.getComponentEnabledSetting(fieldLauncher) == field &&
                    context.packageManager.getComponentEnabledSetting(telegramLauncher) == telegram) return
                Thread.sleep(50)
            }
            fail("Fixture launchers did not reach requested state")
        }
        val statuses = CopyOnWriteArrayList<String>()
        val spoken = CopyOnWriteArrayList<String>()
        val listening = Semaphore(0)
        var conversation: VoiceConversation? = null
        var currentStep = "start"
        fun awaitListening() = assertTrue("Conversation did not return to listening: $statuses", listening.tryAcquire(60, TimeUnit.SECONDS))
        fun step(text: String) {
            currentStep = text
            instrumentation.runOnMainSync { conversation!!.respond(text) }
            awaitListening()
        }
        fun elements() = PhoneAccessibility.connected?.elements().orEmpty()
        fun body(): String {
            val screen = elements()
            val fields = screen.filter { it.editable && it.hint == "Сообщение" }
            assertEquals("Message field missing/ambiguous after $currentStep: statuses=$statuses; spoken=$spoken; screen=$screen", 1, fields.size)
            return fields.single().text
        }
        fun clicks(number: Int) = assertTrue("Send count $number absent: ${elements()}", elements().any { it.text == "Нажатий отправки: $number" })
        fun start() {
            context.startActivity(Intent().setComponent(ComponentName("local.voice.fixture", "local.voice.fixture.TelegramActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            automation.waitForIdle(300, 5000)
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            automation.waitForIdle(300, 5000)
            statuses.clear(); spoken.clear(); listening.drainPermits()
            instrumentation.runOnMainSync {
                conversation = VoiceConversation(context, { status ->
                    statuses.add(status)
                    if (status == "Слушаю…") listening.release()
                }, {}, requireHeadset = false, telegramPackages = setOf("local.voice.fixture"), speechObserver = { spoken.add(it) }).also { it.start() }
            }
            awaitListening()
        }
        fun close() {
            instrumentation.runOnMainSync { conversation?.close(); conversation = null }
            val deadline = System.currentTimeMillis() + 5000
            val audio = context.getSystemService(AudioManager::class.java)
            while (audio.activeRecordingConfigurations.isNotEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(50)
            assertTrue("Conversation kept its microphone", audio.activeRecordingConfigurations.isEmpty())
        }
        try {
            setting(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "local.voice/local.voice.PhoneAccessibility")
            setting(Settings.Secure.ACCESSIBILITY_ENABLED, "1")
            val deadline = System.currentTimeMillis() + 5000
            while (PhoneAccessibility.connected == null && System.currentTimeMillis() < deadline) Thread.sleep(100)
            assertNotNull(PhoneAccessibility.connected)
            launcher("telegram", PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
            val recognized = "отправь в телеграме Маша сообщение Буду скоро"
            val request = (VoiceCommands.parse(recognized) as VoiceCommand.Telegram).request
            start()
            step(recognized)
            assertEquals("local.voice.fixture", PhoneAccessibility.connected?.foregroundPackage())
            assertTrue(elements().any { it.description.contains("@masha_two") })
            step("подтверждаю") // A list needs a choice; confirmation cannot select someone.
            assertFalse(elements().any { it.hint == "Сообщение" })
            step("второй")
            assertTrue("Chosen chat absent: $statuses; ${elements()}", elements().any { it.text == "@masha_two" })
            assertEquals("Черновик", body()); clicks(0)
            step("подтверждаю")
            assertEquals(request.text, body()); clicks(0)
            step("подтверждаю")
            clicks(1)
            assertTrue(elements().any { it.description == request.text + "\nОтправлено сегодня в 12:01" })
            step("подтверждаю") // A terminal receipt must leave no pending Send.
            clicks(1)
            assertTrue(statuses.any { it.startsWith("Нет действия для подтверждения") })
            close()
            start()
            step("отправь в телеграме Маша сообщение отмена и нажми отправить")
            step("первый")
            assertEquals("Черновик", body()); clicks(0)
            step("отмена")
            step("подтверждаю")
            assertEquals("Черновик", body()); clicks(0)
            close()
            start()
            step("прочитай сообщения в телеграме")
            assertTrue("Recipient prompt must precede search: $statuses; ${PhoneAccessibility.connected?.foregroundSnapshot()}; ${elements()}", elements().any { it.text.equals("Поиск", ignoreCase = true) })
            step("подтверждаю")
            assertFalse("Confirmation cannot invent a recipient", elements().any { it.editable })
            step("Маша")
            assertTrue(elements().any { it.description.contains("@masha_one") })
            step("первый")
            assertTrue(elements().any { it.text == "@masha_one" })
            assertEquals("Черновик", body()); clicks(0)
            assertTrue("Private response must use generic status", statuses.any { it == "Telegram" })
            assertTrue("Private message did not reach real TTS: $spoken", spoken.any { it.contains("Привет") })
            assertFalse("Private body leaked to status", statuses.any { it.contains("Привет") })
            step("напиши сообщение Буду скоро")
            assertEquals("Черновик", body()); clicks(0)
            step("подтверждаю")
            assertEquals("Буду скоро", body()); clicks(0)
            step("прочитай сообщения")
            assertEquals("Reading must preserve our confirmed draft", "Буду скоро", body()); clicks(0)
            assertTrue("Read reply did not reach TTS", spoken.last().contains("Привет"))
            step("отправь")
            assertEquals("Буду скоро", body()); clicks(0)
            assertTrue("Draft send must ask for consent: $spoken", spoken.last().contains("Отправить в чате Маша сообщение"))
            step("подтверждаю")
            clicks(1)
            step("подтверждаю")
            clicks(1)
            close()
            start()
            step("найди в телеграме Маша")
            step("второй")
            assertTrue(elements().any { it.text == "@masha_two" })
            val readBefore = spoken.count { it.contains("Видимые сообщения") }
            step("прочитай сообщения")
            assertEquals("Follow-up must read the selected chat", readBefore + 1, spoken.count { it.contains("Видимые сообщения") })
            step("напиши сообщение Отмена и отправь")
            step("подтверждаю")
            assertEquals("Отмена и отправь", body()); clicks(0)
            assertTrue(automation.rootInActiveWindow.findAccessibilityNodeInfosByText("Другой адресат").single()
                .performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            automation.waitForIdle(300, 5000)
            step("отправь")
            clicks(0)
            assertTrue("Changed chat must reject context: $spoken", spoken.last().contains("Выбранный чат Telegram истёк или изменился"))
            step("подтверждаю")
            clicks(0)
            close()
        } finally {
            close()
            launcher("restore_telegram", oldField, oldTelegram)
            setting(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, oldServices)
            setting(Settings.Secure.ACCESSIBILITY_ENABLED, oldEnabled.toString())
        }
    }
}
