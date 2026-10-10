package local.voice

/** A single explicit media operation restricts the model's enum, without executing anything. */
internal object MediaIntent {
    private val operations = listOf(
        "quieter" to Regex("убав|уменьш|пониз|потише|тише", RegexOption.IGNORE_CASE),
        "louder" to Regex("прибав|увелич|повыс|погромче|громче", RegexOption.IGNORE_CASE),
        "pause" to Regex("пауз|приостанов|останови|выключи", RegexOption.IGNORE_CASE),
        "play" to Regex("продолж|возобнов|включи", RegexOption.IGNORE_CASE),
        "next" to Regex("следующ", RegexOption.IGNORE_CASE),
        "previous" to Regex("предыдущ", RegexOption.IGNORE_CASE))
    fun operation(request: String): String? {
        val control = DictationText.control(request)
        if (Regex("(?:^|[^\\p{L}])(?:не|нельзя|никак)(?=$|[^\\p{L}])", RegexOption.IGNORE_CASE).containsMatchIn(control)) return null
        if (NowPlayingRequest.matches(control)) return "info"
        val matches = operations.filter { it.second.containsMatchIn(control) }.map { it.first }
        if (matches.isNotEmpty()) return matches.singleOrNull()
        // Discovery of a read capability, not execution: the LLM must still emit the call.
        val topic = Regex("песн|трек|композиц|музык|исполнител|певец|певица", RegexOption.IGNORE_CASE)
        val current = Regex("текущ|проигрывател|в плеере|слушаю", RegexOption.IGNORE_CASE)
        return "info".takeIf { topic.containsMatchIn(control) && current.containsMatchIn(control) }
    }
}
