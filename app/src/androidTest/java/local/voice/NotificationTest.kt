package local.voice

import android.content.ComponentName
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationTest {
    @Test fun realExternalNotificationsPermissionRevocationAndMessagingStyle() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.uiAutomation
        val component = "${context.packageName}/local.voice.PhoneNotifications"
        val previous = PhoneNotifications.hasAccess(context)
        fun shell(command: String) { automation.executeShellCommand(command).use { parcel -> java.io.FileInputStream(parcel.fileDescriptor).use { it.readBytes() } } }
        fun launch(mode: String = "post") {
            context.startActivity(Intent().setComponent(ComponentName("local.voice.fixture", "local.voice.fixture.NotificationActivity"))
                .putExtra("mode", mode).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        fun waitFor(test: () -> Boolean) { val until = System.currentTimeMillis() + 10000
            while (System.currentTimeMillis() < until) { if (test()) return; Thread.sleep(100) }; fail("Notification condition timed out") }
        try {
            shell("cmd notification disallow_listener $component")
            waitFor { !PhoneNotifications.hasAccess(context) }
            assertTrue(PhoneNotifications.read(context, false).contains("разрешите"))
            shell("pm grant local.voice.fixture android.permission.POST_NOTIFICATIONS")
            shell("cmd notification allow_listener $component")
            launch()
            waitFor {
                val ready = PhoneNotifications.read(context, false)
                listOf("PUBLIC_FIXTURE", "PRIVATE_FIXTURE", "LATEST_MESSAGE_FIXTURE").all(ready::contains)
            }
            val result = PhoneNotifications.read(context, false)
            assertTrue(result, result.contains("PRIVATE_FIXTURE"))
            assertTrue(result, result.contains("LATEST_MESSAGE_FIXTURE"))
            for (hidden in listOf("SECRET_FIXTURE", "ONGOING_FIXTURE", "OLD_MESSAGE_FIXTURE")) assertFalse(result, result.contains(hidden))
            val statuses = java.util.concurrent.CopyOnWriteArrayList<String>()
            val first = java.util.concurrent.CountDownLatch(1)
            val resumed = java.util.concurrent.CountDownLatch(1)
            var conversation: VoiceConversation? = null
            var listens = 0
            instrumentation.runOnMainSync {
                conversation = VoiceConversation(context, { status ->
                    statuses.add(status)
                    if (status == "Слушаю…") { listens++; if (listens == 1) first.countDown() else resumed.countDown() }
                }, {}, requireHeadset = false).also { it.start() }
            }
            try {
                assertTrue("Voice did not become ready", first.await(30, java.util.concurrent.TimeUnit.SECONDS))
                instrumentation.runOnMainSync { conversation!!.respond("Озвучь мои последние уведомления.") }
                assertTrue("Voice did not resume after notifications", resumed.await(40, java.util.concurrent.TimeUnit.SECONDS))
                assertTrue(statuses.contains("Уведомления"))
                assertFalse("Notification body leaked into voice UI", statuses.any { it.contains("FIXTURE") })
            } finally { instrumentation.runOnMainSync { conversation?.close() } }
            launch("clear")
            waitFor { !PhoneNotifications.read(context, false).contains("FIXTURE") }
            shell("cmd notification disallow_listener $component")
            waitFor { !PhoneNotifications.hasAccess(context) }
            assertTrue(PhoneNotifications.read(context, false).contains("разрешите"))
        } finally {
            launch("clear")
            shell("cmd notification ${if (previous) "allow_listener" else "disallow_listener"} $component")
        }
    }
}
