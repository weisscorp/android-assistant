package local.voice

/** Windy may name a selected GPS spot using coordinates. Those are not useful weather speech. */
internal object WeatherSpeech {
    private val coordinates = Regex("[+-]?\\d{1,3}[.,]\\d{3,}\\s*[,;]\\s*[+-]?\\d{1,3}[.,]\\d{3,}")
    fun place(text: String): String = text.replace(coordinates, "").trim(' ', ',', ';', '(', ')').ifBlank { "выбранного места" }
}
