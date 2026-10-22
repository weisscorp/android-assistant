package local.voice

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ConversationMemoryTest {
    @Test fun compactNotePersistsAndFreshConversationRecallsIt() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("conversation_memory", 0)
        val original = prefs.getString("note", null)
        try {
            ConversationMemory.clear(context)
            val utterance = "Давай поговорим про поездку в Батуми, я предпочитаю путешествовать поездом."
            ConversationMemory.save(context, listOf("Поездка в Батуми"), listOf("Предпочитает поезд", utterance, "x".repeat(111)), utterance)
            val stored = ConversationMemory.read(context)!!
            assertEquals(listOf("Предпочитает поезд"), stored.points)
            assertFalse(prefs.getString("note", "")!!.contains(utterance))
            ConversationMemory.save(context, emptyList(), emptyList(), "Привет")
            assertEquals(stored, ConversationMemory.read(context.createPackageContext(context.packageName, 0)))
            val latch = CountDownLatch(1)
            var answer = ""
            val began = android.os.SystemClock.elapsedRealtime()
            instrumentation.runOnMainSync {
                // This test promises a fresh conversation, independent of preceding model tests.
                LocalBrain.dropContext()
                LocalBrain.answerWithMemory(context, emptyList(), "Напомни, куда я собираюсь и каким транспортом предпочитаю ехать?") {
                    answer = it
                    latch.countDown()
                }
            }
            assertTrue("No answer", latch.await(45, TimeUnit.SECONDS))
            assertTrue(answer, answer.contains("Батуми", true))
            assertTrue(answer, answer.contains("поезд", true))
            assertNotNull("Model failed to preserve compact memory", ConversationMemory.read(context))
            java.io.File(context.cacheDir, "memory-metrics.txt").writeText("elapsedMs=${android.os.SystemClock.elapsedRealtime() - began}; promptTokens=${LocalBrain.lastStats?.promptTokens}")
            ConversationMemory.clear(context)
            assertNull(ConversationMemory.read(context))
        } finally {
            instrumentation.runOnMainSync { LocalBrain.cancel() }
            prefs.edit().apply { if (original == null) remove("note") else putString("note", original) }.commit()
        }
    }
}
