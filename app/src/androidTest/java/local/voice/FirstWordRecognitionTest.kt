package local.voice

import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.*
import org.junit.Assert.*
import org.junit.Test
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/** Bundled native decoders and DSP, synthetic speech only. No physical microphone or host playback. */
class FirstWordRecognitionTest {
    @Test fun allThreeDecodersKeepTheQuestionAndProduceFour() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = AssetModels.unpack(context, "voice-ru.zip", "voice-ru", "piper-ruslan-int8-v1")
        val tts = OfflineTts(config = OfflineTtsConfig(model = OfflineTtsModelConfig(vits = OfflineTtsVitsModelConfig(
            model = dir.listFiles()!!.single { it.name.endsWith(".onnx") }.absolutePath,
            tokens = File(dir, "tokens.txt").absolutePath, dataDir = File(dir, "espeak-ng-data").absolutePath), numThreads = 2)))
        val generated = try { tts.generate("Сколько будет два плюс два?", sid = 0, speed = 1f) } finally { tts.release() }
        val speech = ShortArray(generated.samples.size * 16000 / generated.sampleRate) { i ->
            (generated.samples[(i.toLong() * generated.sampleRate / 16000).toInt()] * 32767).toInt().coerceIn(-32768,32767).toShort()
        }
        val reference = PlaybackReference().apply { begin(16000) }
        val outgoing = ReadyCue.samples().map { it / 32768f * .1f }.toFloatArray()
        val origin = 1_000_000_000L
        reference.append(outgoing, 0, outgoing.size, 0, 0, origin)
        val gate = EchoGate(reference)
        val withCue = ShortArray(speech.size)
        EchoProcessor().use { echo ->
            for (start in speech.indices step 160) {
                val at = origin + start * 1_000_000_000L / 16000
                val play = ShortArray(160); reference.frame(at, play)
                val mic = ShortArray(160) { i ->
                    val own = if (start+i >= 320) outgoing.getOrNull(start+i-320)?.times(32768f * .6f) ?: 0f else 0f
                    ((speech.getOrNull(start+i)?.toInt() ?: 0) + own).toInt().coerceIn(-32768,32767).toShort()
                }
                val processed = ShortArray(160); echo.process(mic,play,processed)
                val clean = if (reference.hasRecentAudio(at)) gate.process(processed,at,mic) else mic
                clean.copyInto(withCue,start,0,minOf(160,withCue.size-start))
            }
        }
        val voskDir = AssetModels.unpack(context, "speech-ru.zip", "speech-ru", "vosk-small-ru-0.22")
        Model(voskDir.absolutePath).use { model ->
            for (engine in SpeechEngine.entries) {
                val tone = if (engine == SpeechEngine.TONE) ToneModels.open(context) else null
                val giga = if (engine == SpeechEngine.GIGAAM) GigaAmModels.open(context) else null
                try {
                    val quietRandom = java.util.Random(91)
                    val quiet = ShortArray(speech.size) { (speech[it]*.15 + quietRandom.nextGaussian()*20).toInt().toShort() }
                    for ((variant,pcm) in listOf(speech,withCue,quiet).withIndex()) {
                    val vad = speechVad(context)
                    val decoder: SpeechDecoder = EndpointDecoder(when(engine) {
                        SpeechEngine.VOSK -> VoskDecoder(Recognizer(model,16000f))
                        SpeechEngine.TONE -> ToneDecoder(tone!!)
                        SpeechEngine.GIGAAM -> GigaAmDecoder(giga!!,Recognizer(model,16000f))
                    }, vad)
                    decoder.use {
                        // Match ordinary listening: when no outgoing reference is present, original PCM reaches ASR intact.
                        for (start in pcm.indices step 160) decoder.accept(pcm.copyOfRange(start,minOf(start+160,pcm.size)))
                        var ended = false
                        val random = java.util.Random(64)
                        var tailFrames = 0
                        repeat(180) { if (!ended) {
                            ended = decoder.accept(ShortArray(160) { (random.nextGaussian()*60).toInt().toShort() })
                            tailFrames++
                        } }
                        assertTrue("${engine.id}, variant=$variant: background held endpoint for ${tailFrames*10}ms", ended && tailFrames <= 150)
                        val text = if (ended) decoder.result() else decoder.finish()
                        assertTrue("${engine.id}: first word missing: $text", text.lowercase().startsWith("сколько"))
                        assertEquals("${engine.id}: $text", "4.", ArithmeticReply.answer(text))
                    }
                    vad.release()
                    }
                } finally { tone?.release(); giga?.release() }
            }
        }
    }
}
