package local.voice

/** Personal/current positions require a user-linked source, never a guess from chat memory. */
internal object LocationQuestion {
    private val possessive = wordRegex("^(?:мой|моя|мое|мои|наш|наша|наше|наши)\\b")
    private val relation = Regex("^(?:(?:младший|младшая|старший|старшая)\\s+)?(?:ребенок|дети|сын|дочь|жена|муж|мама|папа|друг|подруга|трансфер)(?:\\s+.*)?$")
    private val activity = wordRegex("^(?:купить|найти|искать|посмотреть|скачать|поесть|погулять|учиться|работать|можно|лучше|взять|есть)\\b")

    fun subject(text: String, knownPeople: List<String> = emptyList()): String? {
        val subject = PresenceFacts.query(text) ?: return null
        val key = PresenceFacts.key(subject)
        if (activity.containsMatchIn(key)) return null
        val current = wordRegex("\\bсейчас\\b").containsMatchIn(PresenceFacts.key(text))
        val identity = knownPeople.any { PresenceFacts.key(it) == key }
        return subject.takeIf { current || possessive.containsMatchIn(key) || relation.matches(key) || identity }
    }

    const val MISSING = "Не знаю текущее положение: источник геолокации ещё не подключён. Уточните имя или название источника. Геолокацией можно поделиться с помощником через «Поделиться»."

    fun ambiguous(names: List<String>): String = "Какой источник положения проверить: " +
        names.take(3).joinToString(", ") { PresenceFacts.forSpeech(it).take(100) } +
        "? Назовите его" + if (names.size > 3) " или другой сохранённый источник." else "."
}
