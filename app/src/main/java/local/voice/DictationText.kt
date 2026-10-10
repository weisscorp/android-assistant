package local.voice

/** Literal data stays outside capability lookup and never comes from a model-generated example. */
internal object DictationText {
    data class Payload(val text: String, val control: String)
    private val verb = Regex("(?:^|(?<=[^\\p{L}]))(?:напиши|впиши|введи|набери|напечатай|запиши)(?=$|[^\\p{L}])", RegexOption.IGNORE_CASE)
    private val field = Regex("^\\s*(?:в|на)\\s+(?:текстовое\\s+поле|поле(?:\\s+ввода)?|сообщение)(?=$|[^\\p{L}])\\s*", RegexOption.IGNORE_CASE)
    private val marker = Regex("^\\s*(?:текст|строку|сообщение)(?:\\s*[:—-]\\s*|\\s+)", RegexOption.IGNORE_CASE)
    private val colonMarker = Regex("^\\s*(?:текст|строку|сообщение)\\s*[:—-]\\s*", RegexOption.IGNORE_CASE)
    private fun closing(character: Char): Char? = when(character) { '"' -> '"'; '«' -> '»'; '“' -> '”'; else -> null }
    private fun quotedAt(text: String, position: Int): Boolean {
        var end: Char? = null
        for (character in text.take(position)) {
            if (end == character) end = null else if (end == null) end = closing(character)
        }
        return end != null
    }
    fun extract(request: String): Payload? {
        val match = verb.findAll(request).firstOrNull { !quotedAt(request, it.range.first) } ?: return null
        val preceding = request.substring(0, match.range.first).trimEnd()
        if (Regex("(?:^|\\s)не$", RegexOption.IGNORE_CASE).containsMatchIn(preceding)) return null
        var at = match.range.last + 1
        val afterVerb = request.substring(at)
        val target = field.find(afterVerb)
        if (target != null) at += target.value.length
        // “Впиши текст …” identifies the field payload; “Напиши Текст принят” can
        // itself be a literal sentence. Quotes or a colon disambiguate either form.
        val fieldVerb = match.value.equals("впиши", true) || match.value.equals("введи", true)
        val prefix = (if (target != null || fieldVerb) marker else colonMarker).find(request.substring(at))
        if (prefix != null) at += prefix.value.length
        while (at < request.length && request[at].isWhitespace()) at++
        if (at == request.length) return null
        val endQuote = closing(request[at])
        if (endQuote != null) {
            val end = request.indexOf(endQuote, at + 1)
            if (end < 0) return null
            val body = request.substring(at + 1, end)
            if (body.isBlank()) return null
            return Payload(body, request.substring(0, at) + " " + request.substring(end + 1))
        }
        return Payload(request.substring(at).trimEnd(), request.substring(0, at))
    }
    fun control(request: String) = extract(request)?.control ?: request
}
