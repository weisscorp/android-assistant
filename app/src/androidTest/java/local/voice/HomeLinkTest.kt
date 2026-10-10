package local.voice

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.CountDownLatch
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

@RunWith(AndroidJUnit4::class)
class HomeLinkTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun prefs() = context.getSharedPreferences("settings", 0)
    private fun fixture(block: () -> Unit) {
        val original = prefs().all.toMap()
        val tls = HttpsURLConnection.getDefaultSSLSocketFactory()
        HomeLink.detach()
        try {
            prefs().edit().putBoolean("home_link_enabled", false).commit()
            val endpoint = InstrumentationRegistry.getArguments().getString("backend_endpoint") ?: error("Use verify_emulator.py")
            val certificate = File(context.filesDir, "test-server-ca.pem").inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("fixture", certificate) }
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            HttpsURLConnection.setDefaultSSLSocketFactory(SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }.socketFactory)
            BackendClient.save(context, endpoint, "helmet-test-token")
            block()
        } finally {
            prefs().edit().putBoolean("home_link_enabled", false).commit(); HomeLink.kick(context); HomeLink.detach()
            HttpsURLConnection.setDefaultSSLSocketFactory(tls)
            val edit = prefs().edit().clear()
            original.forEach { (key, value) -> when (value) {
                is String -> edit.putString(key, value); is Boolean -> edit.putBoolean(key, value)
                is Int -> edit.putInt(key, value); is Long -> edit.putLong(key, value); is Float -> edit.putFloat(key, value)
            } }
            check(edit.commit())
        }
    }
    private fun sync() {
        prefs().edit().putBoolean("home_link_enabled", true).commit()
        HomeLink.checkNow(context).get(15, TimeUnit.SECONDS)
    }
    private fun post(path: String, body: JSONObject): JSONObject {
        val (code, json) = BackendClient.link(context, "POST", path, body.toString().toByteArray(), BackendClient.Request())
        assertEquals(json.toString(), 200, code); return json
    }
    private fun event(kind: String = "message") = JSONObject().put("id", UUID.randomUUID().toString()).put("kind", kind)
        .put("source", "Slack fixture").put("title", "Важное сообщение").put("body", "Пришло письмо. Проверь входящие.")
        .put("priority", "important").put("createdAt", System.currentTimeMillis())

    @Test fun tlsSyncTransfersKnowledgeImagesAliasesAndDeletionsWithStableRevision() = fixture {
        val store = SavedSources(context)
        val source = SavedSource(name = "Знание " + UUID.randomUUID().toString().take(8), kind = SourceKind.NOTE, value = "PRIVATE_FIXTURE_NOTE", aliases = listOf("синктест"))
        val image = SavedSource(name = "Картинка " + UUID.randomUUID().toString().take(8), kind = SourceKind.IMAGE, value = "Картинка")
        store.image(image).parentFile!!.mkdirs()
        store.image(image).writeBytes(byteArrayOf(1, 2, 3, 4))
        try {
            store.save(source); store.save(image)
            sync()
            val device = HomeLink.device(context)
            val (code, remote) = BackendClient.link(context, "GET", "/link/$device/knowledge", null, BackendClient.Request())
            assertEquals(200, code)
            val values = remote.getJSONArray("sources")
            val knowledge = (0 until values.length()).map(values::getJSONObject)
            assertEquals(source.value, knowledge.single { it.getString("id") == source.id }.getString("value"))
            assertEquals("синктест", knowledge.single { it.getString("id") == source.id }.getJSONArray("aliases").getString(0))
            assertEquals(HomeKnowledge.sha(byteArrayOf(1, 2, 3, 4)), knowledge.single { it.getString("id") == image.id }.getString("imageSha256"))
            val revision = remote.getLong("revision")
            sync(); assertEquals(revision, HomeKnowledge.prepare(context, device).first.getLong("revision"))
            prefs().edit().putBoolean("home_link_enabled", false).commit()
            store.delete(source.id); store.delete(image.id)
            sync()
            val after = BackendClient.link(context, "GET", "/link/$device/knowledge", null, BackendClient.Request()).second
            assertTrue(after.getLong("revision") > revision)
            assertFalse(after.getJSONArray("sources").toString().contains(source.id))
        } finally { prefs().edit().putBoolean("home_link_enabled", false).commit(); store.delete(source.id); store.delete(image.id) }
    }

    @Test fun eventsPersistReplayOnceAreQuietAndUpdateCannotInstallOrCaptureAudio() = fixture {
        sync()
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val device = HomeLink.device(context); val root = "/link/$device"
        val value = event("update").put("body", "Доступна новая версия. Скажите проверь обновления, когда удобно.")
        val updatePrefs = context.getSharedPreferences("updates", 0).all.toMap()
        val result = post("$root/events", value)
        post("$root/events", value) // Real server retry, same event UUID.
        sync(); sync()
        val inbox = HomeInbox(context)
        assertEquals(1, inbox.entries().count { it.id == value.getString("id") })
        assertEquals(result.getLong("seq"), inbox.cursor(device))
        val batch = BackendClient.link(context, "GET", "$root/events?after=0&wait=0", null, BackendClient.Request()).second.getJSONArray("events")
        inbox.accept(device, batch); inbox.notifyPending(device)
        val notifications = context.getSystemService(NotificationManager::class.java).activeNotifications.filter { it.tag == "home-$device-${value.getString("id")}" }
        assertEquals(1, notifications.size)
        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(notifications.single().notification.channelId)
        assertNull(channel.sound); assertFalse(channel.shouldVibrate()); assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertTrue(context.getSystemService(AudioManager::class.java).activeRecordingConfigurations.isEmpty())
        assertEquals(updatePrefs, context.getSharedPreferences("updates", 0).all)
        assertTrue(BackendClient.link(context, "GET", "$root/events?after=${inbox.cursor(device)}&wait=0", null, BackendClient.Request()).second.getJSONArray("events").length() == 0)
    }

    @Test fun voiceReadsStoredInboxWithoutLlm() = messagesAndVoice(false)
    internal fun messagesAndVoice(permissionDenied: Boolean) = fixture {
        if (permissionDenied) assertEquals(android.content.pm.PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        sync()
        val device = HomeLink.device(context)
        val value = event()
        post("/link/$device/events", value); sync()
        assertTrue(HomeInbox(context).entries().any { it.id == value.getString("id") })
        if (permissionDenied) assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.none { it.tag == "home-$device-${value.getString("id")}" })
        prefs().edit().putBoolean("home_read_when_locked", true).commit()
        val replies = LinkedBlockingQueue<String>()
        val stats = LocalBrain.lastStats
        var conversation: VoiceConversation? = null
        try {
            instrumentation.runOnMainSync {
                conversation = VoiceConversation(context, {}, {}, textOnly = true, replyObserver = { replies.offer(it) })
                    .also { it.start(); it.respond("прочитай сообщения от Гермеса") }
            }
            val reply = replies.poll(3, TimeUnit.SECONDS)
            assertNotNull(reply); assertTrue(reply!!, reply.contains(value.getString("body")))
            assertSame(stats, LocalBrain.lastStats)
            assertEquals(0L, conversation!!.speechFrames)
            assertTrue(context.getSystemService(AudioManager::class.java).activeRecordingConfigurations.isEmpty())
        } finally { instrumentation.runOnMainSync { conversation?.close() } }
    }

    @Test fun malformedBatchDoesNotAdvanceCursorOrExecuteAnythingAndProfileChangeIsolated() = fixture {
        sync(); val device = HomeLink.device(context); val inbox = HomeInbox(context)
        val before = inbox.cursor(device)
        val bad = JSONArray().put(event().put("seq", before+1)).put(event().put("seq", before+2).put("body", "x".repeat(4001)))
        assertTrue(runCatching { inbox.accept(device, bad) }.isFailure)
        assertEquals(before, inbox.cursor(device))
        val value = event(); post("/link/$device/events", value); sync()
        prefs().edit().putBoolean("home_link_enabled", false).commit()
        HomeLink.changedServer(context)
        assertNotEquals(device, HomeLink.device(context)); assertTrue(HomeInbox(context).entries().isEmpty())
        assertTrue(inbox.entries(device).any { it.id == value.getString("id") })
    }

    @Test fun storedMessageActuallySpeaksAndReturnsToListening() = fixture {
        sync()
        val value = event().put("source", "Hermes").put("title", "Важное письмо").put("body", "Письмо пришло. Проверь почту.")
        post("/link/${HomeLink.device(context)}/events", value); sync()
        prefs().edit().putBoolean("home_read_when_locked", true).commit()
        val first = CountDownLatch(1); val returned = CountDownLatch(1)
        val replies = java.util.concurrent.CopyOnWriteArrayList<String>()
        var listening = 0; var frames = 0L
        var conversation: VoiceConversation? = null
        instrumentation.runOnMainSync {
            conversation = VoiceConversation(context, { if (it == "Слушаю…") {
                if (++listening == 1) first.countDown() else returned.countDown()
            } }, {}, phoneMicrophone = true, replyObserver = { replies.add(it) }).also { it.start() }
        }
        try {
            assertTrue(first.await(30, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { conversation!!.respond("прочитай сообщения от Гермеса") }
            val until = System.currentTimeMillis() + 45000
            while (returned.count > 0 && System.currentTimeMillis() < until) {
                frames = maxOf(frames, conversation!!.speechFrames); Thread.sleep(25)
            }
            assertEquals("Reply=$replies", 0L, returned.count)
            assertTrue(replies.single(), replies.single().contains("Письмо пришло"))
            assertTrue("No actual synthesized PCM played", frames > 16000)
        } finally { instrumentation.runOnMainSync { conversation?.close() } }
        val audio = context.getSystemService(AudioManager::class.java)
        val until = System.currentTimeMillis() + 3000
        while (audio.activeRecordingConfigurations.isNotEmpty() && System.currentTimeMillis() < until) Thread.sleep(25)
        assertTrue(audio.activeRecordingConfigurations.isEmpty())
        File(context.cacheDir, "home-link-voice-proof.json").writeText(JSONObject().put("peakPlaybackFrames", frames)
            .put("reply", JSONArray(replies)).put("listeningCount", listening).put("microphoneReleased", true).toString())
    }
}

@RunWith(AndroidJUnit4::class)
class HomeInboxDeniedTest {
    @Test fun noNotificationPermissionStillRetainsIncomingMessageAndVoiceReadsItWithoutLlm() = HomeLinkTest().messagesAndVoice(true)
}
