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
class RecordingFailureTest {
    @Test fun emptyHeadsetInputSpeaksSpecificFailureWithoutReopeningTheMicrophone() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val audio = context.getSystemService(AudioManager::class.java)
        val listening = CountDownLatch(1)
        val finished = CountDownLatch(1)
        var conversation: VoiceConversation? = null
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        try {
            instrumentation.runOnMainSync {
                conversation = VoiceConversation(context, { if (it == "Слушаю…") listening.countDown() },
                    { finished.countDown() }, phoneMicrophone = true).also { it.start() }
            }
            assertTrue(listening.await(10, TimeUnit.SECONDS))
            assertEquals(1, audio.activeRecordingConfigurations.size)
            // Inject the transport failure after real AudioRecord startup. The emulator
            // cannot represent Cardo SCO; this checks cleanup and the real TTS error path.
            instrumentation.runOnMainSync { conversation!!.recordingFailed(OfflineSpeech.NO_HEADSET_AUDIO) }
            val until = System.currentTimeMillis() + 10000
            while (conversation!!.speechFrames == 0L && finished.count > 0 && System.currentTimeMillis() < until) Thread.sleep(20)
            assertTrue("Failure reply produced no audio", conversation!!.speechFrames > 0)
            assertTrue("Broken input reopened for stop recognition", audio.activeRecordingConfigurations.isEmpty())
            assertTrue("Failure reply did not finish", finished.await(20, TimeUnit.SECONDS))
            assertTrue(audio.activeRecordingConfigurations.isEmpty())
            assertEquals("headset_no_input", JSONObject(SessionDiagnostics.last(context)).getString("reason"))
            assertNull(VoiceConversation.activePhase)
        } finally { instrumentation.runOnMainSync { conversation?.close() } }
    }
}
