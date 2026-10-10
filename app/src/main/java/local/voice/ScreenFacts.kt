package local.voice

/** Dynamic caption/value association. A percent without its caption is deliberately ambiguous. */
internal object ScreenFacts {
    data class Fact(val field: String, val caption: String, val value: String)
    private val captions = mapOf("humidity" to Regex("^(влажность|humidity)$", RegexOption.IGNORE_CASE),
        "cloud" to Regex("^(облачность|cloudiness|cloud cover)$", RegexOption.IGNORE_CASE),
        "wind" to Regex("^(ветер|скорость ветра|wind|wind speed)$", RegexOption.IGNORE_CASE),
        "temperature" to Regex("^(температура|temperature)$", RegexOption.IGNORE_CASE),
        "rain" to Regex("^(осадки|количество осадков|rainfall|precipitation|precipitation amount)$", RegexOption.IGNORE_CASE),
        "rainChance" to Regex("^(вероятность (?:осадков|дождя)|(?:rain|precipitation) (?:chance|probability))$", RegexOption.IGNORE_CASE))
    private fun sameRow(a: AppScreenReader.Word, b: AppScreenReader.Word) =
        kotlin.math.abs((a.top + a.bottom) - (b.top + b.bottom)) <= maxOf(a.bottom - a.top, b.bottom - b.top)
    fun read(words: List<AppScreenReader.Word>): List<Fact> {
        val reliable = words.filter { it.confidence >= 75 && it.right > it.left && it.bottom > it.top }
        val labels = reliable.flatMap { start ->
            val row = reliable.filter { it.left >= start.left && sameRow(start, it) }.sortedBy { it.left }
            (1..minOf(3, row.size)).mapNotNull { size ->
                val phrase = row.take(size)
                if (phrase.zipWithNext().any { (a, b) -> b.left - a.right > maxOf(a.bottom - a.top, b.bottom - b.top) * 2 }) null
                else {
                    val text = phrase.joinToString(" ") { it.text.trim() }
                    val field = captions.entries.singleOrNull { it.value.matches(text) }?.key
                    field?.let { it to start.copy(text = text, right = phrase.last().right) }
                }
            }
        }
        return labels.mapNotNull { (field, label) ->
            val row = reliable.filter { it.left >= label.right && sameRow(label, it) }
                .sortedBy { it.left }
            val numbers = row.filter { Regex("^[−+\\-]?\\d{1,3}(?:[.,]\\d{1,2})?(?:[%°]|°[CFС])?$").matches(it.text) }
            val value = numbers.singleOrNull() ?: return@mapNotNull null
            val suffix = row.filter { it.left >= value.right && it.left - value.right <= (value.bottom - value.top) * 2 }
                .firstOrNull()?.text.orEmpty()
            val combined = if (value.text.contains('%') || value.text.contains('°')) value.text else value.text + " " + suffix
            val valid = when (field) {
                "humidity", "cloud", "rainChance" -> Regex("^\\d{1,3}(?:[.,]\\d+)?\\s*%$").matches(combined) &&
                    value.text.removeSuffix("%").replace(',', '.').toDoubleOrNull()?.let { it in 0.0..100.0 } == true
                "temperature" -> Regex("^[−+\\-]?\\d{1,3}(?:[.,]\\d+)?\\s*°[CFС]?$", RegexOption.IGNORE_CASE).matches(combined)
                "wind" -> Regex("^\\d{1,3}(?:[.,]\\d+)?\\s*(?:м/с|m/s|км/ч|km/h|уз)$", RegexOption.IGNORE_CASE).matches(combined)
                else -> Regex("^\\d{1,3}(?:[.,]\\d+)?\\s*(?:мм|mm)$", RegexOption.IGNORE_CASE).matches(combined)
            }
            if (valid) Fact(field, label.text, combined.trim()) else null
        }.distinct()
    }
    fun relevant(facts: List<Fact>, request: WeatherRequest) = facts.filter {
        (it.field == "wind" && request.wind) || (it.field in setOf("rain", "rainChance") && request.rain) ||
            (it.field == "humidity" && request.query.contains("влажност", true)) ||
            (it.field == "cloud" && request.query.contains("облачност", true))
    }
}
