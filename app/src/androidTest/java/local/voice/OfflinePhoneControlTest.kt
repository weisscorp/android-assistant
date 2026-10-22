package local.voice

import android.content.ComponentName
import android.content.Intent
import android.media.AudioManager
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Disposable emulator only. Synthetic text input, no microphone or sound checks. */
class OfflinePhoneControlTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation get() = instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private var previousServices: String? = null
    private var previousAccessibility = 0
    private fun setting(name: String, value: String?) {
        automation.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_SECURE_SETTINGS)
        try { assertTrue(Settings.Secure.putString(context.contentResolver, name, value)) }
        finally { automation.dropShellPermissionIdentity() }
    }
    @Before fun enableTestAccessibility() {
        previousServices = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        previousAccessibility = Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        setting(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "")
        // Let the system observe removal before re-enabling a service whose process
        // instrumentation may have replaced; coalesced writes can retain "crashed".
        Thread.sleep(300)
        setting(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "local.voice/local.voice.PhoneAccessibility")
        setting(Settings.Secure.ACCESSIBILITY_ENABLED, "1")
        waitFor("Accessibility service did not bind") { PhoneAccessibility.connected != null }
    }
    @After fun restoreTestAccessibility() {
        setting(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, previousServices)
        setting(Settings.Secure.ACCESSIBILITY_ENABLED, previousAccessibility.toString())
    }
    private fun waitFor(message: String, predicate: () -> Boolean) {
        val end = System.currentTimeMillis()+8000
        while (System.currentTimeMillis()<end) {
            var ok=false;instrumentation.runOnMainSync { ok=predicate() }
            if(ok)return
            Thread.sleep(40)
        }
        fail(message)
    }
    private fun fixture() {
        context.startActivity(Intent().setComponent(ComponentName("local.voice.fixture", "local.voice.fixture.FieldActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        automation.waitForIdle(200, 5000)
        var previous: PhoneAccessibility.ForegroundSnapshot? = null
        var stable = 0
        val until = System.currentTimeMillis() + 10000
        while (System.currentTimeMillis() < until) {
            var frame: PhoneAccessibility.ForegroundSnapshot? = null
            instrumentation.runOnMainSync { frame = PhoneAccessibility.connected?.foregroundSnapshot() }
            if (frame?.app == "local.voice.fixture" && frame == previous) stable++ else stable = 0
            if (stable >= 3) return
            previous = frame
            Thread.sleep(100)
        }
        fail("Fixture accessibility frame did not settle: $previous")
    }
    @Test fun clarificationAndControlsStayLocalAndPreserveListening() {
        fixture()
        assertNull("Test must run without a network", context.getSystemService(android.net.ConnectivityManager::class.java).activeNetwork)
        val prefs=context.getSharedPreferences("settings",0);val previous=prefs.getString("brain_mode",null)
        val audio=context.getSystemService(AudioManager::class.java);val volume=audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val replies=LinkedBlockingQueue<String>();var conversation:VoiceConversation?=null;var releases=0
        val stats=LocalBrain.lastStats
        try {
            prefs.edit().putString("brain_mode","LOCAL").commit()
            instrumentation.runOnMainSync {
                conversation=VoiceConversation(context,{}, {},textOnly=true,releaseScreen={releases++},replyObserver={replies.offer(it)}).also { it.start() }
            }
            fun ask(text:String):String {
                instrumentation.runOnMainSync { conversation!!.respond(text) }
                return replies.poll(3,TimeUnit.SECONDS) ?: error("No local reply for $text")
            }
            assertEquals("Какое приложение открыть?",ask("открой"))
            assertEquals(0,releases)
            assertTrue(ask("хшцщжхшцщж").startsWith("Не нашёл приложение"))
            assertEquals(0,releases)
            assertTrue(ask("измени громкость").contains("от нуля до ста"))
            assertTrue("Repeat lost the pending voice question",ask("повтори").contains("от нуля до ста"))
            val result=ask("пять")
            assertTrue(result,result.startsWith("Громкость музыки:"))
            assertTrue(ask("повтори").startsWith("Громкость музыки:"))
            assertTrue(ask("что ты услышал").contains("пять"))
            assertEquals("Какую кнопку нажать? Назовите надпись.",ask("нажми"))
            assertEquals("Отменено.",ask("отмена"))
            assertTrue(ask("команды телефона").contains("Без интернета"))
            assertEquals("Какой текст ввести?", ask("напиши"))
            val literal = ask("назад")
            assertTrue("A dictated control executed instead of being offered as text: $literal", literal.contains("назад") && literal.contains("Заменить текст"))
            ask("отмена")
            ask("автономный режим")
            assertEquals("LOCAL",prefs.getString("brain_mode",null))
            assertSame("Simple control called local LLM",stats,LocalBrain.lastStats)
            assertTrue(conversation!!.listening)
            assertEquals(0L,conversation!!.speechFrames)
            assertTrue(audio.activeRecordingConfigurations.isEmpty())
        } finally {
            instrumentation.runOnMainSync { conversation?.close();audio.setStreamVolume(AudioManager.STREAM_MUSIC,volume,0) }
            prefs.edit().apply { if(previous==null)remove("brain_mode") else putString("brain_mode",previous) }.commit()
        }
    }
    @Test fun numberedScreenButtonsRequireConfirmationAndRejectStaleFrames() {
        fixture();val tools=DeviceTools(context)
        instrumentation.runOnMainSync {
            val offered=tools.execute(VoiceCommand.Buttons)!!
            assertTrue(offered.text,offered.text.contains("1:"))
            val selected=tools.execute(VoiceCommand.Choose(0))!!
            assertTrue(selected.text,selected.actionPending)
            assertFalse(selected.actionAccepted)
            assertEquals("Отменено.",tools.execute(VoiceCommand.Cancel)!!.text)
            tools.execute(VoiceCommand.Buttons)
            tools.execute(VoiceCommand.Choose(0))
        }
        // Move to a different screen before confirming a captured target.
        context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        waitFor("New assistant window did not replace fixture") { automation.rootInActiveWindow?.packageName == context.packageName }
        instrumentation.runOnMainSync {
            val reply=tools.execute(VoiceCommand.Confirm)!!
            assertFalse(reply.actionAccepted)
            assertTrue(reply.text,reply.text.contains("изменился") || reply.text.contains("отменено"))
        }
        fixture()
        instrumentation.runOnMainSync {
            tools.execute(VoiceCommand.Buttons);tools.execute(VoiceCommand.Choose(0))
            assertTrue(tools.execute(VoiceCommand.Confirm)!!.actionAccepted)
        }
    }
    @Test fun realGlobalActionIsDeferredUntilAfterSpeech() {
        fixture();val control=PhoneControl(context)
        val reply=control.execute(PhoneRequest(PhoneAction.HOME))
        assertNotNull(reply.afterSpeech);assertTrue(reply.releaseScreen)
        assertEquals("local.voice.fixture",PhoneAccessibility.connected!!.foregroundPackage())
        instrumentation.runOnMainSync { assertNull(reply.afterSpeech!!.invoke()) }
        waitFor("Home action did not navigate") { automation.rootInActiveWindow?.packageName=="com.android.launcher3" }
        val shade=control.execute(PhoneRequest(PhoneAction.NOTIFICATIONS))
        instrumentation.runOnMainSync { assertNull(shade.afterSpeech!!.invoke()) }
        waitFor("Notification shade did not open") { automation.rootInActiveWindow?.packageName=="com.android.systemui" }
        waitFor("SystemUI screen was not available to voice controls") { PhoneAccessibility.connected?.foregroundSnapshot()?.app == "com.android.systemui" }
        instrumentation.runOnMainSync {
            val buttons = DeviceTools(context).execute(VoiceCommand.Buttons)!!
            assertTrue(buttons.text, buttons.text.contains("1:"))
        }
        val close=control.execute(PhoneRequest(PhoneAction.DISMISS_SHADE))
        instrumentation.runOnMainSync { assertNull(close.afterSpeech!!.invoke()) }
        assertTrue(context.getSystemService(AudioManager::class.java).activeRecordingConfigurations.isEmpty())
    }
    @Test fun brightnessReadbackAndPermissionGuardsWithRestoredSettings() {
        val resolver = context.contentResolver
        val before = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        val mode = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, 0)
        val ops = context.getSystemService(android.app.AppOpsManager::class.java)
        val oldOp = ops.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_WRITE_SETTINGS,
            android.os.Process.myUid(), context.packageName)
        fun permission(value: String) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(
                "appops set ${context.packageName} WRITE_SETTINGS $value")).use { it.readBytes() }
        }
        val restoreMode = when (oldOp) {
            android.app.AppOpsManager.MODE_ALLOWED -> "allow"
            android.app.AppOpsManager.MODE_IGNORED -> "ignore"
            android.app.AppOpsManager.MODE_ERRORED -> "deny"
            android.app.AppOpsManager.MODE_DEFAULT -> "default"
            android.app.AppOpsManager.MODE_FOREGROUND -> "foreground"
            else -> error("Unexpected app-op mode")
        }
        try {
            permission("ignore")
            assertFalse(Settings.System.canWrite(context))
            val denied = PhoneControl(context).execute(PhoneRequest(PhoneAction.BRIGHTNESS, 60))
            assertTrue(denied.text, denied.text.contains("разрешите"))
            assertEquals(before, Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS))
            permission("allow")
            assertTrue(Settings.System.canWrite(context))
            val applied = PhoneControl(context).execute(PhoneRequest(PhoneAction.BRIGHTNESS, 60))
            assertTrue(applied.text, applied.text.contains("60 процентов"))
            assertEquals(153, Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS))
            assertEquals(Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE))
            assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED,
                context.checkSelfPermission(android.Manifest.permission.CAMERA))
            val torch = PhoneControl(context).execute(PhoneRequest(PhoneAction.TORCH_ON))
            assertTrue(torch.text, torch.text.contains("разрешите"))
            assertNull(torch.verify)
        } finally {
            permission("allow")
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, before)
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, mode)
            permission(restoreMode)
        }
    }
    @Test fun missingSpecialPermissionDoesNotPretendBrightnessChanged() {
        if(Settings.System.canWrite(context))return // Existing emulator can have a previous explicit grant.
        val reply=PhoneControl(context).execute(PhoneRequest(PhoneAction.BRIGHTNESS,50))
        assertTrue(reply.text,reply.text.contains("разрешите"))
        assertNull(reply.afterSpeech);assertNull(reply.verify)
    }
}
