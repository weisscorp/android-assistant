package local.voice

import android.content.Context

internal enum class SpeechEngine(val id: String, val label: String, val description: String) {
    VOSK("vosk", "Vosk", "компактная русская модель"),
    TONE("t-one", "T-one", "экспериментальная русская модель"),
    GIGAAM("gigaam-v3", "GigaAM v3", "русская модель · текст после паузы")
}

internal object SpeechEngines {
    fun selected(context: Context): SpeechEngine {
        val prefs = context.getSharedPreferences("settings", 0)
        // Keep existing Vosk/T-one installations and their saved preference compatible.
        return if (prefs.getString("stt_engine", null) == SpeechEngine.GIGAAM.id) SpeechEngine.GIGAAM
            else if (prefs.getBoolean("tone_asr", false)) SpeechEngine.TONE else SpeechEngine.VOSK
    }
    fun select(context: Context, engine: SpeechEngine) {
        val edit = context.getSharedPreferences("settings", 0).edit()
            .putBoolean("tone_asr", engine == SpeechEngine.TONE)
        if (engine == SpeechEngine.GIGAAM) edit.putString("stt_engine", engine.id) else edit.remove("stt_engine")
        check(edit.commit()) { "Не удалось сохранить модель распознавания." }
    }
}
