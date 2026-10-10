package local.voice

import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Android UI routing through text entry; voice/TTS integration has separate regression tests. */
@RunWith(AndroidJUnit4::class)
class TelegramReentryTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun elements() = PhoneAccessibility.connected?.elements().orEmpty()
    private fun draft() = elements().single { it.editable && it.hint == "Сообщение" }.text
    private fun backs() = elements().single { it.text.startsWith("Переходов назад: ") }.text.substringAfter(": ").toInt()
    private fun sent(number: Int) = assertTrue("Wrong send count: ${elements()}", elements().any { it.text == "Нажатий отправки: $number" })

    private fun withFixture(initial: String = "home", test: (VoiceConversation, LinkedBlockingQueue<String>) -> Unit) {
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val qemu = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("getprop ro.kernel.qemu"))
            .bufferedReader().use { it.readText().trim() }
        assertEquals("Reentry tests are only allowed on our emulator", "1", qemu)
        val field = ComponentName("local.voice.fixture", "local.voice.fixture.FieldActivity")
        val telegram = ComponentName("local.voice.fixture", "local.voice.fixture.TelegramLauncher")
        val oldField = context.packageManager.getComponentEnabledSetting(field)
        val oldTelegram = context.packageManager.getComponentEnabledSetting(telegram)
        val oldServices = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val oldEnabled = Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        fun setting(key: String, value: String?) {
            automation.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_SECURE_SETTINGS)
            try { assertTrue(Settings.Secure.putString(context.contentResolver, key, value)) }
            finally { automation.dropShellPermissionIdentity() }
        }
        fun launchers(mode: String, fieldState: Int, telegramState: Int) {
            context.startActivity(Intent().setComponent(ComponentName("local.voice.fixture", "local.voice.fixture.LauncherControlActivity"))
                .putExtra("mode", mode).putExtra("field_state", fieldState).putExtra("telegram_state", telegramState)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val until = System.currentTimeMillis() + 5000
            while (System.currentTimeMillis() < until) {
                if (context.packageManager.getComponentEnabledSetting(field) == fieldState &&
                    context.packageManager.getComponentEnabledSetting(telegram) == telegramState) return
                Thread.sleep(50)
            }
            fail("Fixture launchers did not settle")
        }
        var conversation: VoiceConversation? = null
        try {
            setting(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "local.voice/local.voice.PhoneAccessibility")
            setting(Settings.Secure.ACCESSIBILITY_ENABLED, "1")
            val until = System.currentTimeMillis() + 5000
            while (PhoneAccessibility.connected == null && System.currentTimeMillis() < until) Thread.sleep(50)
            assertNotNull(PhoneAccessibility.connected)
            launchers("telegram", PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
            context.startActivity(Intent().setComponent(ComponentName("local.voice.fixture", "local.voice.fixture.TelegramActivity"))
                .putExtra("initial_screen", initial).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            automation.waitForIdle(200, 5000)
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            automation.waitForIdle(200, 5000)
            // waitForIdle can return before the new Activity becomes the active AX window.
            // Starting the request against the previous fixture frame can skip its launcher.
            val mainDeadline = SystemClock.elapsedRealtime() + 5000
            var stable = 0
            while (stable < 5 && SystemClock.elapsedRealtime() < mainDeadline) {
                stable = if (automation.rootInActiveWindow?.packageName?.toString() == context.packageName &&
                    PhoneAccessibility.connected?.foregroundPackage() == null) stable + 1 else 0
                Thread.sleep(100)
            }
            assertEquals("Assistant setup window did not settle", 5, stable)
            val replies = LinkedBlockingQueue<String>()
            instrumentation.runOnMainSync {
                conversation = VoiceConversation(context, {}, {}, telegramPackages = setOf("local.voice.fixture"),
                    textOnly = true, replyObserver = { replies.offer(it) }).also { it.start() }
            }
            test(conversation!!, replies)
            assertTrue("Text routing unexpectedly opened microphone", context.getSystemService(AudioManager::class.java).activeRecordingConfigurations.isEmpty())
        } finally {
            instrumentation.runOnMainSync { conversation?.close() }
            launchers("restore_telegram", oldField, oldTelegram)
            setting(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, oldServices)
            setting(Settings.Secure.ACCESSIBILITY_ENABLED, oldEnabled.toString())
        }
    }
    private fun step(voice: VoiceConversation, replies: LinkedBlockingQueue<String>, text: String, expected: String) {
        assertTrue("Previous response was not consumed: $replies", replies.isEmpty())
        println("TELEGRAM_REENTRY input=$text")
        instrumentation.runOnMainSync { voice.respond(text) }
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (true) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            val reply = if (remaining > 0) replies.poll(remaining, TimeUnit.MILLISECONDS) else null
            if (reply == null) error("No final response to $text; screen=${elements()}")
            println("TELEGRAM_REENTRY response=$reply")
            // Opening an app is an announcement, not the search/action result.
            // Any other unexpected answer must still fail this step immediately.
            if (reply == "Открываю Telegram.") continue
            assertTrue("Expected $expected after $text; actual $reply; screen=${elements()}", reply.contains(expected))
            return
        }
    }

    @Test fun repeatedSearchChangesRecipientWithoutCarryingSendOrLosingOtherDraft() = withFixture { voice, replies ->
        step(voice, replies, "найди в телеграме Маша", "Кого выбрать")
        step(voice, replies, "второй", "Открыт чат")
        step(voice, replies, "напиши сообщение Буду скоро", "Записать сообщение")
        step(voice, replies, "подтверждаю", "Черновик в чате Маша записан")
        assertEquals("Буду скоро", draft()); sent(0)
        step(voice, replies, "найди в телеграме Маша", "Кого выбрать")
        assertTrue("Did not return through chat navigation", backs() in 1..3)
        step(voice, replies, "первый", "Открыт чат")
        assertTrue(elements().any { it.text == "@masha_one" })
        assertEquals("Черновик", draft()); sent(0)
        step(voice, replies, "отправь", "нет подтверждённого черновика")
        sent(0)
        step(voice, replies, "найди в телеграме Маша", "Кого выбрать")
        step(voice, replies, "второй", "Открыт чат")
        assertEquals("Previous draft must survive navigating away", "Буду скоро", draft()); sent(0)
        step(voice, replies, "отправь", "нет подтверждённого черновика")
        sent(0)
    }

    @Test fun existingSearchFieldIsReusedWithoutBackNavigation() = withFixture { voice, replies ->
        step(voice, replies, "найди в телеграме Маша", "Кого выбрать")
        assertEquals(0, backs())
        step(voice, replies, "найди в телеграме @masha_one", "Кого выбрать")
        assertEquals(0, backs())
        step(voice, replies, "первый", "Открыт чат")
        assertTrue(elements().any { it.text == "@masha_one" }); sent(0)
    }

    @Test fun unknownNestedPageStopsAfterThreeBackActions() = withFixture("nested") { voice, replies ->
        step(voice, replies, "найди в телеграме Маша", "кнопку поиска Telegram")
        assertEquals(3, backs())
        assertEquals("local.voice.fixture", PhoneAccessibility.connected?.foregroundPackage())
        assertFalse(elements().any { it.editable })
    }

    @Test fun readingKeepsOurDraftAndSendingStillNeedsFinalConsent() = withFixture { voice, replies ->
        step(voice, replies, "найди в телеграме Маша", "Кого выбрать")
        step(voice, replies, "второй", "Открыт чат")
        step(voice, replies, "напиши сообщение Буду скоро", "Записать сообщение")
        step(voice, replies, "подтверждаю", "Черновик в чате Маша записан")
        step(voice, replies, "прочитай сообщения", "Видимые сообщения")
        assertEquals("Буду скоро", draft()); sent(0)
        step(voice, replies, "отправь", "Отправить в чате Маша сообщение")
        sent(0)
        step(voice, replies, "подтверждаю", "Доставка получателю не проверена")
        sent(1)
    }
}
