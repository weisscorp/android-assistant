package local.voice

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** Declared alongside the voice interactor, so the assistant role also works on AOSP. */
class LocalRecognitionService : RecognitionService() {
    private var speech: OfflineSpeech? = null
    private var active: Callback? = null
    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        if (active != null) { listener.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY); return }
        val language = recognizerIntent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)
        if (language != null && !language.startsWith("ru", true)) { listener.error(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED); return }
        active = listener
        var ready = false
        val selected = if (getSharedPreferences("settings", 0).getBoolean("tone_asr", false)) "t-one" else "vosk"
        if (speech?.engineName != selected) { speech?.close(); speech = null }
        val engine = speech ?: OfflineSpeech(this).also { speech = it }
        engine.listen(false, { text ->
            if (active !== listener) return@listen
            runCatching {
                if (!ready) { ready = true; listener.readyForSpeech(Bundle().apply { putString("local.voice.speech_engine", engine.engineName) }) }
                else listener.partialResults(Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text)) })
            }
        }) { text, error ->
            if (active !== listener) return@listen
            active = null
            runCatching {
                if (error != null) listener.error(SpeechRecognizer.ERROR_AUDIO)
                else if (text.isNullOrBlank()) listener.error(SpeechRecognizer.ERROR_NO_MATCH)
                else listener.results(Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text)) })
            }
        }
    }
    override fun onStopListening(listener: Callback) { if (active === listener) speech?.finishUtterance() }
    override fun onCancel(listener: Callback) { if (active === listener) { active = null; speech?.cancel() } }
    override fun onCheckRecognitionSupport(intent: Intent, callback: SupportCallback) {
        callback.onSupportResult(RecognitionSupport.Builder().setInstalledOnDeviceLanguages(listOf("ru-RU")).setSupportedOnDeviceLanguages(listOf("ru-RU")).build())
    }
    override fun onDestroy() { active = null; speech?.close(); speech = null; super.onDestroy() }
}
