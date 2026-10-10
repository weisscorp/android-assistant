package local.voice

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.Context
import android.media.AudioManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Synthetic settings only. Select this suite explicitly on a disposable emulator. */
@RunWith(AndroidJUnit4::class)
class AutoUpdateTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun isolated(test: () -> Unit) {
        val prefs = context.getSharedPreferences("dev_ota", 0)
        val saved = prefs.all
        try { AutoUpdates.cancel(context); prefs.edit().clear().commit(); test() }
        finally {
            AutoUpdates.cancel(context)
            prefs.edit().clear().apply { saved.forEach { (key, value) -> when (value) {
                is String -> putString(key, value); is Long -> putLong(key, value); is Boolean -> putBoolean(key, value)
            } } }.commit()
        }
    }
    @Test fun ownerOptInSchedulesQuietUnmeteredJobsAndDisableCancelsThem() = isolated {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        AutoUpdates.schedule(context, immediate = true)
        assertNull(scheduler.getPendingJob(AutoUpdates.PERIODIC_JOB))
        val token = "synthetic-test-key-not-a-personal-credential"
        OtaClient.save(context, "https://127.0.0.1:9876", token, "", true)
        val periodic = requireNotNull(scheduler.getPendingJob(AutoUpdates.PERIODIC_JOB))
        assertTrue(periodic.isPersisted); assertTrue(periodic.isPeriodic)
        assertEquals(15 * 60_000L, periodic.intervalMillis)
        assertEquals(JobInfo.NETWORK_TYPE_UNMETERED, periodic.networkType)
        assertNotNull(scheduler.getPendingJob(AutoUpdates.CHECK_JOB))
        assertFalse(context.getSharedPreferences("dev_ota", 0).getString("token", "")!!.contains(token))
        val connection = OtaClient.connections(context)("/updates/manifest")
        assertEquals("Bearer $token", connection.getRequestProperty("Authorization")); connection.disconnect()
        OtaClient.save(context, "https://127.0.0.1:9876", "", "", false)
        assertNull(scheduler.getPendingJob(AutoUpdates.PERIODIC_JOB)); assertNull(scheduler.getPendingJob(AutoUpdates.CHECK_JOB))
        assertTrue(context.getSystemService(AudioManager::class.java).activeRecordingConfigurations.isEmpty())
        assertNull(VoiceConversation.activePhase)
    }
    @Test fun disabledCycleAndCancelledJobDoNotContactTheServerOrStartVoice() = isolated {
        OtaClient.save(context, "https://127.0.0.1:1", "synthetic-key-xxxxxxxxxxxxxxxxxxxx", "", false)
        assertFalse(AutoUpdates.cycle(context, BackendClient.Request()))
        val operation = BackendClient.Request().apply { cancel() }
        val done = CountDownLatch(1)
        AutoUpdates.check(context, operation) { done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertNull(VoiceConversation.activePhase)
        assertTrue(context.getSystemService(AudioManager::class.java).activeRecordingConfigurations.isEmpty())
    }
    @Test fun changedEndpointRequiresNewKeyAndInvalidatesCandidateIdentity() = isolated {
        OtaClient.save(context, "https://127.0.0.1:9876", "synthetic-key-xxxxxxxxxxxxxxxxxxxx", "", false)
        val source = OtaClient.source(context)
        assertThrows(IllegalArgumentException::class.java) { OtaClient.save(context, "https://127.0.0.1:9877", "", "", false) }
        assertEquals(source, OtaClient.source(context))
        OtaClient.save(context, "https://127.0.0.1:9877", "another-synthetic-key-xxxxxxxxxxx", "", false)
        assertNotEquals(source, OtaClient.source(context))
    }
    /** No ASR, TTS, UI or microphone: the external harness observes process replacement. */
    @Test fun startRealBackgroundSelfUpdate() {
        val args = InstrumentationRegistry.getArguments()
        val endpoint = args.getString("ota_endpoint") ?: error("Select with an explicit disposable emulator and a synthetic OTA server")
        val pin = args.getString("ota_pin") ?: error("Missing synthetic server pin")
        val token = args.getString("ota_token") ?: error("Missing synthetic server token")
        val prefs = context.getSharedPreferences("dev_ota", 0)
        prefs.edit().putString("preservation_fixture", "still here after replacement").commit()
        OtaClient.save(context, endpoint, token, pin, true)
        AutoUpdates.cancel(context) // Exercise the exact background worker deterministically.
        val done = CountDownLatch(1)
        AutoUpdates.check(context, BackendClient.Request()) { done.countDown() }
        assertTrue(done.await(120, TimeUnit.SECONDS))
        assertFalse(Updates.message(context), Updates.state(context) == "failed")
        assertTrue(context.getSystemService(AudioManager::class.java).activeRecordingConfigurations.isEmpty())
        Thread.sleep(15_000) // Installer may kill this test process; harness verifies the installed version.
    }
    @Test fun replacementPreservesSettingsAndReschedulesWithoutStartingVoice() {
        assertEquals("still here after replacement", context.getSharedPreferences("dev_ota", 0).getString("preservation_fixture", ""))
        assertTrue(OtaClient.enabled(context))
        Updates.reconcile(context)
        assertEquals("installed", Updates.state(context))
        assertNotNull(context.getSystemService(JobScheduler::class.java).getPendingJob(AutoUpdates.PERIODIC_JOB))
        assertNull(VoiceConversation.activePhase)
        assertTrue(context.getSystemService(AudioManager::class.java).activeRecordingConfigurations.isEmpty())
    }
}
