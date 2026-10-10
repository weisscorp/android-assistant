package local.voice

import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class EchoGateTest {
    @Test fun loudRawEchoDoesNotEraseQuietIndependentCleanedSpeech() {
        val random = Random(41)
        val output = FloatArray(16000) { random.nextFloat() * 2 - 1 }
        val near = FloatArray(16000) { random.nextFloat() * 2 - 1 }
        val reference = PlaybackReference().apply { begin(16000) }
        val gate = EchoGate(reference)
        var nearSurvived = false
        for (start in output.indices step 160) {
            val nanos = 1_000_000_000L + start * 1_000_000_000L / 16000
            reference.append(output, start, 160, start.toLong(), start.toLong(), nanos)
            val raw = ShortArray(160) { i -> ((output[start + i] * .45f + if (start >= 8000) near[start + i] * .05f else 0f) * 32767).toInt().toShort() }
            val cleaned = ShortArray(160) { i -> ((output[start + i] * .01f + if (start >= 8000) near[start + i] * .05f else 0f) * 32767).toInt().toShort() }
            val result = gate.process(cleaned, nanos, raw)
            if (start in 4000 until 8000) assertTrue("Correlated residual echo must be suppressed", result.all { it == 0.toShort() })
            if (start >= 12000 && result.any { it != 0.toShort() }) nearSurvived = true
        }
        assertTrue("Raw loudspeaker dominance must not erase independent cleaned speech", nearSurvived)
    }
}
