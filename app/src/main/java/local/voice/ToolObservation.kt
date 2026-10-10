package local.voice

/** Result of a local read. Fields are data, never instructions or authorization for actions. */
data class ToolObservation(val tool: String, val operation: String, val status: String,
    val fields: Map<String, String>, val fallback: String) {
    fun answer(raw: String, failed: Boolean): String {
        if (failed || status != "ok" || !SpokenReply.hasContent(raw) || !Regex("[А-Яа-яЁё]").containsMatchIn(raw)) return fallback
        // Small models may paraphrase a title/artist into unrelated words. Keep proper names literal.
        val names = listOfNotNull(fields["title"], fields["artist"]).filter { it.isNotBlank() }
        if (names.any { !raw.contains(it, ignoreCase = true) }) return fallback
        val prose = names.fold(raw) { text, name -> text.replace(name, "", ignoreCase = true) }
        val state = when (fields["state"]) {
            "paused" -> "пауз|приостанов"
            "loading" -> "загруж|буфер"
            "stopped" -> "останов|последн"
            "playing" -> "играет|воспроизвод"
            else -> null
        }
        if (state != null && !Regex(state, RegexOption.IGNORE_CASE).containsMatchIn(prose)) return fallback
        return SpokenReply.answer(raw)
    }
}
