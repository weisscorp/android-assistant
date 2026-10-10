package local.voice

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real GGUF/JNI, including a mid-inference abort and the actual idle eviction timer. */
@RunWith(AndroidJUnit4::class)
class LocalCacheTest {
    @Test fun cachedInstructionsPreserveFreshInputCancellationAndIdleRelease() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        fun plan(text: String): Pair<LocalBrain.Proposal, LocalBrain.Stats> {
            val completed = CountDownLatch(1)
            var result: LocalBrain.Proposal? = null
            instrumentation.runOnMainSync { LocalBrain.plan(context, emptyList(), text) { result = it; completed.countDown() } }
            assertTrue("Native plan timed out", completed.await(120, TimeUnit.SECONDS))
            return result!! to (LocalBrain.lastStats ?: error("No completed native statistics"))
        }
        fun answer(text: String): String {
            val completed = CountDownLatch(1)
            var result = ""
            instrumentation.runOnMainSync { LocalBrain.answer(context, emptyList(), text) { result = it; completed.countDown() } }
            assertTrue(completed.await(120, TimeUnit.SECONDS))
            return result
        }
        fun stats(value: LocalBrain.Stats) = JSONObject().put("promptTokens", value.promptTokens)
            .put("reusedTokens", value.reusedTokens).put("prefillTokens", value.prefillTokens)
            .put("cachedInstructionTokens", value.cachedInstructionTokens)
            .put("prefillMicros", value.prefillMicros).put("totalMicros", value.totalMicros)
        // Switch away from the structured system prompt to establish a cold structured prefix.
        assertTrue(answer("Как по-русски называется столица Франции?").contains("Париж", true))
        val (coldResult, cold) = plan("Сколько осталось процентов батареи?")
        val (warmResult, warm) = plan("Сколько осталось заряда аккумулятора?")
        assertEquals(VoiceCommand.Battery, coldResult.command)
        assertEquals(VoiceCommand.Battery, warmResult.command)
        assertTrue("No instructions reused: $warm", warm.reusedTokens > 0)
        assertEquals("User tokens must not survive as cached instructions", warm.cachedInstructionTokens, warm.reusedTokens)
        assertTrue(warm.cachedInstructionTokens < warm.promptTokens)
        assertTrue("Warm invocation still evaluated the whole instruction: cold=$cold warm=$warm", warm.prefillTokens < cold.prefillTokens / 2)
        val (differentResult, different) = plan("Какое сейчас время?")
        assertEquals("Old user request leaked through the cache", VoiceCommand.Time, differentResult.command)
        assertTrue(different.reusedTokens > 0)
        assertTrue(answer("Как по-русски называется столица Франции?").contains("Париж", true))
        val changedMode = LocalBrain.lastStats!!
        assertTrue("Structured instructions reused for unstructured output", changedMode.reusedTokens < warm.reusedTokens)

        val cancelledCallback = CountDownLatch(1)
        instrumentation.runOnMainSync {
            LocalBrain.answer(context, emptyList(), "Подробно объясни устройство двигателя внутреннего сгорания, перечисли его части и опиши каждую.") { cancelledCallback.countDown() }
        }
        val startDeadline = System.currentTimeMillis() + 5000
        while (!LocalBrain.computing && System.currentTimeMillis() < startDeadline) Thread.sleep(5)
        assertTrue("Cancellation test never entered native inference", LocalBrain.computing)
        instrumentation.runOnMainSync { LocalBrain.cancel() }
        val stopDeadline = System.currentTimeMillis() + 5000
        while (LocalBrain.computing && System.currentTimeMillis() < stopDeadline) Thread.sleep(5)
        assertFalse("Native inference ignored cancellation", LocalBrain.computing)
        val (recoveredResult, recovered) = plan("Сколько осталось процентов батареи?")
        assertEquals(VoiceCommand.Battery, recoveredResult.command)
        assertEquals("Partially aborted KV must be discarded", 0L, recovered.reusedTokens)
        assertEquals("Cancelled answer callback was delivered", 1L, cancelledCallback.count)
        assertTrue(LocalBrain.modelResident)
        instrumentation.runOnMainSync { LocalBrain.dropContext() }
        val (droppedResult, dropped) = plan("Сколько осталось процентов батареи?")
        assertEquals(VoiceCommand.Battery, droppedResult.command)
        assertEquals("Closed conversation's context survived", 0L, dropped.reusedTokens)
        assertTrue("Dropping the context should keep the idle model cache", LocalBrain.modelResident)

        val evictionDeadline = System.currentTimeMillis() + 75000
        while (LocalBrain.modelResident && System.currentTimeMillis() < evictionDeadline) Thread.sleep(100)
        assertFalse("Idle timer retained the model/context", LocalBrain.modelResident)
        val (reloadedResult, reloaded) = plan("Сколько осталось процентов батареи?")
        assertEquals(VoiceCommand.Battery, reloadedResult.command)
        assertEquals("Freed context unexpectedly survived reload", 0L, reloaded.reusedTokens)
        File(context.cacheDir, "local-cache-metrics.json").writeText(JSONObject()
            .put("cold", stats(cold)).put("warm", stats(warm)).put("differentRequest", stats(different))
            .put("changedMode", stats(changedMode)).put("afterCancellation", stats(recovered))
            .put("afterContextDrop", stats(dropped)).put("afterIdleReload", stats(reloaded)).put("cancelledCallbackSuppressed", true)
            .put("idleEvictionObserved", true).toString(2))
    }
}
