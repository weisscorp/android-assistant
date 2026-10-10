package local.voice

import android.Manifest
import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class HomeEventsTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun fixture(block: (String) -> Unit) {
        val prefs = context.getSharedPreferences("settings", 0)
        HomeLink.detach(); prefs.edit().putBoolean("home_link_enabled", false).commit(); HomeLink.kick(context)
        BackendClient.save(context, "https://127.0.0.1:9", "synthetic-fixture-key-000000000000000000000")
        prefs.edit().putBoolean("home_link_enabled", true).commit()
        try { block(HomeLink.device(context)) }
        finally { prefs.edit().putBoolean("home_link_enabled", false).commit(); HomeLink.kick(context); HomeLink.detach() }
    }
    @Test fun offlineOutgoingIsDurableBoundedAndHasNoExecutablePayload() = fixture { _ ->
        val queue = HomeEvents(context)
        val id = queue.enqueue("$(literal) событие с https://example.test", "desktop", schedule = false)
        assertEquals(UUID.fromString(id).toString(), id)
        assertEquals(1L, HomeEvents(context).pendingCount())
        for (n in 1..127) queue.enqueue("fixture $n", schedule = false)
        assertEquals(128L, queue.pendingCount())
        assertThrows(IllegalStateException::class.java) { queue.enqueue("overflow", schedule = false) }
        assertThrows(IllegalArgumentException::class.java) { queue.enqueue("", schedule = false) }
        queue.retire(HomeLink.device(context))
        assertEquals(0L, queue.pendingCount())
    }
    @Test fun importantMessageUsesSilentHighChannelAndReplayDoesNotDuplicate() = fixture { device ->
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        context.getSharedPreferences("settings", 0).edit().putBoolean("home_popup_notifications", true).commit()
        val id = UUID.randomUUID().toString()
        val entry = JSONObject().put("id", id).put("seq", 1L).put("kind", "message").put("source", "fixture")
            .put("title", "Синтетический тест").put("body", "Буквальные данные; команды не выполняются.")
            .put("priority", "important").put("createdAt", System.currentTimeMillis())
        val inbox = HomeInbox(context); inbox.accept(device, JSONArray().put(entry)); inbox.notifyPending(device)
        HomeInbox(context).accept(device, JSONArray().put(entry)); HomeInbox(context).notifyPending(device)
        val manager = context.getSystemService(NotificationManager::class.java)
        val notifications = manager.activeNotifications.filter { it.tag == "home-$device-$id" }
        assertEquals(1, notifications.size)
        val channel = manager.getNotificationChannel(notifications.single().notification.channelId)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertNull(channel.sound); assertFalse(channel.shouldVibrate())
        assertEquals(1, inbox.entries(device).count { it.id == id })
        manager.cancel("home-$device-$id", 0)
    }
}
