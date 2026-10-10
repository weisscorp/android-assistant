package local.voice

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/** Actual HTTPS + Keystore + REST over adb reverse to a temporary TLS fixture.
 * Only the test process temporarily trusts that exact fixture CA. Host verification stays enabled. */
@RunWith(AndroidJUnit4::class)
class ServerJobsTest {
    private fun fixture(test: (Context, String) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val endpoint = InstrumentationRegistry.getArguments().getString("backend_endpoint") ?: error("Run through tools/verify_emulator.py")
        val preferences = context.getSharedPreferences("settings", 0)
        val original = preferences.all.toMap()
        val defaultTls = HttpsURLConnection.getDefaultSSLSocketFactory()
        try {
            val certificate = File(context.filesDir, "test-server-ca.pem").inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
            val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("fixture", certificate) }
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            val tls = SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }
            preferences.edit().remove("server_job").remove("server_job_endpoint").commit()
            BackendClient.save(context, endpoint, "helmet-test-token")
            assertNotEquals("helmet-test-token", preferences.getString("token", null))
            assertTrue("Production TLS must reject an unknown fixture CA", runCatching {
                BackendClient.exchange(context, "GET", "/jobs/${UUID.randomUUID()}", null, BackendClient.Request())
            }.isFailure)
            HttpsURLConnection.setDefaultSSLSocketFactory(tls.socketFactory)
            test(context, endpoint)
        } finally {
            HttpsURLConnection.setDefaultSSLSocketFactory(defaultTls)
            val restore = preferences.edit().clear()
            original.forEach { (key, value) -> when (value) {
                is String -> restore.putString(key, value)
                is Boolean -> restore.putBoolean(key, value)
                is Int -> restore.putInt(key, value)
                is Long -> restore.putLong(key, value)
                is Float -> restore.putFloat(key, value)
                is Set<*> -> restore.putStringSet(key, value.filterIsInstance<String>().toSet())
            } }
            assertTrue(restore.commit())
        }
    }
    @Test fun httpsQueueStatusCancelUnknownRequestAndRestartedClient() = fixture { context, endpoint ->
        val preferences = context.getSharedPreferences("settings", 0)
        fun command(value: VoiceCommand) = ServerJobs.perform(context, value, BackendClient.Request())
        assertTrue(command(VoiceCommand.Server("долгая задача проверки")).contains("принята"))
        val id = preferences.getString("server_job", null)!!
        assertTrue(command(VoiceCommand.ServerStatus).contains("выполняет"))
        assertTrue(command(VoiceCommand.Server("не создавать вторую задачу")).contains("выполняет"))
        assertEquals(id, preferences.getString("server_job", null))
        assertTrue(command(VoiceCommand.ServerCancel).let { it.contains("Отмена") || it.contains("отменена") })
        val until = System.currentTimeMillis() + 4000
        var status = command(VoiceCommand.ServerStatus)
        while (!status.contains("отменена") && System.currentTimeMillis() < until) { Thread.sleep(100); status = command(VoiceCommand.ServerStatus) }
        assertTrue(status, status.contains("отменена"))
        val text = "Привет $(буквально); `без shell`"
        assertTrue(command(VoiceCommand.Server(text)).contains("принята"))
        Thread.sleep(500)
        status = command(VoiceCommand.ServerStatus)
        assertTrue(status, status.contains(text))
        // The result is recovered using a stored ID, not a long-lived in-memory request object.
        assertEquals(VoiceCommands.parse("статус сервера"), VoiceCommand.ServerStatus)
        val unknown = UUID.randomUUID().toString()
        preferences.edit().putString("server_job", unknown).putString("server_job_endpoint", endpoint).commit()
        assertTrue(command(VoiceCommand.Server("не дублировать неизвестный запрос")).contains("не подтверждён"))
        assertEquals(unknown, preferences.getString("server_job", null))
        assertTrue(command(VoiceCommand.ServerCancel).contains("отменена"))
        val delayed = BackendClient.exchange(context, "POST", "/jobs", JSONObject().put("id", unknown).put("text", "запоздавший запрос"), BackendClient.Request())
        assertEquals(202, delayed.first)
        assertEquals("cancelled", delayed.second.getString("state"))
        assertFalse("Transcripts must not be stored in settings", preferences.all.values.any { it.toString().contains(text) })
        // A voice-session stop must release local audio while keeping accepted server work.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val listening = java.util.concurrent.CountDownLatch(1)
        val accepted = java.util.concurrent.CountDownLatch(1)
        var conversation: VoiceConversation? = null
        instrumentation.runOnMainSync {
            conversation = VoiceConversation(context, { value ->
                if (value == "Слушаю…") listening.countDown()
                if (value.contains("Задача принята")) accepted.countDown()
            }, {}, requireHeadset = false).also { it.start() }
        }
        try {
            assertTrue(listening.await(30, java.util.concurrent.TimeUnit.SECONDS))
            instrumentation.runOnMainSync { conversation!!.respond("сервер долгая задача после завершения разговора") }
            assertTrue(accepted.await(15, java.util.concurrent.TimeUnit.SECONDS))
            instrumentation.runOnMainSync { VoiceConversation.stopActive() }
            Thread.sleep(300)
            assertTrue(context.getSystemService(android.media.AudioManager::class.java).activeRecordingConfigurations.isEmpty())
            assertTrue("Accepted server work was lost on voice-session stop", command(VoiceCommand.ServerStatus).contains("выполняет"))
            assertTrue(command(VoiceCommand.ServerCancel).let { it.contains("Отмена") || it.contains("отменена") })
        } finally { instrumentation.runOnMainSync { conversation?.close() } }

    }
    @Test fun missingCapabilityGoesToConversationWithoutStartingDevelopmentJob() = fixture { context, _ ->
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val replies = java.util.concurrent.LinkedBlockingQueue<String>()
        val utterance = "Установи на телефоне таймер на семь минут."
        val stats = LocalBrain.lastStats
        var conversation: VoiceConversation? = null
        instrumentation.runOnMainSync {
            conversation = VoiceConversation(context, {}, {}, textOnly = true,
                replyObserver = { replies.offer(it) }).also { it.start(); it.respond(utterance) }
        }
        try {
            assertTrue(replies.poll(3, java.util.concurrent.TimeUnit.SECONDS)!!.contains("Подключаю Hermes"))
            val answer = replies.poll(10, java.util.concurrent.TimeUnit.SECONDS)!!
            assertTrue(answer, answer.contains(utterance))
            assertEquals(stats, LocalBrain.lastStats)
            assertNull("Missing timer must not create a coding job", context.getSharedPreferences("settings", 0).getString("server_job", null))
            assertFalse(context.getSharedPreferences("settings", 0).all.values.any { it.toString().contains(utterance) })
        } finally { instrumentation.runOnMainSync { conversation?.close() } }
    }
    @Test fun tlsHostnameAuthorizationAndCancelledTransportAreEnforced() = fixture { context, endpoint ->
        val id = UUID.randomUUID().toString()
        assertTrue(runCatching { ServerJobs.decode(JSONObject().put("id", id).put("state", "succeeded").put("reply", 123), id) }.isFailure)
        assertTrue(runCatching { ServerJobs.decode(JSONObject().put("id", id).put("state", "unknown").put("reply", ""), id) }.isFailure)
        val cancelled = BackendClient.Request().also { it.cancel() }
        assertTrue(runCatching { BackendClient.exchange(context, "POST", "/jobs", JSONObject().put("id", id).put("text", "cancelled before send"), cancelled) }.isFailure)
        assertEquals(404, BackendClient.exchange(context, "GET", "/jobs/$id", null, BackendClient.Request()).first)
        BackendClient.save(context, endpoint, "wrong-token")
        assertEquals(401, BackendClient.exchange(context, "GET", "/jobs/$id", null, BackendClient.Request()).first)
        BackendClient.save(context, endpoint.replace("localhost", "127.0.0.1"), "helmet-test-token")
        assertTrue("A trusted CA does not authorize a different hostname", runCatching { BackendClient.exchange(context, "GET", "/jobs/$id", null, BackendClient.Request()) }.isFailure)
        assertTrue(runCatching { BackendClient.save(context, "http://localhost:1234", "helmet-test-token") }.isFailure)
    }
}
