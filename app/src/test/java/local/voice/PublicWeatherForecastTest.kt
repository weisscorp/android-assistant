package local.voice

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class PublicWeatherForecastTest {
    private val now = Instant.parse("2026-10-06T19:15:00Z") // 23:15 in Batumi
    private val time = LocalDateTime.of(2026, 10, 6, 23, 15)
    private val current = PublicWeatherForecast.Current(time, 900, 17.9, 17.1, 74.0, 100.0, 2.02, 3.2, 213.0, 0.0, 3)
    private val forecast = PublicWeatherForecast(ZoneId.of("Asia/Tbilisi"), current,
        listOf(PublicWeatherForecast.Day(LocalDate.of(2026, 10, 7), 16.2, 20.2, 3.75, 6.0, 4.3, 45.0, 61)),
        mapOf(time.withMinute(0) to 2.0, time.plusDays(1).withMinute(0) to 99.0, time.plusHours(1).withMinute(0) to 35.0))
    @Test fun currentUsesUnitsAndActualTimeWithoutReadingOldHourlyProbability() {
        val answer = forecast.reply(WeatherRequest.parse("Погода?")!!, "", now)
        for (value in listOf("По вашему местоположению", "17,9 градусов Цельсия", "2 метров в секунду", "Влажность 74", "Облачность 100", "за последние 15 минут: 0", "на час до 00:00 вероятность осадков 35", "23:15", "Open-Meteo", "расчёт модели")) assertTrue(answer, answer.contains(value))
        assertFalse(answer.contains("99 процентов")); assertFalse(answer.contains("2 процентов"))
        assertFalse(answer.contains("широт")); assertFalse(answer.contains("долгот"))
    }
    @Test fun dailyKeepsLocalDateRainSumAndProbabilitySeparate() {
        val answer = forecast.reply(WeatherRequest.parse("Погода завтра")!!, "Батуми", now)
        for (value in listOf("7 октября", "от 16,2 до 20,2", "Осадки за день 4,3 миллиметров", "вероятность осадков 45 процентов", "Максимальный ветер 3,8")) assertTrue(answer, answer.contains(value))
        val afterMidnight = forecast.reply(WeatherRequest.parse("Погода завтра")!!, "Батуми", Instant.parse("2026-10-06T20:01:00Z"))
        assertTrue(afterMidnight.contains("не вернул прогноз")) // Tomorrow is now Oct 8, not the UTC Oct 7.
    }
    @Test fun nullAndExpiredMeasurementsNeverBecomeZeroOrCurrent() {
        assertTrue(forecast.copy(current = current.copy(temperature = null)).reply(WeatherRequest.parse("Погода")!!, "Батуми", now).contains("не вернул свежие"))
        assertTrue(forecast.reply(WeatherRequest.parse("Погода")!!, "Батуми", now.plusSeconds(5500)).contains("не вернул свежие"))
        val answer = forecast.copy(current = current.copy(wind = null, precipitation = null), probabilities = emptyMap())
            .reply(WeatherRequest.parse("Ветер и дождь?")!!, "Батуми", now)
        assertTrue(answer.contains("Ветер недоступен")); assertTrue(answer.contains("Количество осадков недоступно")); assertTrue(answer.contains("Вероятность осадков недоступна"))
        assertFalse(answer.contains("0 метров")); assertFalse(answer.contains("0 миллиметров"))
    }
    @Test fun explicitAppAndCityAreParsedIndependentlyAndCoordinateLabelsAreNotSpoken() {
        val r = WeatherRequest.parse("Погода в Батуми завтра из windy.app")!!
        assertEquals("батуми", r.place); assertEquals(1, r.day); assertTrue(r.fromWindy)
        assertEquals("батуми", WeatherRequest.parse("Погода из windy.app в Батуми")!!.place)
        assertNull(WeatherRequest.parse("Погода из windy.app")!!.place)
        assertFalse(WeatherRequest.parse("Погода в Батуми")!!.fromWindy)
        assertEquals("выбранного места", WeatherSpeech.place("41.64077, 41.63060"))
        assertEquals("GPS", WeatherSpeech.place("GPS (41.64077, 41.63060)"))
        val answer = WindyForecast("41.64077, 41.63060", mapOf(LocalDate.of(2026, 10, 6) to "17°"))
            .reply(WeatherRequest.parse("Погода из windy.app")!!, LocalDate.of(2026, 10, 6))
        assertFalse(answer.contains("41.64077")); assertTrue(answer.contains("выбранного места"))
    }
}
