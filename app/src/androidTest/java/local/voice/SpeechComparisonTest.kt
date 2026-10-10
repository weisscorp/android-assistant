package local.voice

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/** Explicit external-model benchmark; not a hardware/microphone or ordinary UI test. */
@RunWith(AndroidJUnit4::class)
class SpeechComparisonTest {
    @Test fun bundledUnconstrainedAsrRecognizesTelegramRequests() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (name in listOf("asr-probe/encoder.int8.onnx", "asr-probe/decoder.onnx", "asr-probe/joiner.int8.onnx", "asr-probe/tokens.txt", "tts-float-probe/ru_RU-ruslan-medium.onnx", "tts-float-probe/tokens.txt", "tone-probe/model.onnx", "tone-probe/tokens.txt", "tone-probe/0.wav")) {
            assertTrue("Run tools/prepare_speech_probe.py on the owned AVD first: $name", File(context.filesDir, name).isFile)
        }
        val phrases = listOf("Отправь в телеграме Маша сообщение Буду скоро", "Найди в телеграме Маша", "Открой телеграм")
        val audio = phrases.associateWith(::synthesize)
        val results = audio.mapValues { recognize(it.value) }
        val probe = audio.mapValues { recognize(it.value, probe = true) }
        val alternatives = audio.mapValues { JSONObject(recognize(it.value, alternatives = 10)) }
        val floatAudio = phrases.associateWith { synthesize(it, floatProbe = true) }
        val floatVosk = floatAudio.mapValues { recognize(it.value) }
        val floatZipformer = floatAudio.mapValues { recognize(it.value, probe = true) }
        val tone = audio.mapValues { tone(it.value) }
        val floatTone = floatAudio.mapValues { tone(it.value) }
        val recorded = WaveReader.readWave(File(context.filesDir, "tone-probe/0.wav").absolutePath)
        assertEquals(8000, recorded.sampleRate)
        val recorded8 = ShortArray(recorded.samples.size) { (recorded.samples[it] * 32767).toInt().coerceIn(-32768,32767).toShort() }
        val recorded16 = ShortArray(recorded.samples.size * 2) {
            val i = it / 2
            val sample = if (it % 2 == 0) recorded.samples[i] else (recorded.samples[i] + recorded.samples[(i + 1).coerceAtMost(recorded.samples.lastIndex)]) / 2
            (sample * 32767).toInt().coerceIn(-32768,32767).toShort()
        }
        val reference = JSONObject().put("expected", "ну сейчас к тебе приедет бригада давай давай я жду")
            .put("source", "official T-one example0.wav; recorded file, no phone/headset input")
            .put("sha256", "980b4327e684725859e437f05d15dfa4ec8afb994ef2e8fb356365af76547c41")
            .put("vosk", recognize(recorded16)).put("tone", tone(recorded8, 8000))
        File(context.cacheDir, "telegram-synthetic-asr.json").writeText(JSONObject().put("vosk", JSONObject(results)).put("zipformerProbe", JSONObject(probe)).put("voskAlternatives", JSONObject(alternatives)).put("floatTtsVosk", JSONObject(floatVosk)).put("floatTtsZipformer", JSONObject(floatZipformer)).put("toneProbe", JSONObject(tone)).put("floatTtsTone", JSONObject(floatTone)).put("recordedReference", reference).toString(2))
        assertTrue("Synthetic ASR/intent quality failed: $results", results.values.take(2).all { VoiceCommands.parse(it) is VoiceCommand.Telegram })
        assertEquals(VoiceCommand.Open("телеграм"), VoiceCommands.parse(results.getValue(phrases[2])))
    }
    private fun tone(pcm: ShortArray, sampleRate: Int = 16000): JSONObject {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.filesDir, "tone-probe")
        val started = android.os.SystemClock.elapsedRealtime()
        val decoder = OnlineRecognizer(config = OnlineRecognizerConfig(modelConfig = OnlineModelConfig(
            toneCtc = OnlineToneCtcModelConfig(model = File(directory, "model.onnx").absolutePath),
            tokens = File(directory, "tokens.txt").absolutePath, numThreads = 2)))
        val loaded = android.os.SystemClock.elapsedRealtime()
        val stream = decoder.createStream()
        try {
            // Official T-one example: 8kHz input, 300ms left + 600ms tail padding.
            // Pair-average low-pass before 16kHz -> 8kHz downsampling of the same PCM.
            val samples = if (sampleRate == 8000) FloatArray(pcm.size) { pcm[it] / 32768f }
                else FloatArray(pcm.size / 2) { (pcm[it * 2].toInt() + pcm[it * 2 + 1].toInt()) / 65536f }
            stream.acceptWaveform(FloatArray(2400), 8000)
            stream.acceptWaveform(samples, 8000)
            stream.acceptWaveform(FloatArray(4800), 8000)
            stream.inputFinished()
            while (decoder.isReady(stream)) decoder.decode(stream)
            return JSONObject().put("text", decoder.getResult(stream).text)
                .put("loadMillis", loaded - started).put("decodeMillis", android.os.SystemClock.elapsedRealtime() - loaded)
                .put("inputMillis", pcm.size * 1000L / sampleRate).put("sampleRate", 8000)
                .put("resampling", if (sampleRate == 8000) "original8k recording" else "pair-average 16k to8k; synthetic only, not Bluetooth audio")
        } finally { stream.release(); decoder.release() }
    }
    private fun synthesize(text: String, floatProbe: Boolean = false): ShortArray {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val voice = AssetModels.unpack(context, "voice-ru.zip", "voice-ru", "piper-ruslan-int8-v1")
        val modelDirectory = if (floatProbe) File(context.filesDir, "tts-float-probe") else voice
        val tts = OfflineTts(config = OfflineTtsConfig(model = OfflineTtsModelConfig(vits = OfflineTtsVitsModelConfig(
            model = modelDirectory.listFiles()!!.single { it.name.endsWith(".onnx") }.absolutePath,
            tokens = File(modelDirectory, "tokens.txt").absolutePath, dataDir = File(voice, "espeak-ng-data").absolutePath), numThreads = 2)))
        try {
            val audio = tts.generate(text, sid = 0, speed = 1f)
            return ShortArray(audio.samples.size * 16000 / audio.sampleRate) { i ->
                val at = i.toDouble() * audio.sampleRate / 16000
                val left = at.toInt().coerceAtMost(audio.samples.lastIndex)
                val right = (left + 1).coerceAtMost(audio.samples.lastIndex)
                ((audio.samples[left] * (1 - (at - left)) + audio.samples[right] * (at - left)) * 32767).toInt().coerceIn(-32768,32767).toShort()
            }
        } finally { tts.release() }
    }
    private fun recognize(pcm: ShortArray, probe: Boolean = false, alternatives: Int = 0): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        if (probe) {
            val directory = File(context.filesDir, "asr-probe")
            val decoder = OfflineRecognizer(config = OfflineRecognizerConfig(modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(encoder = File(directory,"encoder.int8.onnx").absolutePath,
                    decoder = File(directory,"decoder.onnx").absolutePath, joiner = File(directory,"joiner.int8.onnx").absolutePath),
                tokens = File(directory,"tokens.txt").absolutePath, numThreads = 2, modelType = "zipformer")))
            val stream = decoder.createStream()
            try {
                stream.acceptWaveform(FloatArray(pcm.size) { pcm[it] / 32768f },16000)
                decoder.decode(stream)
                return decoder.getResult(stream).text
            } finally { stream.release(); decoder.release() }
        }
        val speech = AssetModels.unpack(context, "speech-ru.zip", "speech-ru", "vosk-small-ru-0.22")
        Model(speech.absolutePath).use { model -> Recognizer(model, 16000f).use { decoder ->
            if (alternatives > 0) decoder.setMaxAlternatives(alternatives)
            decoder.acceptWaveForm(pcm, pcm.size)
            val result = decoder.finalResult
            return if (alternatives > 0) result else JSONObject(result).getString("text")
        } }
    }
}
