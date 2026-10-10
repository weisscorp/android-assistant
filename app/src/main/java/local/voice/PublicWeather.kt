package local.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection

internal data class WeatherPlace(val id: Long, val name: String, val latitude: Double, val longitude: Double, val kind: String = "PPL") {
    val point get() = WeatherLocation.Point(latitude, longitude)
    val locality get() = name.substringBefore(',').substringBefore('(').trim()
}
internal interface WeatherDataSource {
    fun places(query: String, request: WeatherHttpRequest): List<WeatherPlace>
    fun forecast(point: WeatherLocation.Point, request: WeatherHttpRequest): PublicWeatherForecast
}
/** Each query owns its connection; cancelling cannot leave a late result in a new conversation. */
internal class WeatherHttpRequest {
    @Volatile var cancelled = false; private set
    private var connection: HttpsURLConnection? = null
    @Synchronized fun cancel() { cancelled = true; connection?.disconnect(); connection = null }
    @Synchronized private fun bind(value: HttpsURLConnection) {
        check(!cancelled); connection = value
    }
    fun get(url: URL): JSONObject {
        require(url.protocol == "https")
        val socket = url.openConnection() as HttpsURLConnection
        bind(socket)
        try {
            socket.instanceFollowRedirects = false
            socket.connectTimeout = 8000; socket.readTimeout = 10000
            socket.setRequestProperty("Accept", "application/json")
            socket.setRequestProperty("User-Agent", "LocalVoice-Weather/1")
            check(socket.responseCode == 200 && !cancelled)
            val bytes = socket.inputStream.use { it.readNBytes(131_073) }
            check(bytes.size <= 131_072 && !cancelled)
            return JSONObject(String(bytes, Charsets.UTF_8)).also { check(!it.optBoolean("error")) }
        } finally {
            synchronized(this) { if (connection === socket) connection = null }
            socket.disconnect()
        }
    }
}
internal object OpenMeteo : WeatherDataSource {
    override fun places(query: String, request: WeatherHttpRequest): List<WeatherPlace> = decodePlaces(request.get(URL(
        "https://geocoding-api.open-meteo.com/v1/search?name=${URLEncoder.encode(query, "UTF-8")}&count=5&language=ru&format=json")))
    override fun forecast(point: WeatherLocation.Point, request: WeatherHttpRequest): PublicWeatherForecast {
        require(point.latitude.isFinite() && point.longitude.isFinite() && point.latitude in -90.0..90.0 && point.longitude in -180.0..180.0)
        return decode(request.get(URL("https://api.open-meteo.com/v1/forecast?latitude=${point.latitude}&longitude=${point.longitude}" +
            "&current=temperature_2m,relative_humidity_2m,apparent_temperature,precipitation,weather_code,cloud_cover,wind_speed_10m,wind_direction_10m,wind_gusts_10m" +
            "&hourly=precipitation_probability&daily=temperature_2m_max,temperature_2m_min,precipitation_sum,precipitation_probability_max,wind_speed_10m_max,wind_gusts_10m_max,weather_code" +
            "&wind_speed_unit=ms&temperature_unit=celsius&precipitation_unit=mm&timezone=auto&forecast_days=3")))
    }
    internal fun decodePlaces(data: JSONObject): List<WeatherPlace> {
        val results = data.optJSONArray("results") ?: return emptyList()
        require(results.length() <= 5)
        return (0 until results.length()).mapNotNull { i -> runCatching {
            val item = results.getJSONObject(i)
            val lat = numeric(item.opt("latitude"), -90.0..90.0) ?: error("latitude")
            val lon = numeric(item.opt("longitude"), -180.0..180.0) ?: error("longitude")
            fun label(key: String) = (item.opt(key) as? String)?.trim()?.takeIf { it.length in 1..100 && it.none(Char::isISOControl) }
            val name = label("name") ?: error("name")
            val kind = label("feature_code").orEmpty()
            val title = when { kind == "AIRP" -> "$name (аэропорт)"; kind.startsWith("ADM") -> "$name (административная территория)"; else -> name }
            val parts = listOfNotNull(title, label("admin2"), label("admin1"), label("country")).distinct()
            WeatherPlace(item.getLong("id"), parts.joinToString(", "), lat, lon, kind)
        }.getOrNull() }.distinctBy { it.id }
    }
    internal fun decode(data: JSONObject): PublicWeatherForecast {
        require(!data.optBoolean("error"))
        val zone = ZoneId.of(data.getString("timezone"))
        fun value(obj: JSONObject, units: JSONObject, name: String, unit: String, range: ClosedFloatingPointRange<Double>): Double? =
            if (units.optString(name) == unit) numeric(obj.opt(name), range) else null
        fun code(raw: Any?) = numeric(raw, 0.0..99.0)?.takeIf { it % 1 == 0.0 }?.toInt()
        val current = data.optJSONObject("current")?.let { current ->
            val units = data.optJSONObject("current_units") ?: JSONObject()
            val interval = (current.opt("interval") as? Number)?.toDouble()?.takeIf { it % 60 == 0.0 && it in 60.0..3600.0 }?.toInt()
            val time = runCatching { LocalDateTime.parse(current.getString("time")) }.getOrNull()
            if (time == null || interval == null || units.optString("time") != "iso8601" || units.optString("interval") != "seconds") null
            else PublicWeatherForecast.Current(time, interval,
                value(current, units, "temperature_2m", "°C", -100.0..70.0),
                value(current, units, "apparent_temperature", "°C", -150.0..100.0),
                value(current, units, "relative_humidity_2m", "%", 0.0..100.0),
                value(current, units, "cloud_cover", "%", 0.0..100.0),
                value(current, units, "wind_speed_10m", "m/s", 0.0..150.0),
                value(current, units, "wind_gusts_10m", "m/s", 0.0..150.0),
                value(current, units, "wind_direction_10m", "°", 0.0..360.0),
                value(current, units, "precipitation", "mm", 0.0..1000.0),
                if (units.optString("weather_code") == "wmo code") code(current.opt("weather_code")) else null)
        }
        fun arrayValue(obj: JSONObject, units: JSONObject, name: String, unit: String, i: Int, size: Int, range: ClosedFloatingPointRange<Double>): Double? {
            val values = obj.optJSONArray(name) ?: return null
            return if (units.optString(name) == unit && values.length() == size) numeric(values.opt(i), range) else null
        }
        val daily = data.optJSONObject("daily") ?: JSONObject()
        val units = data.optJSONObject("daily_units") ?: JSONObject()
        val times = daily.optJSONArray("time") ?: JSONArray()
        require(times.length() <= 16)
        val days = (0 until times.length()).map { i ->
            require(units.optString("time") == "iso8601")
            val date = LocalDate.parse(times.getString(i))
            fun n(name: String, unit: String, range: ClosedFloatingPointRange<Double>) = arrayValue(daily, units, name, unit, i, times.length(), range)
            PublicWeatherForecast.Day(date, n("temperature_2m_min", "°C", -100.0..70.0), n("temperature_2m_max", "°C", -100.0..70.0),
                n("wind_speed_10m_max", "m/s", 0.0..150.0), n("wind_gusts_10m_max", "m/s", 0.0..150.0),
                n("precipitation_sum", "mm", 0.0..2000.0), n("precipitation_probability_max", "%", 0.0..100.0),
                n("weather_code", "wmo code", 0.0..99.0)?.let { code(it) })
        }
        require(days.distinctBy { it.date }.size == days.size)
        val hourly = data.optJSONObject("hourly") ?: JSONObject()
        val hourUnits = data.optJSONObject("hourly_units") ?: JSONObject()
        val hours = hourly.optJSONArray("time") ?: JSONArray()
        require(hours.length() <= 384)
        val probabilities = (0 until hours.length()).mapNotNull { i ->
            if (hourUnits.optString("time") != "iso8601") null else {
                val time = runCatching { LocalDateTime.parse(hours.getString(i)) }.getOrNull()
                val probability = arrayValue(hourly, hourUnits, "precipitation_probability", "%", i, hours.length(), 0.0..100.0)
                if (time != null && probability != null) time to probability else null
            }
        }.toMap()
        return PublicWeatherForecast(zone, current, days, probabilities)
    }
    private fun numeric(raw: Any?, range: ClosedFloatingPointRange<Double>) = (raw as? Number)?.toDouble()?.takeIf { it.isFinite() && it in range }
    internal fun placeQuery(place: String): String {
        val value = place.trim().lowercase(Locale.ROOT)
        return when (value) { "москве", "москву" -> "Москва"; "санкт-петербурге" -> "Санкт-Петербург"; else ->
            if (value.length > 4 && value.endsWith("е")) value.dropLast(1) else value }
    }
}
internal class PublicWeatherQuery(private val context: Context, private val request: WeatherRequest,
    private val active: () -> Boolean, private val say: (String) -> Unit, private val done: (String) -> Unit,
    private val source: WeatherDataSource = OpenMeteo,
    private val location: ((() -> Boolean, (WeatherLocation.Point?, String?) -> Unit) -> Unit) = { allowed, callback -> WeatherLocation.get(context, allowed, callback) }
) {
    private val trace = InteractionJournal.current?.begin("weather", InteractionJournal.data("request" to request.toString()))
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val http = WeatherHttpRequest()
    private var choices = emptyList<WeatherPlace>()
    private var choiceUntil = 0L
    private fun available() = !http.cancelled && active()
    fun cancel() { http.cancel(); worker.shutdownNow() }
    private fun finish(text: String) { if (available()) { trace?.result(InteractionJournal.data("text" to text)); cancel(); done(text) } }
    private fun <T> work(load: () -> T, complete: (T) -> Unit) {
        val timeout = Runnable { finish("Open-Meteo не ответил вовремя. Повторите запрос.") }
        main.postDelayed(timeout, 25_000)
        worker.execute {
            val result = runCatching(load)
            result.fold({ trace?.event("data", InteractionJournal.data("value" to it.toString())) }, { trace?.error(it) })
            main.post {
                main.removeCallbacks(timeout)
                if (available()) result.fold(complete) { finish("Не удалось получить погоду из Open-Meteo. Проверьте интернет и повторите запрос. Данные не получены.") }
            }
        }
    }
    fun start() {
        if (request.day == null) { finish("Пока поддерживаю текущую погоду, завтра и послезавтра. Назовите один из этих сроков."); return }
        val place = request.place
        if (place == null) location(::available) { point, error ->
            if (available()) {
                if (point == null) finish(error ?: "Местоположение недоступно. Назовите город.")
                else { trace?.event("location", InteractionJournal.data("point" to point.toString())); load(point, "") }
            }
        } else work({ source.places(OpenMeteo.placeQuery(place), http) }) { found ->
            val exact = found.filter { it.locality.equals(OpenMeteo.placeQuery(place), true) }
            val settlements = exact.filter { it.kind.startsWith("PPL") }
            choices = (settlements.ifEmpty { exact.ifEmpty { found } }).take(5)
            when (choices.size) {
                0 -> finish("Не нашёл этот город в Open-Meteo. Назовите город и страну.")
                1 -> choose(0)
                else -> {
                    choiceUntil = SystemClock.elapsedRealtime() + 60_000
                    say("Нашёл несколько мест. " + choices.mapIndexed { i, p -> "${i + 1}: ${p.name}." }.joinToString(" ") + " Назовите номер или скажите отмена.")
                }
            }
        }
    }
    private fun load(point: WeatherLocation.Point, name: String) = work({ source.forecast(point, http) }) { forecast -> finish(forecast.reply(request, name)) }
    private fun choose(index: Int) {
        val place = choices.getOrNull(index)
        if (place == null) { say("Такого номера нет. Назовите номер места из списка."); return }
        choiceUntil = 0
        load(place.point, place.name)
    }
    fun answer(command: VoiceCommand): Boolean {
        if (choiceUntil == 0L) return false
        if (command == VoiceCommand.Cancel) { finish("Запрос погоды отменён."); return true }
        if (command !is VoiceCommand.Choose && command != VoiceCommand.Confirm) return false
        if (SystemClock.elapsedRealtime() > choiceUntil) finish("Выбор места истёк. Повторите запрос.")
        else if (command is VoiceCommand.Choose) choose(command.index) else say("Назовите номер места из списка.")
        return true
    }
}
