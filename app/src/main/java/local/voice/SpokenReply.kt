package local.voice

/** Shared bound for the actual TTS payload and its cancellable playback deadline. */
internal object SpokenReply {
    const val UNAVAILABLE = "Не удалось получить ответ. Попробуйте уточнить вопрос."
    private const val LIMIT = 3000
    private const val TRUNCATED = ". Ответ слишком длинный. Прочитал только начало."

    fun hasContent(raw: String): Boolean = raw.any { it.isLetterOrDigit() }
    fun answer(raw: String): String = if (hasContent(raw)) raw.trim() else UNAVAILABLE

    fun text(raw: String): String {
        val answer = answer(raw)
        if (answer.length <= LIMIT) return answer
        var end = LIMIT - TRUNCATED.length
        if (answer[end - 1].isHighSurrogate() && answer[end].isLowSurrogate()) end--
        return answer.substring(0, end) + TRUNCATED
    }

    fun timeoutMillis(text: String): Long = (30_000L + text.length * 100L).coerceIn(90_000L, 300_000L)
}
