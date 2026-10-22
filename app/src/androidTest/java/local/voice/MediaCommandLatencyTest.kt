package local.voice

import android.content.ComponentName
import android.content.Intent
import android.media.AudioManager
import android.media.session.MediaSessionManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** External metadata-only fixture; no audio playback or microphone. */
class MediaCommandLatencyTest {
    @Test fun nextTrackIsExecutedAndVerifiedWithoutLoadingOrCallingLlm() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val component = PhoneNotifications.component(context)
        val access = PhoneNotifications.hasAccess(context)
        fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
            java.io.FileInputStream(it.fileDescriptor).use { input -> input.readBytes() }
        }
        fun fixture(mode: String) {
            context.startActivity(Intent().setComponent(ComponentName("local.voice.fixture", "local.voice.fixture.MediaActivity"))
                .putExtra("mode", mode).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Thread.sleep(300)
        }
        val prefs = context.getSharedPreferences("settings", 0)
        val oldMode = prefs.getString("brain_mode", null)
        val times = org.json.JSONArray()
        try {
            shell("cmd notification allow_listener ${component.flattenToString()}")
            prefs.edit().putString("brain_mode", "LOCAL").commit()
            for (phrase in listOf("следующий трек", "включи следующий трек", "пожалуйста, следующая песня")) {
                fixture("normal")
                var conversation: VoiceConversation? = null
                val ready = CountDownLatch(1)
                var answer = ""
                val stats = LocalBrain.lastStats
                val began = android.os.SystemClock.elapsedRealtime()
                try {
                    instrumentation.runOnMainSync {
                        conversation = VoiceConversation(context, {}, {}, textOnly = true, replyObserver = {
                            answer = it; ready.countDown()
                        }).also { it.start(); it.respond(phrase) }
                    }
                    assertTrue("No verified response for $phrase", ready.await(5, TimeUnit.SECONDS))
                    val elapsed = android.os.SystemClock.elapsedRealtime() - began
                    assertTrue(answer, answer.contains("сменил трек"))
                    assertTrue("Immediate fixture confirmation took ${elapsed}ms", elapsed < 2000)
                    assertSame("A direct media command called LLM", stats, LocalBrain.lastStats)
                    val controller = context.getSystemService(MediaSessionManager::class.java).getActiveSessions(component)
                        .single { it.packageName == "local.voice.fixture" }
                    assertEquals("Тестовый трек 2", controller.metadata!!.getString(android.media.MediaMetadata.METADATA_KEY_TITLE))
                    assertTrue(context.getSystemService(AudioManager::class.java).activeRecordingConfigurations.isEmpty())
                    assertEquals(0L, conversation!!.speechFrames)
                    times.put(org.json.JSONObject().put("phrase", phrase).put("elapsedMs", elapsed))
                } finally { instrumentation.runOnMainSync { conversation?.close() } }
            }
            java.io.File(context.cacheDir, "media-command-latency.json").writeText(times.toString(2))
        } finally {
            fixture("clear")
            shell("cmd notification ${if (access) "allow_listener" else "disallow_listener"} ${component.flattenToString()}")
            prefs.edit().apply { if (oldMode == null) remove("brain_mode") else putString("brain_mode", oldMode) }.commit()
        }
    }
}
