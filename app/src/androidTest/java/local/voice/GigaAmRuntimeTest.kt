package local.voice

import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/** Synthetic PCM only: no microphone, playback, cloud, user recordings or scheduled alarms. */
class GigaAmRuntimeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun legacyChoiceAndNewChoiceAreStableForEachSpeechInstance() {
        val prefs = context.getSharedPreferences("settings", 0)
        val tone = prefs.all["tone_asr"] as? Boolean
        val engine = prefs.getString("stt_engine", null)
        try {
            prefs.edit().remove("stt_engine").putBoolean("tone_asr", true).commit()
            assertEquals(SpeechEngine.TONE, SpeechEngines.selected(context))
            prefs.edit().putBoolean("tone_asr", false).commit()
            assertEquals(SpeechEngine.VOSK, SpeechEngines.selected(context))
            SpeechEngines.select(context, SpeechEngine.GIGAAM)
            val speech = OfflineSpeech(context)
            try {
                SpeechEngines.select(context, SpeechEngine.TONE)
                assertEquals("gigaam-v3", speech.engineName)
                assertEquals(SpeechEngine.TONE, SpeechEngines.selected(context))
                assertFalse(prefs.contains("stt_engine"))
            } finally { speech.close() }
        } finally {
            prefs.edit().apply {
                if (tone == null) remove("tone_asr") else putBoolean("tone_asr", tone)
                if (engine == null) remove("stt_engine") else putString("stt_engine", engine)
            }.commit()
        }
        assertTrue(context.getSystemService(AudioManager::class.java).activeRecordingConfigurations.isEmpty())
    }

    @Test fun bundledGigaAmRecognizesNinetyMinuteCommandsAndSavedNoteOffline() {
        assumeTrue(Build.HARDWARE in listOf("ranchu", "goldfish"))
        val loadedAt = SystemClock.elapsedRealtime()
        val giga = WarmSpeechModels.giga.take { GigaAmModels.open(context) }
        var returnedToPool = false
        val vosk = AssetModels.unpack(context, "speech-ru.zip", "speech-ru", "vosk-small-ru-0.22")
        val voice = AssetModels.unpack(context, "voice-ru.zip", "voice-ru", "piper-ruslan-int8-v1")
        val tts = OfflineTts(config = OfflineTtsConfig(model = OfflineTtsModelConfig(vits = OfflineTtsVitsModelConfig(
            model = voice.listFiles()!!.single { it.name.endsWith(".onnx") }.absolutePath,
            tokens = File(voice, "tokens.txt").absolutePath, dataDir = File(voice, "espeak-ng-data").absolutePath), numThreads = 2)))
        val report = org.json.JSONObject().put("loadMillis", SystemClock.elapsedRealtime() - loadedAt)
        fun synthesize(text: String): ShortArray {
            val audio = tts.generate(text, sid = 0, speed = 1f)
            return ShortArray(audio.samples.size * 16000 / audio.sampleRate) { i ->
                val at = i.toDouble() * audio.sampleRate / 16000
                val left = at.toInt().coerceAtMost(audio.samples.lastIndex)
                val right = (left + 1).coerceAtMost(audio.samples.lastIndex)
                ((audio.samples[left] * (1 - (at - left)) + audio.samples[right] * (at - left)) * 32767).toInt().coerceIn(-32768, 32767).toShort()
            }
        }
        try {
            Model(vosk.absolutePath).use { model ->
                for (phrase in listOf("Поставь таймер на полтора часа", "Поставь будильник через полтора часа", "Открой Спотифай")) {
                    val pcm = synthesize(phrase)
                    GigaAmDecoder(giga, Recognizer(model, 16000f)).use { decoder ->
                        var ended = false
                        for (start in pcm.indices step 160) {
                            ended = decoder.accept(pcm.copyOfRange(start, minOf(start + 160, pcm.size))) || ended
                        }
                        repeat(300) { if (!ended) ended = decoder.accept(ShortArray(160)) }
                        assertTrue("No endpoint for $phrase", ended)
                        assertEquals("No Vosk guesses as GigaAM partials", "", decoder.partial())
                        val began = SystemClock.elapsedRealtime()
                        val text = decoder.finish()
                        report.put(phrase, org.json.JSONObject().put("text", text)
                            .put("decodeMillis", SystemClock.elapsedRealtime() - began).put("audioMillis", pcm.size / 16))
                        assertEquals(text, decoder.finish())
                        when {
                            phrase.contains("таймер") -> {
                                val command = VoiceCommands.parse(text)
                                assertTrue("$phrase -> $text", command is VoiceCommand.Clock)
                                assertEquals(5400L, (command as VoiceCommand.Clock).request.seconds)
                            }
                            phrase.contains("будильник") -> {
                                val command = VoiceCommands.parse(text)
                                assertTrue("$phrase -> $text", command is VoiceCommand.Clock)
                                assertEquals(5400L, (command as VoiceCommand.Clock).request.moment?.delaySeconds)
                            }
                            else -> {
                                // Foreign names can remain imperfect even with GigaAM.
                                // Require a usable launch request and an existing confirmation-only
                                // suggestion, not a fabricated correction of the ASR transcript.
                                val command = VoiceCommands.parse(text.lowercase())
                                assertTrue(text, command is VoiceCommand.Open)
                                assertTrue(text, AppSpeechNames.variants("Spotify").any {
                                    AppSpeechNames.suggestionScore((command as VoiceCommand.Open).app, it) > 0
                                })
                            }
                        }
                    }
                }
            }
            WarmSpeechModels.giga.put(giga); returnedToPool = true
            val previous = SpeechEngines.selected(context)
            val store = AudioNotes(context)
            val note = store.create(); val id = note.getString("id")
            try {
                SpeechEngines.select(context, SpeechEngine.GIGAAM)
                // Exercise the same recognizer pool used by production note transcription.
                val pcm = synthesize("Купить молоко завтра")
                val bytes = ByteArray(pcm.size * 2)
                pcm.forEachIndexed { i, value -> bytes[i*2] = value.toByte(); bytes[i*2+1] = (value.toInt() shr 8).toByte() }
                store.audio(id).writeBytes(AudioNotes.wavHeader(bytes.size) + bytes); store.finalize(id)
                AudioNoteTranscriber.enqueue(context, id)
                val until = SystemClock.elapsedRealtime() + 90000
                var saved = note
                do { Thread.sleep(100); saved = store.local().single { it.getString("id") == id } }
                while (saved.getString("status") == "pending" && SystemClock.elapsedRealtime() < until)
                assertEquals(saved.toString(), "ready", saved.getString("status"))
                assertEquals("gigaam-v3", saved.getString("engine"))
                assertTrue(saved.toString(), saved.getString("text").contains("молоко"))
                report.put("note", saved.getString("text"))
            } finally {
                SpeechEngines.select(context, previous)
                store.audio(id).delete(); File(context.filesDir, "audio-notes/$id.json").delete()
            }
            assertTrue(context.getSystemService(AudioManager::class.java).activeRecordingConfigurations.isEmpty())
        } finally {
            File(context.cacheDir, "gigaam-runtime.json").writeText(report.toString(2))
            tts.release(); if (!returnedToPool) giga.release(); WarmSpeechModels.trim()
        }
    }
}
