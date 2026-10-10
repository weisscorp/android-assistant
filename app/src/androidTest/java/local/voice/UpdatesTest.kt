package local.voice

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.media.AudioManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/** Only selected explicitly by verify_ota.py; not part of the ordinary microphone test suite. */
@RunWith(AndroidJUnit4::class)
class UpdatesTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val endpoint get() = InstrumentationRegistry.getArguments().getString("backend_endpoint") ?: error("Run tools/verify_ota.py")
    private fun tls(test: () -> Unit) {
        val old = HttpsURLConnection.getDefaultSSLSocketFactory()
        val certificate = File(context.filesDir, "test-server-ca.pem").inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("fixture", certificate) }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }
        try { HttpsURLConnection.setDefaultSSLSocketFactory(ssl.socketFactory); test() }
        finally { HttpsURLConnection.setDefaultSSLSocketFactory(old) }
    }
    @Test fun rejectInvalidDownloadsAndChangedCacheBeforeInstall() = tls {
        val updates = context.getSharedPreferences("updates", 0)
        updates.edit().clear().commit()
        for (mode in listOf("bad-hash", "bad-version", "bad-identity", "wrong-signer", "bad-type", "redirect")) {
            BackendClient.save(context, "$endpoint/$mode", "helmet-test-token")
            assertTrue("Invalid OTA accepted: $mode", runCatching { Updates.download(context) }.isFailure)
            assertTrue(context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("ota-") })
            assertFalse(updates.contains("session"))
        }
        BackendClient.save(context, endpoint, "wrong-token")
        assertTrue(runCatching { Updates.download(context) }.isFailure)
        BackendClient.save(context, endpoint, "helmet-test-token")
        val cancelled = BackendClient.Request().also { it.cancel() }
        assertTrue(runCatching { Updates.download(context, cancelled) }.isFailure)
        val file = Updates.download(context)!!
        assertEquals("ready", updates.getString("state", ""))
        file.appendBytes(byteArrayOf(1))
        assertTrue("Cache changed after validation must not be installed", runCatching { Updates.install(context, file) }.isFailure)
        assertFalse(updates.contains("session"))
        file.delete(); updates.edit().clear().commit()
        val forged = Intent().putExtra(PackageInstaller.EXTRA_SESSION_ID, 999).putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_SUCCESS)
        Updates.result(context, forged)
        assertFalse(updates.contains("state"))
    }
    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    @Test fun commitRealSelfUpdate() = tls {
        BackendClient.save(context, endpoint, "helmet-test-token")
        context.getSharedPreferences("settings", 0).edit().putString("ota_test_preserved", "same data after replacement").commit()
        if (InstrumentationRegistry.getArguments().getString("speech_engine") == "tone") {
            assertTrue(context.getSharedPreferences("settings", 0).edit().putBoolean("tone_asr", true).commit())
        }
        val updates = context.getSharedPreferences("updates", 0)
        updates.edit().putInt("test_source_version", context.packageManager.getPackageInfo(context.packageName, 0).versionCode).commit()
        val model = LocalBrain.model(context)
        updates.edit().remove("test_model_sha256").apply {
            if (model.isFile) putString("test_model_sha256", hash(model))
        }.commit()
        updates.edit().putBoolean("test_phone_permission", CallGuard.hasAccess(context)).commit()
        val ready = CountDownLatch(1)
        val downloaded = AtomicReference(CountDownLatch(1))
        val cancelled = CountDownLatch(1)
        var conversation: VoiceConversation? = null
        instrumentation.runOnMainSync {
            conversation = VoiceConversation(context, { status ->
                if (status == "Слушаю…") ready.countDown()
                if (status.startsWith("Скачана и проверена")) downloaded.get().countDown()
                if (status == "Отменено.") cancelled.countDown()
            }, {}, requireHeadset = false).also { it.start() }
        }
        try {
            assertTrue(ready.await(30, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { conversation!!.respond("проверь обновления") }
            assertTrue(downloaded.get().await(60, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { conversation!!.respond("отмена") }
            assertTrue(cancelled.await(5, TimeUnit.SECONDS))
            assertFalse("Voice cancellation must not submit an installer session", updates.contains("session"))
            downloaded.set(CountDownLatch(1))
            instrumentation.runOnMainSync { conversation!!.respond("проверь обновления") }
            assertTrue(downloaded.get().await(60, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { conversation!!.respond("подтверждаю") }
            // The orchestrator observes replacement; this process can be killed by PackageInstaller.
            Thread.sleep(20000)
            assertFalse("Self-update needs confirmation on this emulator: ${Updates.message(context)}", updates.getString("state", "") == "confirmation")
        } finally { instrumentation.runOnMainSync { conversation?.close() } }

    }
    @Test fun newProcessPreservesDataKeystoreAndVoice() {
        val updates = context.getSharedPreferences("updates", 0)
        val installed = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        assertTrue(installed > updates.getInt("test_source_version", Int.MAX_VALUE))
        assertTrue("Assistant role lost after replacement", context.getSystemService(android.app.role.RoleManager::class.java).isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT))
        assertEquals("Phone-state grant changed during replacement", updates.getBoolean("test_phone_permission", false), CallGuard.hasAccess(context))
        updates.getString("test_model_sha256", null)?.let { expected ->
            assertTrue("Imported model lost after replacement", LocalBrain.model(context).isFile)
            assertEquals("Imported model changed during replacement", expected, hash(LocalBrain.model(context)))
            val completed = CountDownLatch(1)
            var proposal: LocalBrain.Proposal? = null
            instrumentation.runOnMainSync {
                LocalBrain.plan(context, emptyList(), "Сколько осталось процентов батареи?") { proposal = it; completed.countDown() }
            }
            assertTrue("Local model failed to restart after OTA", completed.await(120, TimeUnit.SECONDS))
            assertEquals("JNI/model broken after replacement: $proposal", VoiceCommand.Battery, proposal?.command)
        }
        assertTrue(Updates.message(context).contains("установлено"))
        assertEquals("installed", updates.getString("state", ""))
        assertEquals("same data after replacement", context.getSharedPreferences("settings", 0).getString("ota_test_preserved", null))
        assertTrue(context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("ota-") })
        assertTrue("New process must use production CA validation", runCatching { BackendClient.open(context, "/updates/manifest").let { connection -> try { connection.responseCode } finally { connection.disconnect() } } }.isFailure)
        tls {
            val connection = BackendClient.open(context, "/updates/manifest")
            try { assertEquals("Keystore token must survive replacement", 200, connection.responseCode) } finally { connection.disconnect() }
            assertNull("Installed version must not reinstall itself", Updates.download(context))
        }
        val listening = CountDownLatch(1)
        var conversation: VoiceConversation? = null
        instrumentation.runOnMainSync {
            conversation = VoiceConversation(context, { if (it == "Слушаю…") listening.countDown() }, {}, requireHeadset = false).also { it.start() }
        }
        try {
            assertTrue("Voice did not restart after OTA", listening.await(30, TimeUnit.SECONDS))
            assertFalse(context.getSystemService(AudioManager::class.java).activeRecordingConfigurations.isEmpty())
        } finally { instrumentation.runOnMainSync { conversation?.close() } }
        Thread.sleep(200)
        assertTrue(context.getSystemService(AudioManager::class.java).activeRecordingConfigurations.isEmpty())
        if (InstrumentationRegistry.getArguments().getString("speech_engine") == "tone") {
            assertTrue("Selected speech engine lost after OTA", context.getSharedPreferences("settings", 0).getBoolean("tone_asr", false))
            assertEquals("New process did not use the selected engine", "t-one", JSONObject(SessionDiagnostics.last(context)).getString("speechEngine"))
        }
        val preferences = context.getSharedPreferences("settings", 0)
        val hadHeadsetSetting = preferences.contains("headset_only")
        val headsetSetting = preferences.getBoolean("headset_only", true)
        preferences.edit().putBoolean("headset_only", false).commit()
        try {
            instrumentation.runOnMainSync {
                AssistantHealth.message = "Проверка входа после OTA"
                context.startActivity(Intent(context, VoiceCommandActivity::class.java)
                    .setAction(Intent.ACTION_VOICE_COMMAND).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            val until = System.currentTimeMillis() + 30000
            while (AssistantHealth.message != "Слушаю…" && System.currentTimeMillis() < until) Thread.sleep(100)
            assertEquals("System entry failed after replacement", "Слушаю…", AssistantHealth.message)
            assertFalse("System entry did not capture microphone", context.getSystemService(AudioManager::class.java).activeRecordingConfigurations.isEmpty())
            instrumentation.runOnMainSync { VoiceConversation.stopActive() }
            val audio = context.getSystemService(AudioManager::class.java)
            fun waitStopped() {
                val deadline = System.currentTimeMillis() + 10000
                while (audio.activeRecordingConfigurations.isNotEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(100)
                assertTrue("System key retained microphone after OTA", audio.activeRecordingConfigurations.isEmpty())
            }
            waitStopped()
            val automation = instrumentation.uiAutomation
            for (key in listOf("KEYCODE_VOICE_ASSIST", "--longpress KEYCODE_HEADSETHOOK")) {
                fun press() {
                    automation.executeShellCommand("input keyevent $key").use {
                        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
                    }
                }
                instrumentation.runOnMainSync { AssistantHealth.message = "Проверка системной клавиши после OTA" }
                press()
                val deadline = System.currentTimeMillis() + 30000
                while (AssistantHealth.message != "Слушаю…" && System.currentTimeMillis() < deadline) Thread.sleep(100)
                assertEquals("Injected $key did not start after OTA", "Слушаю…", AssistantHealth.message)
                assertTrue("Injected $key did not capture microphone", audio.activeRecordingConfigurations.isNotEmpty())
                press()
                waitStopped()
            }
        } finally {
            instrumentation.runOnMainSync { VoiceConversation.stopActive() }
            val restore = preferences.edit()
            if (hadHeadsetSetting) restore.putBoolean("headset_only", headsetSetting) else restore.remove("headset_only")
            restore.commit()
        }
        Thread.sleep(200)
        assertTrue("System entry retained microphone", context.getSystemService(AudioManager::class.java).activeRecordingConfigurations.isEmpty())
    }
}
