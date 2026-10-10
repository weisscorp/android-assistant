package local.voice

import android.content.Intent
import android.media.AudioManager
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ReadyCueTest {
    @Test fun staticCueWritesBeforeCheckingInitializedAndReachesPlaybackEnd() {
        checkPlayback(descending = false)
    }
    @Test fun descendingCueReachesPlaybackEnd() {
        checkPlayback(descending = true)
    }
    @Test fun stoppingListeningReleasesMicrophoneAndEndsAfterCue() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Thread.sleep(500)
        val listening = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        var conversation: VoiceConversation? = null
        instrumentation.runOnMainSync {
            conversation = VoiceConversation(context, { if (it == "Слушаю…") listening.countDown() }, { stopped.countDown() }).also { it.start() }
        }
        try {
            assertTrue(listening.await(10, TimeUnit.SECONDS))
            Thread.sleep(500) // Let the start cue finish before pressing stop.
            instrumentation.runOnMainSync { conversation!!.stop() }
            assertTrue("Stop cue prevented conversation cleanup", stopped.await(3, TimeUnit.SECONDS))
            assertTrue(context.getSystemService(AudioManager::class.java).activeRecordingConfigurations.isEmpty())
        } finally { instrumentation.runOnMainSync { conversation?.close() } }
    }
    private fun checkPlayback(descending: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val done = CountDownLatch(1)
        val error = AtomicReference<String?>("Callback never invoked")
        instrumentation.runOnMainSync {
            ReadyCue.play(instrumentation.targetContext, true, descending) { result -> error.set(result); done.countDown() }
        }
        assertTrue("Readiness audio callback timed out", done.await(4, TimeUnit.SECONDS))
        assertNull(error.get())
    }
}
