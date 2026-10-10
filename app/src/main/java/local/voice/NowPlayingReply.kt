package local.voice

/** Formats only player-provided data; no inference, URL reading or media controls. */
internal object NowPlayingReply {
    enum class State { PLAYING, PAUSED, LOADING, STOPPED }
    fun value(text: String?): String? = text?.replace(Regex("[\\p{Cc}\\p{Cf}]"), " ")
        ?.replace(Regex("\\s+"), " ")?.trim()?.take(160)?.takeIf { it.any(Char::isLetterOrDigit) }

    fun text(title: String?, artist: String?, state: State): String {
        val name = value(title)
        val performer = value(artist)
        if (name == null && performer == null) return "Проигрыватель не передал название и исполнителя текущего трека."
        val track = if (name != null) "«$name»${performer?.let { " — $it" }.orEmpty()}"
            else "$performer. Название трека проигрыватель не передал"
        return when (state) {
            State.PLAYING -> "Сейчас играет $track."
            State.PAUSED -> "На паузе $track."
            State.LOADING -> "Проигрыватель загружает $track."
            State.STOPPED -> "Последний трек: $track. Воспроизведение остановлено."
        }
    }
}
