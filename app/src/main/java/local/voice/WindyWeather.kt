package local.voice

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import java.time.LocalDate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Bounded read-only navigation of the installed app, with explicit dated cache on lock screen. */
internal object WindyWeather {
    private const val MAX_CACHE_AGE = 6 * 60 * 60 * 1000L
    private fun preferences(context: Context) = context.getSharedPreferences("weather", 0)
    internal fun cached(context: Context, request: WeatherRequest, target: String? = null): String? = runCatching {
        val data = JSONObject(preferences(context).getString("windy", null) ?: return null)
        val at = data.getLong("observedAt")
        val savedKey = data.optString("target")
        if (target != null && !sameTarget(target, savedKey)) return null
        if (System.currentTimeMillis() - at !in 0..MAX_CACHE_AGE) return null
        val entries = data.getJSONObject("days")
        val days = entries.keys().asSequence().associate { LocalDate.parse(it) to entries.getString(it) }
        val forecast = WindyForecast(data.getString("place"), days, data.optString("wind").takeIf(String::isNotBlank))
        "Сохранённые данные windy.app, прочитанные ${SimpleDateFormat("d MMMM, HH:mm", Locale.forLanguageTag("ru")).format(Date(at))}. ${forecast.reply(if (target != null) request.copy(place = null) else request)}"
    }.getOrNull()
    private fun remember(context: Context, forecast: WindyForecast, target: String) {
        val days = JSONObject().apply { forecast.days.forEach { (day, value) -> put(day.toString(), value) } }
        val data = JSONObject().put("observedAt", System.currentTimeMillis()).put("target", target).put("place", forecast.place).put("days", days)
        forecast.cardWind?.let { data.put("wind", it) }
        preferences(context).edit().putString("windy", data.toString()).apply()
    }
    internal fun sameTarget(requested: String, saved: String): Boolean {
        if (requested == saved && requested.isNotBlank()) return true
        if (!requested.startsWith("gps:") || !saved.startsWith("gps:")) return false
        fun point(key: String): WeatherLocation.Point? = runCatching {
            val values = key.removePrefix("gps:").split(',').map { it.trim().toDouble() }
            require(values.size == 2 && values[0] in -90.0..90.0 && values[1] in -180.0..180.0)
            WeatherLocation.Point(values[0], values[1])
        }.getOrNull()
        val a = point(requested) ?: return false
        val b = point(saved) ?: return false
        val latitude = Math.toRadians((a.latitude + b.latitude) / 2)
        val north = Math.toRadians(a.latitude - b.latitude) * 6371000
        val east = Math.toRadians(a.longitude - b.longitude) * kotlin.math.cos(latitude) * 6371000
        return kotlin.math.hypot(north, east) <= 1000
    }
    fun navigation(context: Context, request: WeatherRequest, active: () -> Boolean, say: (String) -> Unit, done: (String) -> Unit): WindyNavigation {
        return WindyNavigation(context, request, active, say, { key -> cached(context, request, key) }, done = { forecast, key, error ->
            if (forecast != null && key != null) {
                remember(context, forecast, key)
                // The adapter verified the selected place against the original search.
                val spoken = forecast.copy(place = WeatherSpeech.place(forecast.place))
                val prefix = if (request.place == null) "На главной карточке windy.app. " else ""
                val details = request.wind || request.rain || Regex("влажност|облачност", RegexOption.IGNORE_CASE).containsMatchIn(request.query)
                if (details) WindyVisual.read(context, request, active) { text ->
                    if (active()) done(prefix + spoken.reply(request.copy(place = null, wind = false, rain = false)) + " " + text)
                } else done(prefix + spoken.reply(request.copy(place = null)))
            } else done(error ?: "Прогноз не прочитан.")
        })
    }
}
