package local.voice

import java.util.Locale

/** Speech aliases are labels, never a guessed package or a remote intent. */
object AppNames {
    private val aliases = mapOf(
        "винди" to "windyapp", "виндиапп" to "windyapp",
        "телеграм" to "telegram", "телеграмм" to "telegram",
        "ватсап" to "whatsapp", "вотсап" to "whatsapp", "вацап" to "whatsapp",
        "ютуб" to "youtube", "ютюб" to "youtube", "ютьюб" to "youtube",
        "спотифай" to "spotify", "спотифаи" to "spotify", "спотифэй" to "spotify", "спотифей" to "spotify",
        "вконтакте" to "vk", "вк" to "vk",
        "настройки" to "settings", "телефон" to "phone",
        "калькулятор" to "calculator", "камера" to "camera",
        "хром" to "chrome", "файрфокс" to "firefox", "сигнал" to "signal"
    )
    internal fun seededAliases(label: String): List<String> = aliases.filterValues { it == canonical(label) }.keys.toList()
    fun completeMention(name: String, request: String, labels: List<String>): String {
        val candidate = canonical(name)
        if (candidate.isBlank()) return name
        val matches = labels.distinct().filter { canonical(it).contains(candidate) && mentioned(it, request) }
        return matches.singleOrNull() ?: name
    }
    fun literalSuffix(proposed: String, request: String): String {
        DictationText.extract(request)?.let { return it.text }
        val body = proposed.trimEnd('.', '!', '?')
        val original = request.trim()
        val withoutPunctuation = original.trimEnd('.', '!', '?')
        require(body.isNotEmpty() && withoutPunctuation.endsWith(body, ignoreCase = true)) { "Продиктованный текст не найден в исходной просьбе." }
        return original.takeLast(body.length + original.length - withoutPunctuation.length)
    }
    fun mentioned(name: String, request: String): Boolean {
        val target = canonical(name)
        if (target.isBlank()) return false
        val words = request.split(Regex("[^\\p{L}\\p{N}]+" )).filter(String::isNotBlank)
        for (start in words.indices) for (end in start + 1..minOf(words.size, start + 8)) {
            if (canonical(words.subList(start, end).joinToString(" ")) == target) return true
        }
        return false
    }
    fun canonical(value: String): String {
        val name = value.lowercase(Locale.ROOT).replace('ё', 'е').replace(Regex("[^\\p{L}\\p{N}]"), "")
        return aliases[name] ?: name
    }
}
