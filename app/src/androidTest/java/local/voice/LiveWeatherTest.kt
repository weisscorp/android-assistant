package local.voice

import android.media.AudioManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Explicit, read-only integration with the real public provider; never uses the owner's location. */
@RunWith(AndroidJUnit4::class)
class LiveWeatherTest {
    @Test fun namedWeatherUsesRealHttpsApiAndReturnsThroughConversation() {
        assumeTrue("Run explicitly with live_weather=true", InstrumentationRegistry.getArguments().getString("live_weather") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val replies = LinkedBlockingQueue<String>()
        var conversation: VoiceConversation? = null
        var released = false
        val stats = LocalBrain.lastStats
        var geocoding = 0; var requests = 0
        val source = object : WeatherDataSource {
            override fun places(query: String, request: WeatherHttpRequest): List<WeatherPlace> { geocoding++; return OpenMeteo.places(query, request) }
            override fun forecast(point: WeatherLocation.Point, request: WeatherHttpRequest): PublicWeatherForecast {
                requests++; assertTrue(point.latitude in 41.0..42.0 && point.longitude in 41.0..42.0)
                return OpenMeteo.forecast(point, request)
            }
        }
        try {
            instrumentation.runOnMainSync { conversation = VoiceConversation(context, {}, {}, textOnly = true, weatherSource = source,
                releaseScreen = { released = true }, replyObserver = { replies.offer(it) }).also { it.start(); it.respond("Погода в Батуми") } }
            val answer = replies.poll(60, TimeUnit.SECONDS) ?: error("No public weather reply")
            assertTrue(answer, answer.contains("Батуми")); assertTrue(answer, answer.contains("градусов Цельсия"))
            assertTrue(answer, answer.contains("Open-Meteo")); assertTrue(answer, answer.contains("расчёт модели"))
            assertFalse(answer.contains("широт")); assertFalse(answer.contains("долгот")); assertFalse(released)
            assertEquals(1, geocoding); assertEquals(1, requests)
            assertEquals(stats, LocalBrain.lastStats); assertEquals(0L, conversation!!.speechFrames)
            assertTrue(context.getSystemService(AudioManager::class.java).activeRecordingConfigurations.isEmpty())
            File(context.cacheDir, "public-weather-live.json").writeText(JSONObject().put("provider", "Open-Meteo")
                .put("testCity", "Батуми").put("reply", answer).put("geocodingRequests", geocoding).put("forecastRequests", requests)
                .put("microphoneFrames", 0).put("localInference", false).put("screenReleased", false).toString(2))
            println("PUBLIC_WEATHER_LIVE_REPLY: $answer")
        } finally { instrumentation.runOnMainSync { conversation?.close() } }
    }
}
