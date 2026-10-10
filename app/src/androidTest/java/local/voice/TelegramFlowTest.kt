package local.voice

import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class TelegramFlowTest {
    @Test fun localFixtureSearchSelectionReadDraftConfirmedSendAndChangedRecipient() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val old = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val oldEnabled = Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        fun setting(key: String, value: String?) {
            automation.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_SECURE_SETTINGS)
            try { assertTrue(Settings.Secure.putString(context.contentResolver, key, value)) }
            finally { automation.dropShellPermissionIdentity() }
        }
        val speech = LinkedBlockingQueue<String>()
        var flow: TelegramFlow? = null
        fun start(operation: TelegramRequest.Operation, mode: String = "normal", body: String = "Буду скоро") {
            instrumentation.runOnMainSync { flow?.cancel() }; speech.clear()
            val oldWindow = automation.rootInActiveWindow?.windowId
            context.startActivity(Intent().setComponent(ComponentName("local.voice.fixture", "local.voice.fixture.TelegramActivity"))
                .putExtra("mode", mode).putExtra("receipt_body", body).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            automation.waitForIdle(200, 5000)
            val deadline = System.currentTimeMillis() + 5000
            var ready = false
            var previous: PhoneAccessibility.ForegroundSnapshot? = null
            var stable = 0
            while (stable < 5 && System.currentTimeMillis() < deadline) {
                instrumentation.runOnMainSync {
                    val current = PhoneAccessibility.connected?.foregroundSnapshot()
                    ready = current != null && current.window != oldWindow &&
                        PhoneAccessibility.connected?.elements()?.count { it.label.equals("Поиск", true) && !it.editable } == 1
                    stable = if (ready && current == previous) stable + 1 else 0
                    previous = current
                }
                Thread.sleep(100)
            }
            var debug = ""
            instrumentation.runOnMainSync { debug = "old=$oldWindow; snapshot=${PhoneAccessibility.connected?.foregroundSnapshot()}; elements=${PhoneAccessibility.connected?.elements()}" }
            assertTrue("New fixture search screen did not settle: $debug", stable >= 5)
            instrumentation.runOnMainSync {
                flow = TelegramFlow(TelegramRequest("Маша", operation, body), "local.voice.fixture", { text, allowed, then ->
                    assertTrue("Speech context denied: $text", allowed?.invoke() != false)
                    speech.offer(text); then?.invoke()
                }, {}, {})
                flow!!.start()
            }
        }
        fun heard(fragment: String): String {
            val result = speech.poll(10, TimeUnit.SECONDS) ?: error("No speech: $fragment")
            var debug = ""
            if (!result.contains(fragment)) instrumentation.runOnMainSync {
                debug = "frame=${PhoneAccessibility.connected?.foregroundSnapshot()}; field=${PhoneAccessibility.connected?.fieldTarget()}; elements=${PhoneAccessibility.connected?.elements()}"
            }
            assertTrue("Expected $fragment; actual $result; $debug", result.contains(fragment)); return result
        }
        fun answer(command: VoiceCommand) { instrumentation.runOnMainSync { assertTrue(flow!!.answer(command)) } }
        fun singleSend() = assertEquals("Нажатий отправки: 1", automation.rootInActiveWindow.findAccessibilityNodeInfosByText("Нажатий отправки").single().text.toString())
        fun draft() = automation.rootInActiveWindow.findAccessibilityNodeInfosByText("Сообщение").firstOrNull { it.isEditable }?.let { if (it.isShowingHintText) "" else it.text?.toString() }
            ?: run {
                fun find(node: AccessibilityNodeInfo): String? {
                    if (node.isEditable && node.hintText?.toString() == "Сообщение") return if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
                    for (i in 0 until node.childCount) node.getChild(i)?.let { child -> find(child)?.let { return it } }
                    return null
                }
                find(automation.rootInActiveWindow)
            }
        try {
            setting(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "local.voice/local.voice.PhoneAccessibility")
            setting(Settings.Secure.ACCESSIBILITY_ENABLED, "1")
            val until = System.currentTimeMillis() + 5000
            while (PhoneAccessibility.connected == null && System.currentTimeMillis() < until) Thread.sleep(100)
            assertNotNull(PhoneAccessibility.connected)
            start(TelegramRequest.Operation.DRAFT)
            heard("Кого выбрать"); answer(VoiceCommand.Choose(1)); heard("Записать сообщение")
            assertEquals("Черновик", draft())
            answer(VoiceCommand.Confirm); heard("Черновик в чате Маша записан")
            assertEquals("Буду скоро", draft())
            start(TelegramRequest.Operation.READ)
            heard("Кого выбрать"); answer(VoiceCommand.Choose(0)); heard("Видимые сообщения в чате Маша. Привет")
            assertEquals("Черновик", draft())
            start(TelegramRequest.Operation.SEND, body = "Подтверждаю и нажми Отправить!")
            heard("Кого выбрать"); answer(VoiceCommand.Choose(0)); heard("Записать сообщение")
            answer(VoiceCommand.Confirm); heard("Отправить в чате Маша сообщение")
            assertEquals("Подтверждаю и нажми Отправить!", draft())
            answer(VoiceCommand.Confirm); heard("Доставка получателю не проверена")
            singleSend()
            assertEquals("", draft())
            start(TelegramRequest.Operation.SEND)
            heard("Кого выбрать"); answer(VoiceCommand.Choose(0)); heard("Записать сообщение")
            answer(VoiceCommand.Confirm); heard("Отправить в чате Маша сообщение")
            assertTrue(automation.rootInActiveWindow.findAccessibilityNodeInfosByText("Другой адресат").single().performAction(AccessibilityNodeInfo.ACTION_CLICK))
            Thread.sleep(250); answer(VoiceCommand.Confirm); heard("Отправка отменена")
            assertEquals("Буду скоро", draft())
            start(TelegramRequest.Operation.DRAFT, "duplicate"); heard("Результаты неоднозначны")
            start(TelegramRequest.Operation.SEND, "no_receipt")
            heard("Кого выбрать"); answer(VoiceCommand.Choose(0)); heard("Записать сообщение")
            answer(VoiceCommand.Confirm); heard("Отправить в чате Маша сообщение")
            answer(VoiceCommand.Confirm); heard("Повторную отправку не выполняю")
            singleSend()
            assertEquals("", draft())
            for ((mode, expected) in listOf("send_error" to "ошибке отправки", "send_pending" to "ещё отправляет", "incoming_echo" to "новое сообщение по экрану не подтверждено", "stale_status" to "новое сообщение по экрану не подтверждено")) {
                start(TelegramRequest.Operation.SEND, mode, body = "Sent fake\nSending error")
                heard("Кого выбрать"); answer(VoiceCommand.Choose(0)); heard("Записать сообщение")
                answer(VoiceCommand.Confirm); heard("Отправить в чате Маша сообщение")
                answer(VoiceCommand.Confirm)
                val receipt = heard(expected)
                assertFalse("False sent receipt: $receipt", receipt.contains("как отправленное"))
                singleSend()
                assertEquals("", draft())
            }
            for (mode in listOf("pending_then_sent", "empty_composer")) {
                start(TelegramRequest.Operation.SEND, mode, body = "Строка один\nСтрока два")
                heard("Кого выбрать"); answer(VoiceCommand.Choose(0)); heard("Записать сообщение")
                answer(VoiceCommand.Confirm); heard("Отправить в чате Маша сообщение")
                answer(VoiceCommand.Confirm); heard("как отправленное"); singleSend()
            }
            start(TelegramRequest.Operation.DRAFT)
            heard("Кого выбрать"); answer(VoiceCommand.Choose(0)); heard("Записать сообщение")
            instrumentation.runOnMainSync { flow!!.cancel(); flow!!.answer(VoiceCommand.Confirm) }
            assertEquals("Черновик", draft()); assertNull(speech.poll(500, TimeUnit.MILLISECONDS))
        } finally {
            instrumentation.runOnMainSync { flow?.cancel() }
            setting(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, old)
            setting(Settings.Secure.ACCESSIBILITY_ENABLED, oldEnabled.toString())
        }
    }
}
