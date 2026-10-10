package local.voice

import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Intent
import android.media.AudioManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** Explicit owned-AVD-only PIN test; never part of ordinary microphone instrumentation. */
@RunWith(AndroidJUnit4::class)
class NotificationPrivacyTest {
    @Test fun lockDuringPlaybackStopsOnlyProtectedContentAndPreferenceRevocationStopsIt() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes().toString(Charsets.UTF_8) }
        }
        assertEquals("Run through the owned-AVD verifier", "true", InstrumentationRegistry.getArguments().getString("owned_lock_test"))
        assertEquals("1", shell("getprop ro.kernel.qemu").trim())
        assertTrue("Existing PIN refused", shell("locksettings verify").contains("verified successfully"))
        val preferences = context.getSharedPreferences("settings", 0)
        val hadPreference = preferences.contains("notifications_when_locked")
        val previousPreference = preferences.getBoolean("notifications_when_locked", false)
        val access = PhoneNotifications.hasAccess(context)
        val component = "local.voice/local.voice.PhoneNotifications"
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val audio = context.getSystemService(AudioManager::class.java)
        var pinSet = false
        fun waitFor(message: String, timeout: Long = 10000, predicate: () -> Boolean) {
            val until = System.currentTimeMillis() + timeout
            while (System.currentTimeMillis() < until) { if (predicate()) return; Thread.sleep(50) }
            fail(message)
        }
        fun unlock() {
            if (pinSet) { assertTrue(shell("locksettings clear --old 1234").contains("cleared")); pinSet = false }
            shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard")
            waitFor("Keyguard stayed locked") { !keyguard.isDeviceLocked }
        }
        fun fixture(mode: String) {
            context.startActivity(Intent().setComponent(ComponentName("local.voice.fixture", "local.voice.fixture.NotificationActivity"))
                .putExtra("mode", mode).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        try {
            shell("pm grant local.voice.fixture android.permission.POST_NOTIFICATIONS")
            shell("cmd notification allow_listener $component")
            for ((mode, allow) in listOf("private-long" to false, "public-long" to false, "private-long" to true)) {
                unlock(); preferences.edit().putBoolean("notifications_when_locked", allow).commit()
                fixture(mode)
                waitFor("Synthetic notification unavailable") { PhoneNotifications.read(context, true).contains("синтетическое") }
                val statuses = CopyOnWriteArrayList<String>()
                val framesAtDenial = AtomicLong(-1)
                var conversation: VoiceConversation? = null
                instrumentation.runOnMainSync {
                    conversation = VoiceConversation(context, { value ->
                        if (value == "Телефон заблокирован. Чтение личных уведомлений остановлено.") framesAtDenial.set(conversation!!.speechFrames)
                        statuses.add(value)
                    }, {}, requireHeadset = false).also { it.start() }
                }
                try {
                    waitFor("Voice did not start", 30000) { statuses.contains("Слушаю…") }
                    instrumentation.runOnMainSync { conversation!!.respond("прочитай последнее уведомление") }
                    waitFor("Notification TTS never played PCM", 30000) { statuses.contains("Уведомления") && conversation!!.speechFrames > 4000 }
                    assertTrue(shell("locksettings set-pin 1234").contains("set to")); pinSet = true
                    shell("input keyevent KEYCODE_SLEEP")
                    waitFor("PIN did not lock the device") { keyguard.isDeviceLocked }
                    if (mode == "private-long" && !allow) {
                        waitFor("Protected TTS did not stop after lock", 3000) { framesAtDenial.get() >= 0 }
                        assertEquals("Private AudioTrack was not released before notice", 0L, framesAtDenial.get())
                        waitFor("Voice did not resume after policy notice", 30000) { statuses.count { it == "Слушаю…" } >= 2 }
                    } else {
                        val before = conversation!!.speechFrames
                        Thread.sleep(800)
                        assertEquals(-1L, framesAtDenial.get())
                        assertTrue("Allowed audio was unnecessarily cut", conversation!!.speechFrames > before)
                        if (allow) {
                            preferences.edit().putBoolean("notifications_when_locked", false).commit()
                            waitFor("Revoking locked-reading preference did not stop TTS", 3000) { framesAtDenial.get() >= 0 }
                            assertEquals(0L, framesAtDenial.get())
                            waitFor("Voice did not resume after revocation", 30000) { statuses.count { it == "Слушаю…" } >= 2 }
                        }
                    }
                    assertFalse("Body leaked into UI status", statuses.any { it.contains("синтетическое") })
                } finally { instrumentation.runOnMainSync { conversation?.close() } }
                waitFor("Microphone remained after close") { audio.activeRecordingConfigurations.isEmpty() }
            }
        } finally {
            try { unlock() } finally { fixture("clear")
            shell("cmd notification ${if (access) "allow_listener" else "disallow_listener"} $component")
            val restore = preferences.edit()
            if (hadPreference) restore.putBoolean("notifications_when_locked", previousPreference) else restore.remove("notifications_when_locked")
            restore.commit()
            }

        }
    }
}
