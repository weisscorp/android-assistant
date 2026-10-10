package local.voice

import java.util.Locale

/** Live weather questions must never be answered from the language model's training data. */
data class WeatherRequest(val query: String, val day: Int?, val place: String?, val wind: Boolean, val rain: Boolean) {
    val fromWindy: Boolean get() = Regex("windy(?:\\.app)?|винди", RegexOption.IGNORE_CASE).containsMatchIn(query)
    companion object {
        private val weather = Regex("(?:^|[^\\p{L}])(?:погод\\p{L}*|прогноз\\p{L}*|ветер|ветра|ветру|ветром|ветрен\\p{L}*|дожд\\p{L}*|осадк\\p{L}*|влажност\\p{L}*|облачност\\p{L}*|температур\\p{L}*|windy(?:\\.app)?|винди)(?=$|[^\\p{L}])")
        fun parse(text: String): WeatherRequest? {
            val value = text.lowercase(Locale.ROOT).replace('ё', 'е').trim()
            if (!weather.containsMatchIn(value) || wordRegex("^(?:что такое|почему|зачем|как устроен|как работает|объясни)\\b").containsMatchIn(value)) return null
            // Temperature without weather context may mean the phone, a room or a person.
            if (Regex("температур").containsMatchIn(value) && !Regex("погод|на улице|завтра|сегодня|windy|винди").containsMatchIn(value)) return null
            val unsupported = Regex("(?:в|на)\\s+\\d{1,2}(?::\\d{2}|\\s+час)|вечером|утром|ночью|вечер|утро|недел|месяц|через|вчера|понедельник|вторник|среду|четверг|пятницу|субботу|воскресенье|\\d+\\s+(?:январ|феврал|март|апрел|ма[йя]|июн|июл|август|сентябр|октябр|ноябр|декабр)").containsMatchIn(value)
            val day = when { unsupported -> null; value.contains("послезавтра") -> 2; value.contains("завтра") -> 1; else -> 0 }
            val withoutSource = value.replace(Regex("(?:из|через|по|в)\\s+(?:приложени\\p{L}*\\s+)?(?:windy(?:\\.app)?|винди)"), "").trim()
            val explicitPlace = wordRegex("(?:^|\\s)(?:в|во|для)\\s+(.+?)(?=\\s+(?:сегодня|завтра|послезавтра|сейчас|по|из|через|на|будет|ожидается)\\b|[?!,.]?$)")
                .find(withoutSource)?.groupValues?.get(1)?.trim()?.takeIf {
                    !Regex("^(?:windy|винди|приложени|шлем|телефон)|^\\d|час|настоящ|моем|моем|текущ").containsMatchIn(it)
                }
            val bare = Regex("^(?:погода|прогноз)\\s+(?:(?:сегодня|завтра|послезавтра|сейчас)\\s+)?(.+)$").find(withoutSource)?.groupValues?.get(1)
                ?.replace(Regex("\\s+(?:сегодня|завтра|послезавтра|сейчас)[?!,.]?$"), "")?.trim()?.trimEnd('?', '!', '.')
                ?.takeIf { !Regex("^(?:из|по|на|через|в |во |сегодня|завтра|послезавтра|сейчас|температур|ветер|осад|windy|винди)").containsMatchIn(it) }
            val place = explicitPlace ?: bare
            return WeatherRequest(text.take(600), day, place, Regex("ветер|ветра|ветру|ветром|ветрен").containsMatchIn(value), Regex("дожд|осадк").containsMatchIn(value))
        }
    }
}
