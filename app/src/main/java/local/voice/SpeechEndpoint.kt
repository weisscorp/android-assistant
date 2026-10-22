package local.voice

import android.content.Context
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/** PCM-time decisions, independent of ASR text, microphone gain and decoder loading speed. */
internal class SpeechPause {
    var heardSpeech = false; private set
    private var windows = 0
    private var voicedWindows = 0
    private var lastVoice = 0
    private var voiced = false
    fun accept(probability: Float): Boolean {
        windows++
        voiced = probability >= if (voiced) .35f else .5f
        if (voiced) {
            voicedWindows++
            lastVoice = windows
            if (voicedWindows >= 3) heardSpeech = true // 96ms, reject isolated clicks.
        } else if (!heardSpeech) voicedWindows = 0
        return if (heardSpeech) windows - lastVoice >= 25 // 800ms after classifier hangover.
            else windows >= 157 // About five seconds without speech, not the 15s utterance cap.
    }
}

internal fun speechVad(context: Context) = Vad(context.assets, VadModelConfig(
    sileroVadModelConfig = SileroVadModelConfig(model = "silero_vad.onnx", windowSize = 512),
    sampleRate = 16000, numThreads = 1))

/** Observe original ASR PCM without zeroing, trimming or starting another capture. */
internal class EndpointDecoder(private val decoder: SpeechDecoder, private val vad: Vad) : SpeechDecoder {
    private val pause = SpeechPause()
    private val window = FloatArray(512)
    private var size = 0
    private var boundary = false
    private var finalText: String? = null
    init { vad.reset() }
    override fun accept(pcm: ShortArray): Boolean {
        check(finalText == null)
        for (value in pcm) {
            window[size++] = value / 32768f
            if (size == window.size) {
                size = 0
                // Bounded analysis-only gain keeps quiet speech detectable; ASR/raw PCM stay untouched.
                val rms = kotlin.math.sqrt(window.sumOf { it.toDouble()*it } / window.size)
                val gain = if (rms > 0) (.04 / rms).coerceIn(1.0, 8.0).toFloat() else 1f
                val analysis = FloatArray(window.size) { (window[it]*gain).coerceIn(-1f,1f) }
                boundary = pause.accept(vad.compute(analysis)) || boundary
            }
        }
        val nativeEnd = decoder.accept(pcm)
        return boundary || nativeEnd && pause.heardSpeech
    }
    override fun partial() = if (pause.heardSpeech) decoder.partial() else ""
    override fun result(): String = finalText ?: (if (!pause.heardSpeech) "" else
        if (boundary) decoder.finish() else decoder.result()).also { finalText = it }
    override fun finish(): String = finalText ?: (if (pause.heardSpeech) decoder.finish() else "").also { finalText = it }
    override fun close() = decoder.close() // Vad belongs to the capture owner; reused after reset.
}
