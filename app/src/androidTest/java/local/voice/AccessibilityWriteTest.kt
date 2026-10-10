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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AccessibilityWriteTest {
    @Test fun externalFieldConfirmationStalenessAmbiguityAndActualResult() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val oldServices = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val oldEnabled = Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        fun settings(name: String, value: String?) {
            automation.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_SECURE_SETTINGS)
            try { assertTrue(Settings.Secure.putString(context.contentResolver, name, value)) }
            finally { automation.dropShellPermissionIdentity() }
        }
        fun launch(mode: String) {
            val oldWindow = automation.rootInActiveWindow?.windowId
            context.startActivity(Intent().setComponent(ComponentName("local.voice.fixture", "local.voice.fixture.FieldActivity"))
                .putExtra("mode", mode).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            automation.waitForIdle(200, 5000)
            val until = System.currentTimeMillis() + 5000
            var previous: Pair<String?, PhoneAccessibility.FieldTarget?>? = null
            var stable = 0
            while (System.currentTimeMillis() < until) {
                var snapshot: Pair<String?, PhoneAccessibility.FieldTarget?>? = null
                instrumentation.runOnMainSync {
                    snapshot = PhoneAccessibility.connected?.let { it.readScreen() to it.fieldTarget() }
                }
                val root = automation.rootInActiveWindow
                val newWindow = root != null && root.windowId != oldWindow && root.packageName?.toString() == "local.voice.fixture"
                val matchesWindow = if (mode in setOf("multiple", "password")) snapshot?.second == null
                    else snapshot?.second?.window == root?.windowId
                if (newWindow && matchesWindow && snapshot != null && snapshot == previous && !snapshot!!.first.isNullOrBlank()) stable++ else stable = 0
                if (stable >= 3) return
                previous = snapshot
                Thread.sleep(100)
            }
            fail("External fixture accessibility tree did not settle before the command")
        }
        fun action(command: VoiceCommand): DeviceTools.Reply {
            var reply: DeviceTools.Reply? = null
            instrumentation.runOnMainSync { reply = tools!!.execute(command) }
            return reply!!
        }
        fun verify(reply: DeviceTools.Reply): String {
            assertNotNull("Expected verified action, got: ${reply.text}", reply.verify)
            val latch = CountDownLatch(1); var text = ""
            instrumentation.runOnMainSync { reply.verify!!.invoke { text = it; latch.countDown() } }
            assertTrue(latch.await(5, TimeUnit.SECONDS)); return text
        }
        fun click(label: String) {
            val node = automation.rootInActiveWindow.findAccessibilityNodeInfosByText(label).single { it.text?.toString()?.equals(label, true) == true }
            assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)); Thread.sleep(250)
        }
        try {
            settings(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, null)
            Thread.sleep(250)
            settings(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "local.voice/local.voice.PhoneAccessibility")
            settings(Settings.Secure.ACCESSIBILITY_ENABLED, "1")
            val until = System.currentTimeMillis() + 5000
            while (PhoneAccessibility.connected == null && System.currentTimeMillis() < until) Thread.sleep(100)
            assertNotNull("Assistant accessibility service did not connect", PhoneAccessibility.connected)
            tools = DeviceTools(context)
            launch("single")
            var scoped: DeviceTools.Reply? = null
            instrumentation.runOnMainSync { scoped = tools!!.execute(VoiceCommand.Write("Не должно записаться"), "wrong.application") }
            assertTrue(scoped!!.text.contains("Продолжение команды отменено"))
            assertTrue(action(VoiceCommand.Write("Буду через десять минут.")).text.contains("Заменить текст"))
            assertEquals("Черновик", automation.rootInActiveWindow.findAccessibilityNodeInfosByViewId("local.voice.fixture:id/message").single().text.toString())
            val before = PhoneAccessibility.connected?.fieldTarget()
            val confirmed = action(VoiceCommand.Confirm)
            assertNotNull(confirmed.verify)
            val verified = verify(confirmed)
            assertTrue("Result=$verified; before=$before; actual=${automation.rootInActiveWindow.findAccessibilityNodeInfosByViewId("local.voice.fixture:id/message").single().text}; target=${PhoneAccessibility.connected?.fieldTarget()}", verified.startsWith("Текст записан"))
            assertEquals("Буду через десять минут.", automation.rootInActiveWindow.findAccessibilityNodeInfosByViewId("local.voice.fixture:id/message").single().text.toString())
            assertNull(action(VoiceCommand.Confirm).verify) // consumed, cannot replay
            launch("single")
            action(VoiceCommand.Write("Не должно появиться")); click("Изменить черновик")
            assertTrue(verify(action(VoiceCommand.Confirm)).contains("изменились"))
            launch("single")
            action(VoiceCommand.Write("Другой адресат запрещён")); click("Другой адресат")
            assertTrue(verify(action(VoiceCommand.Confirm)).contains("изменились"))
            launch("editable_recipient")
            assertTrue(action(VoiceCommand.Write("Адресат в поле не должен меняться")).text.contains("Заменить"))
            click("Другой адресат")
            assertEquals("Другой контакт", automation.rootInActiveWindow.findAccessibilityNodeInfosByViewId("local.voice.fixture:id/other").single().text.toString())
            val changedRecipient = verify(action(VoiceCommand.Confirm))
            assertTrue("Editable recipient change was ignored: $changedRecipient", changedRecipient.contains("изменились"))
            assertEquals("Черновик", automation.rootInActiveWindow.findAccessibilityNodeInfosByViewId("local.voice.fixture:id/message").single().text.toString())
            launch("editable_recipient")
            action(VoiceCommand.Write("Верный адресат"))
            assertTrue(verify(action(VoiceCommand.Confirm)).startsWith("Текст записан"))
            assertEquals("Верный адресат", automation.rootInActiveWindow.findAccessibilityNodeInfosByViewId("local.voice.fixture:id/message").single().text.toString())
            assertEquals("Тестовый контакт", automation.rootInActiveWindow.findAccessibilityNodeInfosByViewId("local.voice.fixture:id/other").single().text.toString())
            launch("noid")
            action(VoiceCommand.Write("Строка один\nСтрока два"))
            assertTrue(verify(action(VoiceCommand.Confirm)).startsWith("Текст записан"))
            assertEquals("Строка один\nСтрока два", automation.rootInActiveWindow.findAccessibilityNodeInfosByText("Строка один").single { it.isEditable }.text.toString())
            launch("replace")
            action(VoiceCommand.Write("Другой элемент"))
            assertTrue(verify(action(VoiceCommand.Confirm)).startsWith("Не удалось подтвердить"))
            launch("single")
            assertTrue(action(VoiceCommand.Tap("Изменить черновик")).text.contains("подтверждаю"))
            click("Другой адресат")
            assertTrue(action(VoiceCommand.Confirm).text.contains("отменено"))
            assertEquals("Черновик", automation.rootInActiveWindow.findAccessibilityNodeInfosByViewId("local.voice.fixture:id/message").single().text.toString())
            launch("single")
            action(VoiceCommand.Tap("Изменить черновик"))
            assertTrue(action(VoiceCommand.Confirm).text.contains("принята"))
            Thread.sleep(200)
            assertEquals("Изменено другим пользователем", automation.rootInActiveWindow.findAccessibilityNodeInfosByViewId("local.voice.fixture:id/message").single().text.toString())
            launch("focused")
            assertTrue(action(VoiceCommand.Write("Выбранное поле")).text.contains("Заменить"))
            assertTrue(verify(action(VoiceCommand.Confirm)).startsWith("Текст записан"))
            val untouched = automation.rootInActiveWindow.findAccessibilityNodeInfosByViewId("local.voice.fixture:id/other").single()
            assertTrue(untouched.text.isNullOrEmpty() || untouched.isShowingHintText)
            launch("multiple")
            assertTrue(action(VoiceCommand.Write("Неоднозначно")).text.contains("однозначное поле"))
            launch("password")
            assertTrue(action(VoiceCommand.Write("Секрет")).text.contains("однозначное поле"))
            launch("reject")
            action(VoiceCommand.Write("Приложение притворяется"))
            assertTrue(verify(action(VoiceCommand.Confirm)).startsWith("Не удалось подтвердить"))
            assertEquals("Черновик", automation.rootInActiveWindow.findAccessibilityNodeInfosByViewId("local.voice.fixture:id/message").single().text.toString())
            launch("single")
            action(VoiceCommand.Write("Отменяем")); action(VoiceCommand.Cancel)
            assertNull(action(VoiceCommand.Confirm).verify)
            launch("single")
            action(VoiceCommand.Write("Отмена отложенного ввода"))
            val deferred = action(VoiceCommand.Confirm)
            val cancelled = CountDownLatch(1)
            var cancelledReceipt: String? = null
            instrumentation.runOnMainSync {
                deferred.verify!!.invoke { cancelledReceipt = it; cancelled.countDown() }
                tools!!.execute(VoiceCommand.Cancel)
            }
            assertTrue(cancelled.await(5, TimeUnit.SECONDS))
            assertEquals("Ввод отменён.", cancelledReceipt)
            assertEquals("Черновик", automation.rootInActiveWindow.findAccessibilityNodeInfosByViewId("local.voice.fixture:id/message").single().text.toString())
            // The real foreground conversation must route confirmation through the
            // asynchronous verification before it speaks success and listens again.
            launch("single")
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            automation.waitForIdle(200, 5000)
            val listening = List(7) { CountDownLatch(1) }
            var count = 0
            var offeredField: PhoneAccessibility.FieldTarget? = null
            val voiceStatuses = java.util.concurrent.CopyOnWriteArrayList<String>()
            var verifiedSpoken = false
            var conversation: VoiceConversation? = null
            instrumentation.runOnMainSync {
                conversation = VoiceConversation(context, { status ->
                    voiceStatuses.add(status)
                    if (status.startsWith("Заменить текст")) offeredField = PhoneAccessibility.connected?.fieldTarget()
                    if (status == "Слушаю…" && count < listening.size) listening[count++].countDown()
                    if (status.startsWith("Текст записан")) verifiedSpoken = true
                }, {}, requireHeadset = false).also { it.start() }
            }
            try {
                assertTrue(listening[0].await(30, TimeUnit.SECONDS))
                instrumentation.runOnMainSync { conversation!!.respond("Запусти приложение Ассистент: тест полей и впиши сообщение Буду скоро") }
                val openingCompleted = listening[1].await(30, TimeUnit.SECONDS)
                assertTrue("Opening/input turn did not finish: $voiceStatuses; phase=${VoiceConversation.activePhase}; computing=${LocalBrain.computing}; stats=${LocalBrain.lastStats}; failure=${LocalBrain.lastFailure}", openingCompleted)
                assertEquals("Opening did not reach the requested fixture: $voiceStatuses", "local.voice.fixture", automation.rootInActiveWindow?.packageName?.toString())
                assertTrue("No input confirmation: $voiceStatuses", voiceStatuses.any { it.startsWith("Заменить текст") })
                assertEquals("Черновик", automation.rootInActiveWindow.findAccessibilityNodeInfosByViewId("local.voice.fixture:id/message").single().text.toString())
                instrumentation.runOnMainSync { conversation!!.respond("подтверждаю") }
                assertTrue(listening[2].await(30, TimeUnit.SECONDS))
                assertTrue("Conversation did not speak the verified result: $voiceStatuses; offered=$offeredField; current=${PhoneAccessibility.connected?.fieldTarget()}", verifiedSpoken)
                assertEquals("Буду скоро", automation.rootInActiveWindow.findAccessibilityNodeInfosByViewId("local.voice.fixture:id/message").single().text.toString())
                instrumentation.runOnMainSync { conversation!!.respond("открой Ассистент: тест полей затем напиши Отменяем") }
                assertTrue(listening[3].await(30, TimeUnit.SECONDS))
                val beforeCancel = automation.rootInActiveWindow.findAccessibilityNodeInfosByViewId("local.voice.fixture:id/message").single().text.toString()
                instrumentation.runOnMainSync { conversation!!.respond("отмена") }
                assertTrue(listening[4].await(30, TimeUnit.SECONDS))
                assertEquals(beforeCancel, automation.rootInActiveWindow.findAccessibilityNodeInfosByViewId("local.voice.fixture:id/message").single().text.toString())
                val beforeMissing = voiceStatuses.size
                instrumentation.runOnMainSync { conversation!!.respond("открой Несуществующее приложение xyz затем напиши Запрещено") }
                assertTrue(listening[5].await(30, TimeUnit.SECONDS))
                assertTrue(voiceStatuses.drop(beforeMissing).any { it.startsWith("Не нашёл приложение") })
                assertFalse(voiceStatuses.drop(beforeMissing).any { it.startsWith("Заменить текст") })
                instrumentation.runOnMainSync { conversation!!.respond("подтверждаю") }
                assertTrue(listening[6].await(30, TimeUnit.SECONDS))
                assertTrue(voiceStatuses.any { it.startsWith("Нет действия для подтверждения") })
                assertEquals(beforeCancel, automation.rootInActiveWindow.findAccessibilityNodeInfosByViewId("local.voice.fixture:id/message").single().text.toString())
            } finally { instrumentation.runOnMainSync { conversation?.close() } }
        } finally {
            tools?.clear()
            automation.executeShellCommand("am force-stop local.voice.fixture").use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
            settings(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, oldServices)
            settings(Settings.Secure.ACCESSIBILITY_ENABLED, oldEnabled.toString())
        }
    }
    private var tools: DeviceTools? = null
}
