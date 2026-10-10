package local.voice

import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineToneCtcModelConfig
import android.content.Context
import java.io.File
import org.json.JSONObject
import org.vosk.Recognizer

/** All decoding happens on OfflineSpeech's PCM worker, never on another microphone. */
internal interface SpeechDecoder : AutoCloseable {
    fun accept(pcm: ShortArray): Boolean
    fun partial(): String
    fun result(): String
    fun finish(): String
}

internal object ToneModels {
    fun open(context: Context): OnlineRecognizer {
        val dir = AssetModels.unpack(context, "speech-tone-ru.zip", "speech-tone-ru", "t-one-ru-2025-09-08-v1")
        return OnlineRecognizer(config = OnlineRecognizerConfig(modelConfig = OnlineModelConfig(
            toneCtc = OnlineToneCtcModelConfig(model = File(dir, "model.onnx").absolutePath),
            tokens = File(dir, "tokens.txt").absolutePath, numThreads = 2)))
    }
}

internal class VoskDecoder(private val decoder: Recognizer) : SpeechDecoder {
    override fun accept(pcm: ShortArray) = decoder.acceptWaveForm(pcm, pcm.size)
    override fun partial() = JSONObject(decoder.partialResult).optString("partial").trim()
    override fun result() = JSONObject(decoder.result).optString("text").trim()
    override fun finish() = JSONObject(decoder.finalResult).optString("text").trim()
    override fun close() = decoder.close()
}

internal class ToneDecoder(private val decoder: OnlineRecognizer) : SpeechDecoder {
    private val stream: OnlineStream = decoder.createStream()
    private var finished = false
    init {
        // sherpa performs its own resampling to the model's 8kHz input.
        stream.acceptWaveform(FloatArray(4800), 16000) // 300ms left padding
    }
    private fun decode() { while (decoder.isReady(stream)) decoder.decode(stream) }
    override fun accept(pcm: ShortArray): Boolean {
        check(!finished)
        stream.acceptWaveform(FloatArray(pcm.size) { pcm[it] / 32768f }, 16000)
        decode()
        return decoder.isEndpoint(stream)
    }
    override fun partial() = decoder.getResult(stream).text.trim()
    override fun result() = partial()
    override fun finish(): String {
        if (!finished) {
            finished = true
            stream.acceptWaveform(FloatArray(9600), 16000) // 600ms tail padding
            stream.inputFinished(); decode()
        }
        return partial()
    }
    override fun close() = stream.release()
}
