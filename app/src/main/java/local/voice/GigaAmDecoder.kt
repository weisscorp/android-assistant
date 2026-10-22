package local.voice

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.File
import org.vosk.Recognizer

internal object GigaAmModels {
    fun open(context: Context): OfflineRecognizer {
        val dir = AssetModels.unpack(context, "speech-gigaam-ru.zip", "speech-gigaam-ru",
            "gigaam-v3-rnnt-int8-0424d626")
        return OfflineRecognizer(config = OfflineRecognizerConfig(modelConfig = OfflineModelConfig(
            transducer = OfflineTransducerModelConfig(
                encoder = File(dir, "encoder.int8.onnx").absolutePath,
                decoder = File(dir, "decoder.onnx").absolutePath,
                joiner = File(dir, "joiner.onnx").absolutePath),
            tokens = File(dir, "tokens.txt").absolutePath, numThreads = 2)))
    }
}

/** Vosk supplies endpoint timing only. Every returned word is decoded by GigaAM.
 * PCM comes from the existing single capture owner; no network or extra microphone.
 * Notes are segmented at pauses or 20 seconds, keeping native inference bounded. */
internal class GigaAmDecoder(private val decoder: OfflineRecognizer,
    private val endpoint: Recognizer) : SpeechDecoder {
    private val samples = FloatArray(16000 * 20)
    private var size = 0
    private var finalText: String? = null
    override fun accept(pcm: ShortArray): Boolean {
        check(finalText == null) { "Реплика уже завершена." }
        check(size + pcm.size <= samples.size) { "Слишком длинный фрагмент распознавания." }
        for (sample in pcm) samples[size++] = sample / 32768f
        return endpoint.acceptWaveForm(pcm, pcm.size) || size == samples.size
    }
    // This is an utterance model: do not present Vosk guesses as GigaAM partials.
    override fun partial() = ""
    override fun result(): String {
        if (size == 0) return ""
        val stream = decoder.createStream()
        try {
            stream.acceptWaveform(samples.copyOf(size), 16000)
            decoder.decode(stream)
            return decoder.getResult(stream).text.trim()
        } finally {
            stream.release(); size = 0; endpoint.reset()
        }
    }
    override fun finish(): String = finalText ?: result().also { finalText = it }
    override fun close() = endpoint.close()
}
