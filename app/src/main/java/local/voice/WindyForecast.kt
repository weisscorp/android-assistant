package local.voice

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Plain immutable app observations; no model output is accepted as a measurement. */
internal data class ScreenTextNode(val text: String = "", val description: String = "", val id: String = "",
    val clickable: Boolean = false, val children: List<ScreenTextNode> = emptyList(), val bounds: String = "") {
    fun all(): List<ScreenTextNode> = listOf(this) + children.flatMap { it.all() }
}

internal data class WindyForecast(val place: String, val days: Map<LocalDate, String>, val cardWind: String? = null) {
    fun reply(request: WeatherRequest, today: LocalDate = LocalDate.now()): String {
        val spokenPlace = WeatherSpeech.place(place)
        if (request.place != null && !matchesPlace(request.place, place))
            return "В windy.app выбран $spokenPlace. Прогноз для ${request.place} не получен. Выберите нужное место в windy.app и повторите вопрос."
        if (request.day == null) return "Пока могу прочитать из windy.app дневной прогноз на сегодня, завтра или послезавтра. Почасовая таблица не предоставляет текст."
        val date = today.plusDays(request.day.toLong())
        val temperature = days[date] ?: return "В windy.app для $spokenPlace нет доступной карточки на ${date.format(dateFormat)}."
        val facts = mutableListOf("В windy.app для $spokenPlace дневной прогноз на ${date.format(dateFormat)}: ${degrees(temperature)}.")
        if (request.wind && request.day == 0 && cardWind != null)
            facts += "На карточке места указан ветер ${speed(cardWind)}. Время этого значения приложение не сообщает."
        else if (request.wind) facts += "Ветер на этот день не удалось прочитать из почасовой таблицы."
        if (request.rain) facts += "Значок осадков не доступен как текст, поэтому наличие дождя определить не удалось."
        if (!request.wind && !request.rain) facts += "Это значение на день, не текущий замер. Осадки с этого экрана не читаются."
        return facts.joinToString(" ")
    }
    companion object {
        const val PACKAGE = "co.windyapp.android"
        private val dateFormat = DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ru"))
        private val dateLabel = Regex("^(пн|вт|ср|чт|пт|сб|вс),?\\s*(\\d{1,2})$", RegexOption.IGNORE_CASE)
        private val temperature = Regex("^[−-]?\\d{1,2}(?:[.,]\\d)?°(?:[CFС]|\\s*[CFС])?$", RegexOption.IGNORE_CASE)
        private val wind = Regex("^\\d{1,3}(?:[.,]\\d)?\\s*(?:м/с|m/s|км/ч|km/h|уз|kts|knots)$", RegexOption.IGNORE_CASE)
        private val weekdays = listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")
        internal fun card(root: ScreenTextNode): ScreenTextNode? {
            // Each number is associated within its actual clickable forecast card.
            // Flat text concatenation loses dates and also mixes different favorite places.
            val cards = root.all().filter { it.clickable && it.all().count { n -> dateLabel.matches(n.text.trim()) } >= 2 }
            return cards.filter { parent -> cards.none { child -> child !== parent && parent.children.any { child in it.all() } } }.singleOrNull()
        }
        fun read(root: ScreenTextNode, today: LocalDate): WindyForecast? {
            val card = card(root) ?: return null
            val labels = card.all().map { it.text.trim() }.filter { it.isNotBlank() }
            val firstDate = labels.indexOfFirst(dateLabel::matches)
            val place = labels.take(firstDate).distinct().singleOrNull()?.takeIf { it.length in 1..160 } ?: return null
            val days = linkedMapOf<LocalDate, String>()
            for (i in labels.indices) {
                val match = dateLabel.matchEntire(labels[i]) ?: continue
                val date = (0L..9L).map(today::plusDays).singleOrNull {
                    it.dayOfMonth == match.groupValues[2].toInt() && weekdays[it.dayOfWeek.value - 1] == match.groupValues[1].lowercase(Locale.ROOT)
                } ?: continue
                val next = (i + 1 until labels.size).firstOrNull { dateLabel.matches(labels[it]) } ?: labels.size
                val value = labels.subList(i + 1, next).filter(temperature::matches).singleOrNull() ?: continue
                if (days.put(date, value) != null) return null
            }
            if (days.isEmpty()) return null
            val favorites = root.all().filter { n -> n.clickable && n.all().any { it.text.trim() == place } &&
                n.all().none { dateLabel.matches(it.text.trim()) } && n.all().count { wind.matches(it.text.trim()) } == 1 }
            val values = favorites.flatMap { it.all().map { n -> n.text.trim() }.filter(wind::matches) }.distinct()
            return WindyForecast(place, days, values.singleOrNull())
        }
        private fun matchesPlace(query: String, place: String): Boolean {
            fun words(s: String) = s.lowercase(Locale.ROOT).replace("батуми", "batumi").split(Regex("[^\\p{L}\\p{N}]+" )).filter(String::isNotBlank)
            val wanted = words(query)
            return wanted.isNotEmpty() && wanted.all { it in words(place) }
        }
        private fun degrees(text: String) = text.replace('−', '-').replace("°C", " градусов Цельсия", true)
            .replace("°С", " градусов Цельсия", true).replace("°F", " градусов Фаренгейта", true).replace("°", " градусов")
        private fun speed(text: String) = text.replace("м/с", " метров в секунду").replace("m/s", " метров в секунду")
            .replace("км/ч", " километров в час").replace("km/h", " километров в час")
    }
}
