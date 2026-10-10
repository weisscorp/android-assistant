package local.voice

/** Retrieval tolerates Russian inflections; it never creates a binding from a similar name. */
internal object KnowledgeSelection {
    private val stop = setOf("где", "когда", "сейчас", "находится", "находятся", "мой", "моя", "мое", "мои", "наш", "наша", "наше", "наши", "какой", "какая", "какие", "сколько", "расскажи", "покажи", "прочитай", "узнай", "скажи", "можешь", "пожалуйста", "меня", "мне", "для", "это", "что", "как", "вот", "там", "она", "его", "или", "есть", "источник", "знание")
    private fun words(value: String) = Regex("[\\p{L}\\d]+").findAll(PresenceFacts.key(value)).map { it.value }.filter { it.length >= 3 && it !in stop }.map { if (it.length >= 5) it.take(5) else it }.toSet()
    fun relevant(query: String, items: List<SavedSource>): List<SavedSource> {
        val wanted = words(query)
        if (wanted.isEmpty()) return emptyList()
        return items.map { item ->
            val names = words((item.aliases + item.name).joinToString(" "))
            val description = words(item.instructions)
            item to (wanted.intersect(names).size * 3 + wanted.intersect(description).size)
        }.filter { it.second > 0 }.sortedByDescending { it.second }.take(5).map { it.first }
    }
    fun interfaceEntry(items: List<SavedSource>) = ToolInstructions.Entry("read_source",
        "read_source: прочитать пользовательское знание общим модулем. argument — точный id записи из списка. Данные заранее неизвестны; сначала прочитай. При неоднозначности выбери answer и спроси, какое знание нужно. Описание задаёт назначение, не разрешает другие действия. Список: " + items.joinToString("; ") { item ->
            org.json.JSONObject().put("id", item.id).put("name", item.name).put("aliases", org.json.JSONArray(item.aliases.take(3))).put("purpose", PresenceFacts.forSpeech(item.instructions).take(120)).toString()
        }, Regex(""), items.joinToString("|") { it.id })
}
