package local.voice

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sin

/** Real bundled Silero, no capture, playback or user recordings. */
class SpeechEndpointRuntimeTest {
    private class Sink : SpeechDecoder {
        var accepted = 0
        var nonzero = 0
        override fun accept(pcm: ShortArray): Boolean { accepted += pcm.size; nonzero += pcm.count { it.toInt()!=0 }; return false }
        override fun partial() = "noise hallucination"
        override fun result() = "noise hallucination"
        override fun finish() = "noise hallucination"
        override fun close() {}
    }
    @Test fun steadyQuietNoiseAndHumTimeOutWithoutAFalseTranscriptOrErasingAudio() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (amplitude in listOf(25,150,500)) {
            val vad = speechVad(context)
            val sink = Sink()
            val random = java.util.Random(42)
            try {
                EndpointDecoder(sink,vad).use { decoder ->
                    var ended = false
                    var frame = 0
                    while (!ended && frame < 600) {
                        val pcm = ShortArray(160) { i ->
                            val n = frame*160+i
                            (amplitude*(.5*sin(n*2*Math.PI*100/16000)+.25*random.nextGaussian())).toInt().toShort()
                        }
                        ended = decoder.accept(pcm); frame++
                    }
                    assertTrue("Noise amplitude=$amplitude kept listening for ${frame*10}ms", ended && frame in 500..520)
                    assertEquals("Noise hallucinated a transcript", "", decoder.result())
                    assertTrue("VAD must observe, never zero original PCM", sink.nonzero > sink.accepted/2)
                }
            } finally { vad.release() }
        }
    }
}
