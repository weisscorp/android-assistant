package local.voice

import android.content.Intent
import android.media.AudioManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class HeadsetButtonTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val audio get() = context.getSystemService(AudioManager::class.java)
    private fun waitFor(message: String, predicate: () -> Boolean) {
        val until = System.currentTimeMillis() + 10000
        while (System.currentTimeMillis() < until) {
            var ready = false
            instrumentation.runOnMainSync { ready = predicate() }
            if (ready) return
            Thread.sleep(20)
        }
        fail(message)
    }
    private fun launchHome() {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
    }
    @Test fun repeatedEntryWhileThinkingCancelsInferenceAndCannotResumeListening() {
        launchHome()
        val listening = CountDownLatch(1)
        val finished = CountDownLatch(1)
        var conversation: VoiceConversation? = null
        var listenCount = 0
        try {
            instrumentation.runOnMainSync {
                conversation = VoiceConversation(context, { if (it == "Слушаю…") { listenCount++; listening.countDown() } },
                    { finished.countDown() }, phoneMicrophone = true).also { it.start() }
            }
            assertTrue(listening.await(10, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                conversation!!.respond("Расскажи короткую историю про путешествие к звёздам")
                assertEquals("Test did not exercise the thinking phase", SessionDiagnostics.Phase.THINKING, VoiceConversation.activePhase)
                context.startActivity(Intent(context, VoiceCommandActivity::class.java)
                    .setAction(Intent.ACTION_VOICE_COMMAND).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            assertTrue("Second invocation did not end the inference session", finished.await(5, TimeUnit.SECONDS))
            Thread.sleep(1000)
            assertEquals("Inference callback restarted listening after cancellation", 1, listenCount)
            assertNull(VoiceConversation.activePhase)
            assertTrue(audio.activeRecordingConfigurations.isEmpty())
            assertEquals("button_stop", JSONObject(SessionDiagnostics.last(context)).getString("reason"))
        } finally { instrumentation.runOnMainSync { conversation?.close() } }
    }
    @Test fun headsetAudioEndStopsActualPlaybackAndCaptureWithoutRestarting() {
        launchHome()
        val listening = CountDownLatch(1)
        val finished = CountDownLatch(1)
        var conversation: VoiceConversation? = null
        var listenCount = 0
        try {
            instrumentation.runOnMainSync {
                conversation = VoiceConversation(context, { if (it == "Слушаю…") { listenCount++; listening.countDown() } },
                    { finished.countDown() }, phoneMicrophone = true).also { it.start() }
            }
            assertTrue(listening.await(10, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { conversation!!.respond("который час") }
            waitFor("Test did not start real TTS playback") { conversation!!.speechFrames > 0 }
            // Simulate the HFP transport callback. Actual Cardo broadcasts require hardware.
            instrumentation.runOnMainSync { conversation!!.headsetEndedAudio() }
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            waitFor("Headset-ended audio kept capture active") { audio.activeRecordingConfigurations.isEmpty() }
            Thread.sleep(1000)
            assertEquals(1, listenCount)
            assertNull(VoiceConversation.activePhase)
            assertTrue(audio.activeRecordingConfigurations.isEmpty())
            assertEquals("headset_ended_audio", JSONObject(SessionDiagnostics.last(context)).getString("reason"))
        } finally { instrumentation.runOnMainSync { conversation?.close() } }
    }
}
