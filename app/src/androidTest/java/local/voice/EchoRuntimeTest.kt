package local.voice

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.k2fsa.sherpa.onnx.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

@RunWith(AndroidJUnit4::class)
class EchoRuntimeTest {
    private data class Observation(val interrupted: Boolean, val transcript: String)
    /** Actual native DSP + actual bundled TTS/ASR, with known delayed echo and near speech. */
    @Test fun ownSpokenStopDoesNotCancelButNearSpokenStopDoes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = AssetModels.unpack(context, "voice-ru.zip", "voice-ru", "piper-ruslan-int8-v1")
        val voice = OfflineTts(config = OfflineTtsConfig(model = OfflineTtsModelConfig(vits = OfflineTtsVitsModelConfig(
            model = directory.listFiles()!!.single { it.name.endsWith(".onnx") }.absolutePath,
            tokens = File(directory, "tokens.txt").absolutePath,
            dataDir = File(directory, "espeak-ng-data").absolutePath
        ), numThreads = 2)))
        try {
            fun synthesize(text: String, speed: Float): FloatArray {
                val generated = voice.generate(text, sid = 0, speed = speed)
                return FloatArray(generated.samples.size * 16000 / generated.sampleRate) { i ->
                    val at = i.toDouble() * generated.sampleRate / 16000
                    val left = at.toInt().coerceAtMost(generated.samples.lastIndex)
                    val right = (left + 1).coerceAtMost(generated.samples.lastIndex)
                    (generated.samples[left] * (1 - (at - left)) + generated.samples[right] * (at - left)).toFloat()
                }
            }
            fun saved(name: String) = File(context.cacheDir, name).inputStream().buffered().let { input -> java.io.DataInputStream(input).use { data -> FloatArray(data.readInt()) { data.readFloat() } } }
            val replay = InstrumentationRegistry.getArguments().getString("echo_probe_replay") == "true"
            fun recorded(name: String) = InstrumentationRegistry.getInstrumentation().context.assets.open(name).let { input ->
                java.io.DataInputStream(input).use { data -> FloatArray(data.readInt()) { data.readFloat() } }
            }
            val variants = if (replay) listOf(saved("echo-own.fixture") to saved("echo-near.fixture")) else listOf(
                synthesize("Помощник читает инструкцию. Для завершения разговора скажите стоп. Ответ продолжается.", 1f) to synthesize("Стоп!", .85f),
                recorded("echo-own-quiet-stop.fixture") to recorded("echo-near-quiet-stop.fixture"))
            val speech = AssetModels.unpack(context, "speech-ru.zip", "speech-ru", "vosk-small-ru-0.22")
            for ((own, near) in variants) {
            Model(speech.absolutePath).use { model ->
                fun run(nearVolume: Float, useGate: Boolean = true, useDsp: Boolean = true, nearOnly: Boolean = false): Observation {
                    val reference = PlaybackReference().apply { begin(16000) }
                    val gate = EchoGate(reference)
                    val result = StringBuilder()
                    var heardStop = false
                    var candidate = ""
                    var candidateSince = 0L
                    var lastPartial = 0L
                    EchoProcessor().use { echo -> Recognizer(model, 16000f, "[\"стоп\",\"[unk]\"]").use { decoder ->
                        val play = ShortArray(160); val mic = ShortArray(160); val processed = ShortArray(160)
                        // Two repeated replies: adaptation first, then near speech over playback.
                        val nearAt = own.size + 16000
                        val total = maxOf(own.size * 2, nearAt + near.size) + 16000
                        for (start in 0 until total step 160) {
                            val nanos = 1_000_000_000L + start * 1_000_000_000L / 16000
                            val outgoing = FloatArray(160) { n -> if (start + n < own.size * 2) own[(start + n) % own.size] else 0f }
                            reference.append(outgoing, 0, 160, start.toLong(), start.toLong(), nanos)
                            reference.frame(nanos, play)
                            for (n in mic.indices) {
                                val i = start + n
                                fun delayed(delay: Int) = if (i >= delay && i - delay < own.size * 2) own[(i - delay) % own.size] else 0f
                                val nearby = if (nearVolume > 0 && i - nearAt in near.indices) near[i - nearAt] else 0f
                                mic[n] = ((delayed(3200) * .45f + delayed(3240) * .12f + nearby * nearVolume) * 32767).toInt().coerceIn(-32768, 32767).toShort()
                            }
                            if (useDsp) echo.process(mic, play, processed) else mic.copyInto(processed)
                            val clean = if (useGate) gate.process(processed, nanos, mic) else processed
                            if (nearOnly && (start < nearAt || start > nearAt + near.size + 8000)) continue
                            val ended = decoder.acceptWaveForm(clean, clean.size)
                            val now = nanos / 1_000_000
                            if (ended) {
                                val phrase = JSONObject(decoder.result).optString("text").trim()
                                if (phrase == "стоп") heardStop = true
                                result.append(phrase).append(' ')
                            } else if (now - lastPartial > 80) {
                                lastPartial = now
                                val phrase = JSONObject(decoder.partialResult).optString("partial").trim()
                                if (phrase != candidate) { candidate = phrase; candidateSince = now }
                                if (phrase == "стоп" && now - candidateSince >= 160) heardStop = true
                            }
                        }
                        result.append(JSONObject(decoder.finalResult).optString("text"))
                    } }
                    return Observation(heardStop, result.toString())
                }
                val echoOnly = run(0f)
                assertFalse("Our own instruction triggered stop: $echoOnly", echoOnly.interrupted)
                for (volume in listOf(.9f, .25f)) {
                    val interrupted = run(volume)
                    if (!interrupted.interrupted) {
                        for ((name, samples) in listOf("echo-own.fixture" to own, "echo-near.fixture" to near)) {
                            java.io.DataOutputStream(File(context.cacheDir, name).outputStream().buffered()).use { data -> data.writeInt(samples.size); samples.forEach(data::writeFloat) }
                        }
                        val ungated = run(volume, useGate = false, nearOnly = true)
                        val unprocessed = run(volume, useGate = false, useDsp = false, nearOnly = true)
                        fail("Near speech at volume $volume lost: gated=$interrupted; nativeOnly=$ungated; raw=$unprocessed")
                    }
                }
            }
            }
        } finally { voice.release() }
    }
}
