package local.voice

import android.content.Intent
import android.media.AudioManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class CaptureFirstTest {
    @Test fun microphoneAndLevelsStartWhileModelLoadIsBlockedAndCancelReleasesCapture() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Thread.sleep(500)
        val loading = CountDownLatch(1)
        val allowLoad = CountDownLatch(1)
        val listening = CountDownLatch(1)
        val levels = CountDownLatch(1)
        val modelReady = CountDownLatch(1)
        var speech: OfflineSpeech? = null
        var callbacks = 0
        instrumentation.runOnMainSync {
            speech = OfflineSpeech(context, persistent = true, phoneMicrophone = true,
                level = { _, _, _ -> levels.countDown() }, beforeModelLoad = {
                    loading.countDown(); check(allowLoad.await(10, TimeUnit.SECONDS))
                })
            speech!!.prepare { error -> assertNull(error); modelReady.countDown() }
            speech!!.listen(false, { if (it == "Слушаю…") listening.countDown() }, { _, _ -> callbacks++ })
        }
        try {
            assertTrue(loading.await(3, TimeUnit.SECONDS))
            assertTrue("Capture waited for decoder", listening.await(2, TimeUnit.SECONDS))
            assertTrue("Raw microphone did not feed levels while model was blocked", levels.await(2, TimeUnit.SECONDS))
            assertEquals(1L, modelReady.count)
            assertEquals(1, context.getSystemService(AudioManager::class.java).activeRecordingConfigurations.size)
            // A finished phrase remains pending until the decoder becomes ready.
            instrumentation.runOnMainSync { speech!!.finishUtterance() }
            Thread.sleep(100)
            assertEquals("Undecoded utterance was discarded", 0, callbacks)
            allowLoad.countDown()
            assertTrue(modelReady.await(15, TimeUnit.SECONDS))
        } finally {
            allowLoad.countDown()
            instrumentation.runOnMainSync { speech?.close() }
        }
        Thread.sleep(500)
        assertTrue(context.getSystemService(AudioManager::class.java).activeRecordingConfigurations.isEmpty())
    }
}
