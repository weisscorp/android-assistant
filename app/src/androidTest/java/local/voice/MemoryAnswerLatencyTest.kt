package local.voice

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real JNI generation, synthetic memory, and no microphone or audio playback. */
class MemoryAnswerLatencyTest {
    @Test fun readyAnswerIsDeliveredOnceBeforeMemoryTailFinishes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("conversation_memory", 0)
        val previous = prefs.getString("note", null)
        try {
            ConversationMemory.clear(context)
            ConversationMemory.save(context, listOf("Поездка"), listOf("Предпочитает поезд"), "Хочу ехать поездом")
            val previousStats = LocalBrain.lastStats
            val callbacks = AtomicInteger()
            val ready = CountDownLatch(1)
            var answer = ""
            var tailRunning = false
            val began = android.os.SystemClock.elapsedRealtime()
            var readyMs = 0L
            instrumentation.runOnMainSync {
                LocalBrain.answerWithMemory(context, emptyList(), "Какой транспорт я предпочитаю? Ответь одним предложением.") {
                    answer = it; callbacks.incrementAndGet()
                    tailRunning = LocalBrain.computing
                    readyMs = android.os.SystemClock.elapsedRealtime() - began
                    ready.countDown()
                }
            }
            assertTrue("No answer: ${LocalBrain.lastFailure}", ready.await(90, TimeUnit.SECONDS))
            assertTrue(answer, answer.contains("поезд", true))
            assertTrue("Reply waited for the memory tail", tailRunning)
            val settled = CountDownLatch(1)
            // Let generation and the posted memory commit finish without starting audio.
            val until = android.os.SystemClock.elapsedRealtime() + 90_000
            while (LocalBrain.computing && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(50)
            instrumentation.runOnMainSync { settled.countDown() }
            assertTrue(settled.await(5, TimeUnit.SECONDS))
            assertFalse("Memory did not finish", LocalBrain.computing)
            assertNull(LocalBrain.lastFailure)
            assertEquals("Duplicate answer after memory completion", 1, callbacks.get())
            // Empty topics/points legitimately preserve the previous note on a recall query.
            assertNotNull("Completed memory must retain the previous context", ConversationMemory.read(context))
            val statsUntil = android.os.SystemClock.elapsedRealtime() + 5000
            while (LocalBrain.lastStats === previousStats && android.os.SystemClock.elapsedRealtime() < statsUntil) Thread.sleep(25)
            instrumentation.runOnMainSync { }
            assertNotSame("Full inference did not complete", previousStats, LocalBrain.lastStats)
            java.io.File(context.cacheDir, "memory-answer-latency.json").writeText(org.json.JSONObject()
                .put("answerReadyMs", readyMs).put("fullCompletionMs", android.os.SystemClock.elapsedRealtime() - began)
                .put("tailRunningAtReply", tailRunning).put("callbacks", callbacks.get()).toString(2))
        } finally {
            instrumentation.runOnMainSync { LocalBrain.cancel() }
            prefs.edit().apply { if (previous == null) remove("note") else putString("note", previous) }.commit()
        }
    }

    @Test fun cancellingAfterReadyDoesNotRepeatAnswerOrReplaceMemory() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("conversation_memory", 0)
        val previous = prefs.getString("note", null)
        try {
            ConversationMemory.clear(context)
            ConversationMemory.save(context, listOf("Книги"), listOf("Любит фантастику"), "Люблю фантастику")
            val saved = prefs.getString("note", null)
            val ready = CountDownLatch(1)
            val callbacks = AtomicInteger()
            instrumentation.runOnMainSync {
                LocalBrain.answerWithMemory(context, emptyList(), "Что я люблю читать?") {
                    callbacks.incrementAndGet(); LocalBrain.cancel(); ready.countDown()
                }
            }
            assertTrue(ready.await(90, TimeUnit.SECONDS))
            val until = android.os.SystemClock.elapsedRealtime() + 10_000
            while (LocalBrain.computing && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(50)
            instrumentation.runOnMainSync { }
            assertFalse(LocalBrain.computing)
            assertEquals(1, callbacks.get())
            assertEquals(saved, prefs.getString("note", null))
        } finally {
            instrumentation.runOnMainSync { LocalBrain.cancel() }
            prefs.edit().apply { if (previous == null) remove("note") else putString("note", previous) }.commit()
        }
    }
}
