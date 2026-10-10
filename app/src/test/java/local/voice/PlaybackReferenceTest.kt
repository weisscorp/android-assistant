package local.voice

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sin

class PlaybackReferenceTest {
    @Test fun echoProcessingWindowEndsAfterPlaybackTail() {
        val reference = PlaybackReference()
        assertFalse(reference.hasRecentAudio(1_000_000_000))
        reference.begin(16000)
        reference.append(FloatArray(16000) { .1f }, 0, 16000, 0, 0, 1_000_000_000)
        assertTrue(reference.hasRecentAudio(2_400_000_000))
        assertFalse(reference.hasRecentAudio(2_600_000_000))
        reference.clear()
        assertFalse(reference.hasRecentAudio(1_000_000_000))
    }
    @Test fun playbackClockResamplesAndDoesNotInventFutureAudio() {
        val reference = PlaybackReference()
        reference.begin(32000)
        reference.append(floatArrayOf(0f, .2f, .4f, .6f, .8f), 0, 5, 0, 0, 1_000_000_000)
        val frame = ShortArray(4)
        reference.frame(1_000_000_000, frame)
        assertEquals(0, frame[0].toInt())
        assertEquals(13106, frame[1].toInt())
        // The last known sample remains valid; after it, there is no reference yet.
        assertEquals(26213, frame[2].toInt())
        assertEquals(0, frame[3].toInt())
        reference.clear(); reference.frame(1_000_000_000, frame)
        assertTrue(frame.all { it == 0.toShort() })
    }
    @Test fun delayedOwnPlaybackIsRejectedButIndependentNearSpeechSurvives() {
        val reference = PlaybackReference()
        reference.begin(16000)
        val wave = FloatArray(16000) { i -> (.3 * sin(i * .061) + .15 * sin(i * .093)).toFloat() }
        reference.append(wave, 0, wave.size, 0, 0, 1_000_000_000)
        val gate = EchoGate(reference)
        var echoEnergy = 0L
        var nearEnergy = 0L
        for (index in 0 until 80) {
            val nanos = 1_000_000_000L + index * 10_000_000L
            val mic = ShortArray(160) { n ->
                val i = index * 160 + n
                val echo = if (i >= 800) wave[i - 800] * 6000 else 0f
                val near = if (index >= 50) 12000 * sin(i * .127) else 0.0
                (echo + near).toInt().toShort()
            }
            val clean = gate.process(mic, nanos)
            if (index in 25..45) echoEnergy += clean.sumOf { it.toLong() * it }
            if (index >= 60) nearEnergy += clean.sumOf { it.toLong() * it }
        }
        assertEquals("Echo must not reach ASR", 0L, echoEnergy)
        assertTrue("Independent near speech was incorrectly muted", nearEnergy > 100_000_000)
    }
}
