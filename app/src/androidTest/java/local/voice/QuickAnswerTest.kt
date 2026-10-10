package local.voice

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.io.File
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class QuickAnswerTest {
    @Test fun plainAnswerAndCompactToolPromptReturnUsefulResults() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val answered = CountDownLatch(1)
        var answer = ""
        val began = android.os.SystemClock.elapsedRealtime()
        instrumentation.runOnMainSync {
            LocalBrain.answer(context, emptyList(), "Как называется столица Франции? Ответь одним словом.") { answer = it; answered.countDown() }
        }
        assertTrue(answered.await(30, TimeUnit.SECONDS))
        assertTrue(answer, answer.contains("Париж", true))
        val answerMs = android.os.SystemClock.elapsedRealtime() - began
        val answerStats = LocalBrain.lastStats!!
        assertEquals("Text inference should use the normal CPU budget", 4L, answerStats.threads)
        assertTrue("Plain answer inherited tool instructions", answerStats.promptTokens < 200)
        val planned = CountDownLatch(1)
        var proposal: LocalBrain.Proposal? = null
        val planBegan = android.os.SystemClock.elapsedRealtime()
        instrumentation.runOnMainSync {
            LocalBrain.plan(context, emptyList(), "Сколько осталось процентов батареи?") { proposal = it; planned.countDown() }
        }
        assertTrue(planned.await(45, TimeUnit.SECONDS))
        assertEquals(proposal.toString(), VoiceCommand.Battery, proposal?.command)
        val planStats = LocalBrain.lastStats!!
        assertTrue("Tool instructions are too long", planStats.promptTokens < 1000)
        File(context.cacheDir, "quick-answer-metrics.json").writeText(JSONObject()
            .put("answerMs", answerMs).put("answerTokens", answerStats.promptTokens)
            .put("planMs", android.os.SystemClock.elapsedRealtime() - planBegan).put("planTokens", planStats.promptTokens).toString())
    }
}
