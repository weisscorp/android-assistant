package local.voice

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class IdleNativeModelTest {
    @Test fun checkoutWaitsForWarmupAndBorrowsSameModel() {
        val pool = IdleNativeModel<Any> { error("Borrowed model must not be released") }
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val model = Any()
        try {
            val warm = executor.submit { pool.warm { entered.countDown(); check(finish.await(2, TimeUnit.SECONDS)); model } }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val checkout = executor.submit<Any> { pool.take { error("Warmup must prevent a duplicate load") } }
            finish.countDown()
            warm.get(2, TimeUnit.SECONDS)
            assertSame(model, checkout.get(2, TimeUnit.SECONDS))
        } finally { finish.countDown(); executor.shutdownNow() }
    }
    @Test fun changingVoiceKeyReleasesOnlyIdleModel() {
        val released = mutableListOf<Any>()
        val pool = IdleNativeModel<Any> { released += it }
        val previous = Any()
        val selected = Any()
        pool.put(previous, "old")
        assertSame(selected, pool.take("new") { selected })
        assertEquals(listOf(previous), released)
        pool.put(selected, "new")
        assertSame(selected, pool.take("new") { error("Expected cached voice") })
        assertEquals(listOf(previous), released)
    }
    @Test fun memoryPressureDoesNotReleaseCheckedOutModel() {
        val released = mutableListOf<Any>()
        val pool = IdleNativeModel<Any>(retainUntilMemoryPressure = true) { released += it }
        val model = Any()
        pool.put(model)
        assertSame(model, pool.take { error("Expected cached model") })
        pool.clearIdle()
        assertTrue(released.isEmpty())
        pool.put(model)
        pool.clearIdle()
        assertEquals(listOf(model), released)
    }

}
