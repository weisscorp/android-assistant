package local.voice

import android.content.Intent
import android.media.AudioManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.WaveReader
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ToneRuntimeTest {
    @Test fun systemRecognizerReusesServiceButChangesEngineAfterCancel() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("settings", 0)
        val old = prefs.all["tone_asr"] as? Boolean
        val oldEngine = prefs.getString("stt_engine", null)
        val ready = Semaphore(0)
        val answers = CopyOnWriteArrayList<String>()
        var recognizer: android.speech.SpeechRecognizer? = null
        val audio = context.getSystemService(AudioManager::class.java)
        fun awaitReleased() {
            val until = System.currentTimeMillis() + 5000
            while (audio.activeRecordingConfigurations.isNotEmpty() && System.currentTimeMillis() < until) Thread.sleep(50)
            assertTrue("Recognition service kept its microphone", audio.activeRecordingConfigurations.isEmpty())
        }
        try {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                recognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(context,
                    android.content.ComponentName(context, LocalRecognitionService::class.java))
                recognizer!!.setRecognitionListener(object : android.speech.RecognitionListener {
                    override fun onReadyForSpeech(params: android.os.Bundle) { answers.add(params.getString("local.voice.speech_engine").orEmpty()); ready.release() }
                    override fun onError(error: Int) { answers.add("error:$error"); ready.release() }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray) {}
                    override fun onEndOfSpeech() {}
                    override fun onResults(results: android.os.Bundle) {}
                    override fun onPartialResults(partialResults: android.os.Bundle) {}
                    override fun onEvent(eventType: Int, params: android.os.Bundle) {}
                })
            }
            for (engine in listOf(SpeechEngine.TONE, SpeechEngine.VOSK, SpeechEngine.GIGAAM, SpeechEngine.TONE)) {
                ready.drainPermits(); answers.clear()
                SpeechEngines.select(context, engine)
                instrumentation.runOnMainSync {
                    recognizer!!.startListening(Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "ru-RU"))
                }
                assertTrue("System recognizer never became ready: $answers", ready.tryAcquire(30, TimeUnit.SECONDS))
                assertEquals(engine.id, answers.single())
                assertEquals(1, audio.activeRecordingConfigurations.size)
                instrumentation.runOnMainSync { recognizer!!.cancel() }
                awaitReleased()
            }
        } finally {
            instrumentation.runOnMainSync { recognizer?.destroy() }
            awaitReleased()
            val edit = prefs.edit()
            if (old == null) edit.remove("tone_asr") else edit.putBoolean("tone_asr", old)
            if (oldEngine == null) edit.remove("stt_engine") else edit.putString("stt_engine", oldEngine)
            assertTrue(edit.commit())
        }
    }

    @Test fun bundledDecoderReadsRecordedReferenceInTenMillisecondChunks() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val model = ToneModels.open(instrumentation.targetContext)
        try {
            val wave = WaveReader.readWave(instrumentation.context.assets, "tone-human-reference.wav")
            assertEquals(8000, wave.sampleRate)
            val pcm = ShortArray(wave.samples.size * 2) {
                val i = it / 2
                val sample = if (it % 2 == 0) wave.samples[i] else (wave.samples[i] + wave.samples[(i + 1).coerceAtMost(wave.samples.lastIndex)]) / 2
                (sample * 32767).toInt().coerceIn(-32768, 32767).toShort()
            }
            ToneDecoder(model).use { decoder ->
                for (start in pcm.indices step 160) decoder.accept(pcm.copyOfRange(start, minOf(start + 160, pcm.size)))
                var endpoint = false
                repeat(250) { if (decoder.accept(ShortArray(160))) endpoint = true }
                assertTrue("Silence did not end the streaming utterance", endpoint)
                val text = decoder.finish()
                assertEquals("ну сейчас к тебе приедет бригада давай давай я жду", text)
                assertEquals("Finalization must be idempotent", text, decoder.finish())
            }
        } finally { model.release() }
    }

    @Test fun selectedEngineSurvivesSettingChangeAndReleasesTheOnlyMicrophone() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("settings", 0)
        val old = prefs.all["tone_asr"] as? Boolean
        val audio = context.getSystemService(AudioManager::class.java)
        val statuses = CopyOnWriteArrayList<String>()
        val listening = Semaphore(0)
        var conversation: VoiceConversation? = null
        fun start() {
            statuses.clear(); listening.drainPermits()
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                conversation = VoiceConversation(context, { status ->
                    statuses.add(status)
                    if (status == "Слушаю…") listening.release()
                }, {}, requireHeadset = false).also { it.start() }
            }
            assertTrue("No listening: $statuses", listening.tryAcquire(60, TimeUnit.SECONDS))
            assertEquals("One PCM owner required", 1, audio.activeRecordingConfigurations.size)
        }
        fun close() {
            instrumentation.runOnMainSync { conversation?.close(); conversation = null }
            val until = System.currentTimeMillis() + 5000
            while (audio.activeRecordingConfigurations.isNotEmpty() && System.currentTimeMillis() < until) Thread.sleep(50)
            assertTrue("Microphone survived close", audio.activeRecordingConfigurations.isEmpty())
        }
        try {
            assertTrue(prefs.edit().putBoolean("tone_asr", true).commit())
            start()
            assertTrue(prefs.edit().putBoolean("tone_asr", false).commit())
            instrumentation.runOnMainSync { conversation!!.respond("который час") }
            assertTrue("No listening after actual TTS: $statuses", listening.tryAcquire(30, TimeUnit.SECONDS))
            assertEquals(1, audio.activeRecordingConfigurations.size)
            close()
            assertEquals("t-one", org.json.JSONObject(SessionDiagnostics.last(context)).getString("speechEngine"))
            start(); close()
            assertEquals("vosk", org.json.JSONObject(SessionDiagnostics.last(context)).getString("speechEngine"))
        } finally {
            close()
            val edit = prefs.edit()
            if (old == null) edit.remove("tone_asr") else edit.putBoolean("tone_asr", old)
            assertTrue(edit.commit())
        }
    }
}
