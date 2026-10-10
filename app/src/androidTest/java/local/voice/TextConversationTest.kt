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

@RunWith(AndroidJUnit4::class)
class TextConversationTest {
    @Test fun typedCommandAnswersWithoutOpeningMicrophoneOrSpeechPlayback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Thread.sleep(500)
        val answered = CountDownLatch(1)
        var reply = ""
        var conversation: VoiceConversation? = null
        val audio = context.getSystemService(AudioManager::class.java)
        val beforeMode = audio.mode
        instrumentation.runOnMainSync {
            conversation = VoiceConversation(context, {}, {}, textOnly = true,
                replyObserver = { reply = it; answered.countDown() }).also { it.start(); it.respond("который час") }
        }
        try {
            assertTrue(answered.await(3, TimeUnit.SECONDS))
            assertTrue(reply.startsWith("Сейчас"))
            assertTrue("Typed request opened microphone", audio.activeRecordingConfigurations.isEmpty())
            assertEquals(beforeMode, audio.mode)
            assertNull(conversation!!.speechOutputDeviceType)
            assertEquals(0L, conversation!!.speechFrames)
        } finally { instrumentation.runOnMainSync { conversation?.close() } }
    }
    @Test fun typedChatUsesLocalModelWithoutSpeechCapture() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Thread.sleep(500)
        val answered = CountDownLatch(1)
        var reply = ""
        var conversation: VoiceConversation? = null
        instrumentation.runOnMainSync {
            conversation = VoiceConversation(context, {}, {}, textOnly = true,
                replyObserver = { reply = it; answered.countDown() }).also { it.start(); it.respond("Назови число после двух.") }
        }
        try {
            assertTrue("Typed local chat timed out", answered.await(40, TimeUnit.SECONDS))
            assertTrue(reply.isNotBlank())
            assertFalse(reply.contains("Импортируйте", ignoreCase = true))
            assertFalse(reply.contains("не смогла ответить", ignoreCase = true))
            assertTrue(context.getSystemService(AudioManager::class.java).activeRecordingConfigurations.isEmpty())
            assertNull(conversation!!.speechOutputDeviceType)
        } finally { instrumentation.runOnMainSync { conversation?.close() } }
    }

}
