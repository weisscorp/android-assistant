package local.voice

import org.junit.Assert.*
import org.junit.Test

class UnlockActionQueueTest {
    @Test fun protectedActionIsRetainedUntilAuthenticatedAndConsumedExactlyOnce() {
        var now = 1000L; var runs = 0
        val queue = UnlockActionQueue { now }
        val token = queue.stage(61000) { runs++; null }
        assertNotNull(queue.run(token) { false }); assertEquals(0, runs)
        assertEquals(60000L, queue.remaining(token))
        assertNull(queue.run(token) { true }); assertEquals(1, runs)
        assertNotNull(queue.run(token) { true }); assertEquals(1, runs)
    }
    @Test fun timeoutCancellationAndNewRequestNeverResurrectOldCommand() {
        var now = 1000L; var runs = 0
        val queue = UnlockActionQueue { now }
        val expired = queue.stage(2000) { runs++; null }; now = 2000
        assertNotNull(queue.run(expired) { true })
        val cancelled = queue.stage(3000) { runs++; null }; queue.cancel(cancelled)
        assertNotNull(queue.run(cancelled) { true })
        val old = queue.stage(3000) { runs++; null }
        val latest = queue.stage(3000) { runs += 10; null }
        queue.cancel(old)
        assertNotNull(queue.run(old) { true }); assertEquals(0, runs)
        assertNull(queue.run(latest) { true }); assertEquals(10, runs)
    }
}
