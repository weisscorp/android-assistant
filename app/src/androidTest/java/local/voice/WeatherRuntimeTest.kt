package local.voice

import android.app.UiAutomation
import android.content.Intent
import android.media.AudioManager
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.time.LocalDate
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch

@RunWith(AndroidJUnit4::class)
class WeatherRuntimeTest {
    @Test fun nativeWeatherPlannerReturnsGroundedReadCommand() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val request = "Какая погода в Батуми завтра?"
        val answered = CountDownLatch(1)
        var result: LocalBrain.Proposal? = null
        instrumentation.runOnMainSync {
            LocalBrain.plan(instrumentation.targetContext, emptyList(), request) { result = it; answered.countDown() }
        }
        assertTrue("Weather planner timed out", answered.await(35, TimeUnit.SECONDS))
        assertEquals(result.toString(), VoiceCommand.Weather(WeatherRequest.parse(request)!!), result?.command)
        LocalBrain.dropContext()
    }
    @Test fun cacheReportsReadTimeRejectsExpiredFutureAndOtherPlace() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("weather", 0)
        val before = prefs.getString("windy", null)
        val request = WeatherRequest.parse("Какая погода?")!!
        try {
            fun save(at: Long) = prefs.edit().putString("windy", JSONObject().put("observedAt", at).put("place", "Test Place")
                .put("days", JSONObject().put(LocalDate.now().toString(), "17°")).toString()).commit()
            assertTrue(save(System.currentTimeMillis()))
            val reply = WindyWeather.cached(context, request)!!
            assertTrue(reply, reply.startsWith("Сохранённые данные windy.app, прочитанные"))
            assertTrue(reply.contains("17 градусов"))
            assertTrue(reply.contains("не текущий замер"))
            assertFalse(WindyWeather.cached(context, WeatherRequest.parse("Погода в Москве")!!)!!.contains("17 градусов"))
            assertTrue(save(System.currentTimeMillis() - 7 * 60 * 60 * 1000))
            assertNull(WindyWeather.cached(context, request))
            assertTrue(save(System.currentTimeMillis() + 60_000))
            assertNull(WindyWeather.cached(context, request))
        } finally { prefs.edit().apply { if (before == null) remove("windy") else putString("windy", before) }.commit() }
    }
    private fun realPhoneSetup() {
        assumeTrue("Run explicitly on the Redmi with installed Windy", InstrumentationRegistry.getArguments().getString("real_windy") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertNotNull(context.packageManager.getLaunchIntentForPackage(WindyForecast.PACKAGE))
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        // MIUI marks a bound service crashed when instrumentation replaces the
        // process. Rebind only the service already enabled by the user in Settings.
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val component = "local.voice/local.voice.PhoneAccessibility"
        assertTrue("Enable assistant screen access in Settings first", component in enabled.split(':'))
        automation.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_SECURE_SETTINGS)
        try {
            assertTrue(Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, enabled.split(':').filter { it != component }.joinToString(":")))
            Thread.sleep(600) // Let the system observe removal before restoring the same component.
            assertTrue(Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, enabled))
            assertTrue(Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1))
        } finally { automation.dropShellPermissionIdentity() }
        val until = System.currentTimeMillis() + 5000
        while (PhoneAccessibility.connected == null && System.currentTimeMillis() < until) Thread.sleep(100)
        assertNotNull("Assistant screen access is disabled", PhoneAccessibility.connected)
    }
    private fun weatherReply(query: String): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Thread.sleep(500)
        val replies = LinkedBlockingQueue<String>()
        var conversation: VoiceConversation? = null
        val stats = LocalBrain.lastStats
        instrumentation.runOnMainSync {
            conversation = VoiceConversation(context, {}, {}, textOnly = true, replyObserver = { replies.offer(it) }).also {
                it.start(); it.respond(query)
            }
        }
        try {
            val reply = replies.poll(65, TimeUnit.SECONDS) ?: error("No weather reply")
            assertEquals("Weather unexpectedly invoked LLM", stats, LocalBrain.lastStats)
            assertTrue(context.getSystemService(AudioManager::class.java).activeRecordingConfigurations.isEmpty())
            assertEquals(0L, conversation!!.speechFrames)
            return reply
        } finally { instrumentation.runOnMainSync { conversation?.close() } }
    }
    @Test fun installedWindyQuestionReadsAppWithoutMicrophoneOrModelInference() {
        realPhoneSetup()
        val reply = weatherReply("Какая погода в Батуми завтра из windy.app?")
        assertTrue(reply, reply.startsWith("В windy.app для "))
        val saved = JSONObject(InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("weather", 0).getString("windy", null)!!)
        assertTrue("Different city", WindyNavigation.placeMatches("батуми", saved.getString("place")))
        assertTrue(reply, reply.contains("дневной прогноз"))
        assertTrue(reply, reply.contains("не текущий замер"))
        println("WINDY_REAL_REPLY: $reply")
    }
    @Test fun homeCardIsReadWithoutCoordinateSearch() {
        realPhoneSetup()
        val reply = weatherReply("Погода из windy.app")
        assertTrue(reply, reply.startsWith("На главной карточке windy.app. В windy.app для "))
        val saved = JSONObject(InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("weather", 0).getString("windy", null)!!)
        assertEquals("windy:home", saved.getString("target"))
        assertFalse(reply.contains("По текущему положению телефона"))
        assertFalse(Regex("\\d{1,3}\\.\\d{4,}").containsMatchIn(reply))
        println("WINDY_REAL_HOME_REPLY: $reply")
    }
    @Test fun readsInstalledWindyChartThroughLocalScreenshotModule() {
        realPhoneSetup()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.startActivity(context.packageManager.getLaunchIntentForPackage(WindyForecast.PACKAGE)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        var card: ScreenTextNode? = null
        val cardUntil = System.currentTimeMillis() + 15_000
        while (card == null && System.currentTimeMillis() < cardUntil) {
            instrumentation.runOnMainSync { card = PhoneAccessibility.connected?.textTree(WindyForecast.PACKAGE)?.let(WindyForecast::card) }
            Thread.sleep(150)
        }
        instrumentation.runOnMainSync {
            val service = PhoneAccessibility.connected!!
            val frame = service.foregroundSnapshot()!!
            assertEquals(WindyForecast.PACKAGE, frame.app)
            assertNotNull("Forecast card is not visible; chart was not opened", card)
            assertTrue(WindyVisual.openCard(service, frame))
        }
        var table = false
        val tableUntil = System.currentTimeMillis() + 15_000
        while (!table && System.currentTimeMillis() < tableUntil) {
            instrumentation.runOnMainSync { table = PhoneAccessibility.connected?.elements()?.any {
                it.id == "${WindyForecast.PACKAGE}:id/text" && Regex("^(м/с|m/s|км/ч|km/h)$").matches(it.text)
            } == true }
            Thread.sleep(200)
        }
        assertTrue("Drawn forecast table did not load", table)
        Thread.sleep(700)
        val read = CountDownLatch(1)
        var result: AppScreenReader.Observation? = null
        var error: String? = null
        instrumentation.runOnMainSync {
            val frame = PhoneAccessibility.connected!!.foregroundSnapshot()!!
            AppScreenReader.read(context, frame, { true }) { value, failure -> result = value; error = failure; read.countDown() }
        }
        assertTrue("OCR timed out", read.await(25, TimeUnit.SECONDS))
        assertNotNull(error, result)
        assertTrue("No visual text", result!!.visual.isNotBlank())
        assertTrue("No recognized word boxes", result!!.words.isNotEmpty())
        println("WINDY_REAL_OCR: ${result!!.visual.take(1800)}")
        println("WINDY_REAL_OCR_WORDS: ${result!!.words.filter { it.text.any(Char::isDigit) }.take(70)}")
        assertTrue("Drawn forecast numbers were not recognized", result!!.words.count {
            Regex("^\\d{1,2}[,.]\\d$").matches(it.text) && !result!!.accessible.contains(it.text)
        } >= 3)
    }
}
