package local.voice

/** Telegram's node classes can be ordinary Android Views; use its exposed text contract. */
internal object TelegramUiText {
    enum class Status { SENT, PENDING, ERROR, RECEIVED }
    data class Message(val body: String, val status: Status)
    fun person(label: String, query: String): Boolean {
        val first = label.substringBefore('\n').substringBefore(',').substringBefore(". ").trim()
        val matches = first.equals(query.trim(), true) ||
            (query.startsWith("@") && Regex("@[A-Za-z0-9_]{5,32}").findAll(label).any { it.value.equals(query, true) })
        return matches && !wordRegex("(?:\\b(?:бот|bot|группа|group|канал|channel|подписчиков|subscribers|участников|members)\\b)", RegexOption.IGNORE_CASE).containsMatchIn(label)
    }
    fun sourceChat(label: String, query: String): Boolean {
        val first=label.substringBefore('\n').substringBefore(',').substringBefore(". ").trim()
        return first.equals(query.trim(),true) || query.startsWith("@") && Regex("@[A-Za-z0-9_]{5,32}").findAll(label).any { it.value.equals(query,true) }
    }
    fun message(label: String): Message? {
        val lines = label.lines()
        if (lines.size < 2) return null
        // The generated footer must be last; message text containing these words cannot invent delivery.
        val footer = lines.last().trim()
        val status = when {
            Regex("^(?:Sending error|Ошибка отправки)(?:\\s.*)?$", RegexOption.IGNORE_CASE).matches(footer) -> Status.ERROR
            Regex("^(?:Sending|Отправляется)(?:\\s.*)?$", RegexOption.IGNORE_CASE).matches(footer) -> Status.PENDING
            Regex("^(?:Sent|Отправлено)\\s+.+$", RegexOption.IGNORE_CASE).matches(footer) -> Status.SENT
            Regex("^(?:Received|Получено)\\s+.+$", RegexOption.IGNORE_CASE).matches(footer) -> Status.RECEIVED
            else -> return null
        }
        val body = lines.dropLast(1).joinToString("\n").trim().takeIf { it.isNotBlank() } ?: return null
        return Message(body, status)
    }
    fun messageBody(label: String): String? = message(label)?.body
    fun outgoingCounts(labels: List<String>, body: String): Map<Status, Int> = labels.mapNotNull(::message)
        .filter { it.body == body && it.status != Status.RECEIVED }.groupingBy { it.status }.eachCount()
    /** Movement/status changes of an old message are not evidence of a new send. */
    fun newOutgoingStatus(before: Map<Status, Int>, after: Map<Status, Int>): Status? {
        if (after.values.sum() <= before.values.sum()) return null
        return after.keys.filter { (after[it] ?: 0) > (before[it] ?: 0) }.singleOrNull()
    }
}
