package local.voice

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

class SileroRuntimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun reference() = JSONObject(instrumentation.context.assets.open("silero-reference.json").bufferedReader().use { it.readText() })
    @Test fun frontendMatchesOfficialStressYoHomographsAndTokenizer() {
        val voice = SileroVoice(context, "baya")
        try {
            val ref = reference()
            val frontend = ref.getJSONArray("frontend")
            for (i in 0 until frontend.length()) {
                val test = frontend.getJSONObject(i); val text = test.getString("text")
                val accented = voice.accentuate(text)
                assertEquals(text, test.getString("accented"), accented)
                val ids = test.getJSONArray("type_ids")
                assertArrayEquals(text, LongArray(ids.length()) { ids.getLong(it) }, voice.typeIds(text, accented.length + 2))
            }
            val tokenizer = ref.getJSONArray("tokenizer")
            for (i in 0 until tokenizer.length()) {
                val test = tokenizer.getJSONObject(i); val ids = test.getJSONArray("ids")
                assertArrayEquals(test.getString("text"), LongArray(ids.length()) { ids.getLong(it) }, voice.tokenize(test.getString("text")))
            }
            assertEquals("заряд пятьдесят два процентов. открой телеграм.", voice.normalized("Заряд 52%. Открой Telegram."))
        } finally { voice.close() }
    }
    @Test fun waveformMatchesPythonAcrossVoicesAndQuestions() {
        val tests = reference().getJSONArray("waveform")
        for (i in 0 until tests.length()) {
            val test = tests.getJSONObject(i)
            val voice = SileroVoice(context, test.getString("speaker"))
            try {
                val raw = instrumentation.context.assets.open(test.getString("file")).use { it.readBytes() }
                val floats = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                val expected = FloatArray(floats.remaining()); floats.get(expected)
                var actual = floatArrayOf()
                val start = System.nanoTime()
                voice.generate(test.getString("text"), { true }) { actual += it; true }
                assertEquals("Wave length for case $i", expected.size, actual.size)
                assertTrue(actual.all { it.isFinite() })
                val energy = expected.sumOf { it.toDouble() * it }
                val error = expected.indices.sumOf { val d = actual[it] - expected[it]; d.toDouble() * d }
                val relative = sqrt(error / energy)
                android.util.Log.i("SileroRuntime", "case=$i samples=${actual.size} relativeRms=$relative millis=${(System.nanoTime() - start)/1000000}")
                assertTrue("Wave changed for case $i: relative RMS $relative", relative < 0.02)
            } finally { voice.close() }
        }
    }
    @Test fun otherSpeakersAndNativePitchRateControlsWork() {
        val prefs = context.getSharedPreferences("settings", 0)
        val oldPitch = prefs.all["tts_pitch"] as? Int; val oldRate = prefs.all["tts_rate"] as? Int
        try {
            prefs.edit().putInt("tts_pitch", 0).putInt("tts_rate", 100).commit()
            for (speaker in listOf("aidar", "kseniya", "xenia", "baya")) {
                val voice = SileroVoice(context, speaker)
                try {
                    var base = floatArrayOf()
                    voice.generate("Привет, я всё ещё здесь.", { true }) { base += it; true }
                    assertTrue(base.size > 4800 && base.all { it.isFinite() })
                    if (speaker == "baya") {
                        prefs.edit().putInt("tts_pitch", -10).putInt("tts_rate", 120).commit()
                        var tuned = floatArrayOf()
                        voice.generate("Привет, я всё ещё здесь.", { true }) { tuned += it; true }
                        assertTrue("Speed did not shorten speech", tuned.size < base.size)
                        assertTrue(tuned.all { it.isFinite() })
                        assertTrue("Pitch did not change samples", base.indices.take(minOf(base.size, tuned.size)).any { kotlin.math.abs(base[it] - tuned[it]) > 0.01f })
                    }
                } finally { voice.close() }
            }
        } finally {
            prefs.edit().apply {
                if (oldPitch == null) remove("tts_pitch") else putInt("tts_pitch", oldPitch)
                if (oldRate == null) remove("tts_rate") else putInt("tts_rate", oldRate)
            }.commit()
        }
    }
    @Test fun cancellationStopsBeforeNextInferenceChunk() {
        val voice = SileroVoice(context, "baya")
        try {
            var chunks = 0
            voice.generate("Привет! ".repeat(80), { chunks == 0 }) { chunks++; true }
            assertEquals(1, chunks)
            assertEquals(0, SileroVoice.chunks("").size)
        } finally { voice.close() }
    }
    @Test fun selectedVoicePlaysAndReleasesAudioThenPiperStillWorks() {
        val prefs = context.getSharedPreferences("settings", 0)
        val previous = prefs.getString("tts_model", null)
        val custom = prefs.all["custom_voice"] as? Boolean
        val closed = Semaphore(0)
        try {
            for (selection in listOf("baya", "ruslan")) {
                ModelCatalog.selectVoice(context, selection)
                val voice = LocalVoice(context)
                val ready = Semaphore(0); val spoken = Semaphore(0)
                var error: String? = null
                try {
                    instrumentation.runOnMainSync { voice.initialize { error = it; ready.release() } }
                    assertTrue("Voice init timeout", ready.tryAcquire(60, TimeUnit.SECONDS)); assertNull(error)
                    instrumentation.runOnMainSync { voice.speak("Привет! Ёжик живёт у ёлки.") { error = it; spoken.release() } }
                    assertTrue("Playback timeout", spoken.tryAcquire(30, TimeUnit.SECONDS)); assertNull(error)
                } finally { voice.close { closed.release() }; assertTrue(closed.tryAcquire(10, TimeUnit.SECONDS)) }
            }
        } finally {
            prefs.edit().apply { if (previous == null) remove("tts_model") else putString("tts_model", previous)
                if (custom == null) remove("custom_voice") else putBoolean("custom_voice", custom) }.commit()
        }
    }
}
