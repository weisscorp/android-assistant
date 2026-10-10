package local.voice

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Provider measurements retain units, valid time and aggregation interval. No LLM supplies facts. */
internal data class PublicWeatherForecast(
    val zone: ZoneId,
    val current: Current?,
    val daily: List<Day>,
    val probabilities: Map<LocalDateTime, Double>
) {
    data class Current(val time: LocalDateTime, val interval: Int, val temperature: Double?, val feels: Double?,
        val humidity: Double?, val cloud: Double?, val wind: Double?, val gust: Double?, val direction: Double?,
        val precipitation: Double?, val code: Int?)
    data class Day(val date: LocalDate, val low: Double?, val high: Double?, val wind: Double?, val gust: Double?,
        val precipitation: Double?, val probability: Double?, val code: Int?)
    fun reply(request: WeatherRequest, place: String, now: Instant = Instant.now()): String {
        val today = now.atZone(zone).toLocalDate()
        val day = request.day ?: return "Пока поддерживаю текущую погоду, завтра и послезавтра. Назовите один из этих сроков."
        val location = if (place.isBlank()) "По вашему местоположению" else place
        val dateFormat = DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ru"))
        if (day != 0 || Regex("сегодня|будет|ожидается", RegexOption.IGNORE_CASE).containsMatchIn(request.query)) {
            val value = daily.singleOrNull { it.date == today.plusDays(day.toLong()) }
                ?: return "Open-Meteo не вернул прогноз на нужную дату. Попробуйте позже."
            val facts = mutableListOf("$location, ${value.date.format(dateFormat)}. ${condition(value.code)}")
            if (value.low != null && value.high != null) facts += "Температура от ${number(value.low)} до ${number(value.high)} градусов Цельсия."
            else facts += "Температура недоступна."
            value.wind?.let { facts += "Максимальный ветер ${number(it)} метров в секунду${value.gust?.let { gust -> ", порывы до ${number(gust)}" }.orEmpty()}." }
            value.precipitation?.let { facts += "Осадки за день ${number(it)} миллиметров." }
            value.probability?.let { facts += "Максимальная вероятность осадков ${number(it)} процентов." }
            if (Regex("влажност|облачност", RegexOption.IGNORE_CASE).containsMatchIn(request.query))
                facts += "Влажность и облачность в этом дневном ответе не доступны."
            return facts.joinToString(" ") + " Прогноз Open-Meteo."
        }
        val value = current
        if (value == null || value.temperature == null || kotlin.math.abs(java.time.Duration.between(value.time.atZone(zone).toInstant(), now).seconds) > 5400)
            return "Open-Meteo не вернул свежие текущие условия. Попробуйте позже."
        val facts = mutableListOf("$location. ${condition(value.code)} Температура ${number(value.temperature)} градусов Цельсия.")
        value.feels?.let { facts += "Ощущается как ${number(it)}." }
        value.wind?.let { facts += "Ветер ${direction(value.direction)}${number(it)} метров в секунду${value.gust?.let { gust -> ", порывы до ${number(gust)}" }.orEmpty()}." }
        if (request.wind && value.wind == null) facts += "Ветер недоступен."
        value.humidity?.let { facts += "Влажность ${number(it)} процентов." }
        value.cloud?.let { facts += "Облачность ${number(it)} процентов." }
        value.precipitation?.let { facts += "Осадки за последние ${value.interval / 60} минут: ${number(it)} миллиметров." }
        if (request.rain && value.precipitation == null) facts += "Количество осадков недоступно."
        // Hourly precipitation probability describes the hour ending at its timestamp.
        val probability = probabilities.entries.filter { it.key > value.time && it.key <= value.time.plusHours(1) }.minByOrNull { it.key }
        probability?.let { facts += "В прогнозе на час до ${it.key.format(DateTimeFormatter.ofPattern("HH:mm"))} вероятность осадков ${number(it.value)} процентов." }
        if (request.rain && probability == null) facts += "Вероятность осадков недоступна."
        facts += "Open-Meteo, расчёт модели на ${value.time.format(DateTimeFormatter.ofPattern("HH:mm"))} по местному времени."
        return facts.joinToString(" ")
    }
    companion object {
        private fun number(value: Double) = String.format(Locale.forLanguageTag("ru"), "%.1f", value).removeSuffix(",0")
        private fun direction(value: Double?): String {
            if (value == null) return ""
            val words = listOf("северный", "северо-восточный", "восточный", "юго-восточный", "южный", "юго-западный", "западный", "северо-западный")
            return words[((value + 22.5) / 45).toInt() % 8] + " "
        }
        private fun condition(code: Int?): String = when (code) {
            0 -> "Ясно."; 1 -> "Преимущественно ясно."; 2 -> "Переменная облачность."; 3 -> "Пасмурно."
            45, 48 -> "Туман."; 51, 53, 55 -> "Морось."; 56, 57 -> "Переохлаждённая морось."
            61, 63, 65 -> "Дождь."; 66, 67 -> "Переохлаждённый дождь."; 71, 73, 75, 77 -> "Снег."
            80, 81, 82 -> "Ливень."; 85, 86 -> "Снежные заряды."; 95 -> "Гроза."; 96, 99 -> "Гроза с градом."
            else -> "Описание погоды недоступно."
        }
    }
}
