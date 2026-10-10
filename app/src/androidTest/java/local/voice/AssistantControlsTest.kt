package local.voice

import android.content.Intent
import android.media.AudioManager
import android.widget.Button
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AssistantControlsTest {
    @Test fun transcriptAndFinishRecordingAndStopControlsWork() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Thread.sleep(500)
        val listening = CountDownLatch(1)
        val finalized = CountDownLatch(1)
        var conversation: VoiceConversation? = null
        lateinit var panel: AssistantStatusView
        lateinit var finish: Button
        lateinit var stop: Button
        instrumentation.runOnMainSync {
            panel = AssistantStatusView(context)
            conversation = VoiceConversation(context, { text ->
                panel.update(text)
                if (text == "Слушаю…") listening.countDown()
                if (text == "Не расслышал. Повторите.") finalized.countDown()
            }, {}, transcript = { text, final -> panel.transcript(text, final) }).also { it.start() }
            val buttons = (0 until panel.childCount).map { panel.getChildAt(it) }.filterIsInstance<Button>()
            finish = buttons.single { it.text == "Закончить запись" }
            stop = buttons.single { it.text == "Остановить помощника" }
        }
        try {
            assertTrue(listening.await(10, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                panel.transcript("проверяем речь", false)
                val texts = (0 until panel.childCount).map { panel.getChildAt(it) }.filterIsInstance<TextView>()
                assertTrue(texts.any { it.text.toString() == "Распознаю:\nпроверяем речь" })
                panel.transcript("проверяем речь", true)
                assertTrue(texts.any { it.text.toString() == "Вы сказали:\nпроверяем речь" })
                assertTrue(finish.isEnabled)
                finish.performClick()
                assertFalse(finish.isEnabled)
                assertFalse("Explicit finish must leave the listening state immediately", conversation!!.listening)
                assertEquals(SessionDiagnostics.Phase.THINKING, VoiceConversation.activePhase)
            }
            assertTrue("Finish recording did not finalize the current utterance", finalized.await(10, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { stop.performClick() }
            Thread.sleep(500)
            assertTrue("Stop assistant left the microphone running", context.getSystemService(AudioManager::class.java).activeRecordingConfigurations.isEmpty())
        } finally { instrumentation.runOnMainSync { conversation?.close() } }
    }
}
