package local.voice

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class ArithmeticConversationTest {
    @Test fun arithmeticAnswersDoNotDependOnSelectedConversationModeOrBrainWeights() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("settings", 0)
        val saved = prefs.getString("brain_mode", null)
        try {
            for (mode in listOf("LOCAL", "AUTO", "HERMES")) {
                prefs.edit().putString("brain_mode", mode).commit()
                val replies = LinkedBlockingQueue<String>()
                var conversation: VoiceConversation? = null
                try {
                    instrumentation.runOnMainSync {
                        conversation = VoiceConversation(context, {}, {}, textOnly = true, replyObserver = { replies.offer(it) })
                        conversation!!.start()
                        conversation!!.respond("сколько будет два плюс два")
                    }
                    assertEquals(mode, "4.", replies.poll(3, TimeUnit.SECONDS))
                    instrumentation.runOnMainSync { conversation!!.respond("сколько будет шесть умножить на семь") }
                    assertEquals(mode, "42.", replies.poll(3, TimeUnit.SECONDS))
                } finally { instrumentation.runOnMainSync { conversation?.close() } }
            }
        } finally {
            prefs.edit().apply { if (saved == null) remove("brain_mode") else putString("brain_mode", saved) }.commit()
        }
    }
}
