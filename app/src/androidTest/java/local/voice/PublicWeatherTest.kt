package local.voice

import android.media.AudioManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PublicWeatherTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun fixture(): JSONObject {
        val today = LocalDate.now(ZoneId.of("Asia/Tbilisi"))
        val time = LocalDateTime.now(ZoneId.of("Asia/Tbilisi")).withSecond(0).withNano(0)
        return JSONObject("""{
            "timezone":"Asia/Tbilisi",
            "current_units":{"time":"iso8601","interval":"seconds","temperature_2m":"°C","relative_humidity_2m":"%","cloud_cover":"%","wind_speed_10m":"m/s","wind_gusts_10m":"m/s","wind_direction_10m":"°","precipitation":"mm","weather_code":"wmo code"},
            "current":{"time":"$time","interval":900,"temperature_2m":17.9,"relative_humidity_2m":74,"cloud_cover":100,"wind_speed_10m":2.02,"wind_gusts_10m":3.2,"wind_direction_10m":213,"precipitation":0,"weather_code":3},
            "hourly_units":{"time":"iso8601","precipitation_probability":"%"},"hourly":{"time":["${time.plusHours(1).withMinute(0)}"],"precipitation_probability":[35]},
            "daily_units":{"time":"iso8601","temperature_2m_max":"°C","temperature_2m_min":"°C","precipitation_sum":"mm","precipitation_probability_max":"%","wind_speed_10m_max":"m/s","wind_gusts_10m_max":"m/s","weather_code":"wmo code"},
            "daily":{"time":["${today.plusDays(1)}"],"temperature_2m_min":[16.2],"temperature_2m_max":[20.2],"precipitation_sum":[4.3],"precipitation_probability_max":[45],"wind_speed_10m_max":[3.75],"wind_gusts_10m_max":[6],"weather_code":[61]}
        }""")
    }
    @Test fun jsonNumbersNullsAndUnitsAreValidatedBeforeSpeech() {
        val data = fixture()
        val parsed = OpenMeteo.decode(data)
        assertEquals(17.9, parsed.current!!.temperature!!, 0.001)
        data.getJSONObject("current").put("relative_humidity_2m", JSONObject.NULL).put("precipitation", "0")
        data.getJSONObject("current_units").put("wind_speed_10m", "km/h")
        data.getJSONObject("daily").put("temperature_2m_max", org.json.JSONArray("[20.2, 50]"))
        val checked = OpenMeteo.decode(data)
        assertNull(checked.current!!.humidity); assertNull(checked.current.wind); assertNull(checked.current.precipitation)
        assertNull(checked.daily.single().high)
        data.getJSONObject("current_units").put("temperature_2m", "°F")
        assertTrue(OpenMeteo.decode(data).reply(WeatherRequest.parse("Погода")!!, "Батуми").contains("не вернул свежие"))
        assertEquals("Москва", OpenMeteo.placeQuery("Москве"))
        assertTrue(runCatching { OpenMeteo.decode(JSONObject("""{"timezone":"Bad/Zone"}""")) }.isFailure)
    }
    @Test fun malformedCoordinatesAndDuplicatePlacesAreRejected() {
        val data = JSONObject("""{"results":[{"id":1,"name":"Батуми","admin1":"Аджария","country":"Грузия","latitude":41.64077,"longitude":41.6306},
            {"id":1,"name":"Duplicate","latitude":41,"longitude":41},{"id":2,"name":"Invalid","latitude":100,"longitude":41}]}""")
        val places = OpenMeteo.decodePlaces(data)
        assertEquals(1, places.size); assertEquals("Батуми, Аджария, Грузия", places.single().name)
        assertTrue(OpenMeteo.decodePlaces(JSONObject()).isEmpty())
    }
    @Test fun weatherConversationUsesApiWithoutScreenReleaseMicrophoneOrModel() {
        val replies = LinkedBlockingQueue<String>()
        var conversation: VoiceConversation? = null
        var released = false
        val stats = LocalBrain.lastStats
        val forecast = OpenMeteo.decode(fixture())
        val source = object : WeatherDataSource {
            override fun places(query: String, request: WeatherHttpRequest): List<WeatherPlace> {
                assertEquals("батуми", query)
                return listOf(WeatherPlace(1, "Батуми, Грузия", 41.64077, 41.6306),
                    WeatherPlace(2, "Batumirah, Индонезия", -7.2, 109.1), WeatherPlace(3, "Батуми (аэропорт), Грузия", 41.61, 41.59, "AIRP"))
            }
            override fun forecast(point: WeatherLocation.Point, request: WeatherHttpRequest): PublicWeatherForecast {
                assertEquals(41.64077, point.latitude, 0.000001); return forecast
            }
        }
        try {
            instrumentation.runOnMainSync { conversation = VoiceConversation(context, {}, {}, textOnly = true,
                releaseScreen = { released = true }, replyObserver = { replies.offer(it) }, weatherSource = source).also { it.start(); it.respond("Какая погода в Батуми?") } }
            val answer = replies.poll(5, TimeUnit.SECONDS)!!
            assertTrue(answer, answer.contains("17,9 градусов Цельсия")); assertTrue(answer.contains("Open-Meteo"))
            assertFalse(answer.contains("41.64077")); assertFalse(answer.contains("Hermes")); assertFalse(released)
            assertEquals(stats, LocalBrain.lastStats)
            assertEquals(0L, conversation!!.speechFrames)
            assertTrue(context.getSystemService(AudioManager::class.java).activeRecordingConfigurations.isEmpty())
        } finally { instrumentation.runOnMainSync { conversation?.close() } }
    }
    @Test fun gpsIsUsedOnlyForApiAndDoesNotReachSpokenAnswer() {
        val replies = LinkedBlockingQueue<String>()
        var query: PublicWeatherQuery? = null
        val point = WeatherLocation.Point(41.64077, 41.6306)
        val source = object : WeatherDataSource {
            override fun places(query: String, request: WeatherHttpRequest): List<WeatherPlace> = error("GPS must not use Search")
            override fun forecast(value: WeatherLocation.Point, request: WeatherHttpRequest): PublicWeatherForecast { assertEquals(point, value); return OpenMeteo.decode(fixture()) }
        }
        try {
            instrumentation.runOnMainSync {
                query = PublicWeatherQuery(context, WeatherRequest.parse("Погода?")!!, { true }, { replies.offer(it) }, { replies.offer(it) }, source,
                    { _, callback -> callback(point, null) }).also { it.start() }
            }
            val answer = replies.poll(3, TimeUnit.SECONDS)!!
            assertTrue(answer.contains("По вашему местоположению")); assertFalse(answer.contains("41.64077"))
        } finally { instrumentation.runOnMainSync { query?.cancel() } }
    }
    @Test fun ambiguousCityRequiresChoiceAndCancellationDropsLateForecast() {
        val replies = LinkedBlockingQueue<String>()
        var conversation: VoiceConversation? = null
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val source = object : WeatherDataSource {
            override fun places(query: String, request: WeatherHttpRequest) = listOf(WeatherPlace(1, "Берлин, Германия", 52.5, 13.4), WeatherPlace(2, "Берлин, США", 44.4, -71.2))
            override fun forecast(point: WeatherLocation.Point, request: WeatherHttpRequest): PublicWeatherForecast {
                assertEquals(44.4, point.latitude, 0.001); entered.countDown()
                try { release.await(3, TimeUnit.SECONDS) } catch (_: InterruptedException) { }
                return OpenMeteo.decode(fixture())
            }
        }
        try {
            instrumentation.runOnMainSync { conversation = VoiceConversation(context, {}, {}, textOnly = true, replyObserver = { replies.offer(it) }, weatherSource = source).also { it.start(); it.respond("Погода в Берлине") } }
            assertTrue(replies.poll(3, TimeUnit.SECONDS)!!.contains("несколько мест"))
            assertEquals(1L, entered.count)
            instrumentation.runOnMainSync { conversation!!.respond("второй") }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { conversation!!.respond("заряд") }
            assertTrue(replies.poll(3, TimeUnit.SECONDS)!!.startsWith("Заряд батареи"))
            release.countDown()
            assertNull(replies.poll(500, TimeUnit.MILLISECONDS))
        } finally { release.countDown(); instrumentation.runOnMainSync { conversation?.close() } }
    }
    @Test fun unavailableApiCannotProduceInventedWeather() {
        val replies = LinkedBlockingQueue<String>()
        var conversation: VoiceConversation? = null
        val source = object : WeatherDataSource {
            override fun places(query: String, request: WeatherHttpRequest) = listOf(WeatherPlace(1, "Батуми", 41.6, 41.6))
            override fun forecast(point: WeatherLocation.Point, request: WeatherHttpRequest): PublicWeatherForecast = error("offline")
        }
        try {
            instrumentation.runOnMainSync { conversation = VoiceConversation(context, {}, {}, textOnly = true, replyObserver = { replies.offer(it) }, weatherSource = source).also { it.start(); it.respond("Погода в Батуми") } }
            val answer = replies.poll(3, TimeUnit.SECONDS)!!
            assertTrue(answer.contains("Данные не получены")); assertFalse(answer.contains("градусов"))
        } finally { instrumentation.runOnMainSync { conversation?.close() } }
    }
}
