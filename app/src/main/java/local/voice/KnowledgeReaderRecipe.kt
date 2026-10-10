package local.voice

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI

/** A declarative reader stored with the knowledge. No script execution or site-specific classes. */
internal data class KnowledgeReaderRecipe(val path: String, val inheritQuery: Boolean, val fields: Map<String, String>) {
    companion object {
        private val supported = setOf("latitude", "longitude", "updated_at", "age_seconds", "accuracy", "heading", "speed_mps", "address", "name", "fresh", "simulated", "status")
        fun decode(value: String): KnowledgeReaderRecipe {
            val json = JSONObject(value)
            require(json.keys().asSequence().all { it in setOf("path", "inheritQuery", "fields") }) { "Неизвестное поле правила чтения." }
            val path = json.optString("path")
            require(path.length <= 1000 && path.none { it.isISOControl() }) { "Слишком длинный путь данных." }
            val mapping = json.getJSONObject("fields")
            require(mapping.length() in 1..supported.size && mapping.keys().asSequence().all { it in supported }) { "Неизвестное поле данных." }
            val fields = mapping.keys().asSequence().associateWith { key -> mapping.getString(key).also { pointer ->
                require(pointer.length <= 400 && pointer.startsWith('/') && pointer.count { it == '/' } <= 12) { "Нужны JSON Pointer пути к полям." }
            } }
            require(("latitude" in fields) == ("longitude" in fields)) { "Широта и долгота нужны вместе." }
            return KnowledgeReaderRecipe(path, json.optBoolean("inheritQuery"), fields)
        }
        fun at(root: Any, pointer: String): Any? {
            var value: Any? = root
            pointer.drop(1).split('/').forEach { part ->
                val key = part.replace("~1", "/").replace("~0", "~")
                value = when (val current = value) { is JSONObject -> current.opt(key); is JSONArray -> key.toIntOrNull()?.let { current.opt(it) }; else -> null }
            }
            return value.takeIf { it != JSONObject.NULL }
        }
    }
    fun endpoint(source: String): String {
        require(PresenceHttp.validUrl(source)) { "Для знания нужен HTTPS-адрес." }
        val origin = URI(source)
        val destination = if (path.isBlank()) origin else origin.resolve(path)
        require(destination.host == origin.host && destination.port == origin.port && PresenceHttp.validUrl(destination.toString())) { "Путь данных должен вести на тот же HTTPS-сервер." }
        return if (inheritQuery && destination.rawQuery == null && origin.rawQuery != null) destination.toString() + "?" + origin.rawQuery else destination.toString()
    }
    fun parse(body: String, now: Long = System.currentTimeMillis()): PresenceEvidence {
        val root = JSONTokener(body).nextValue()
        require(root is JSONObject || root is JSONArray) { "По правилу ожидались JSON-данные." }
        fun field(name: String) = fields[name]?.let { at(root, it) }
        fun number(name: String) = field(name)?.toString()?.toDoubleOrNull()?.takeIf(Double::isFinite)
        fun text(name: String) = (field(name) as? String).orEmpty().take(700)
        for (flag in listOf("fresh", "simulated")) if (flag in fields && field(flag) != null) require(field(flag) is Boolean) { "Поле $flag должно быть логическим." }
        val simulated = field("simulated") == true
        val missing = fields.keys.filter { field(it) == null }
        val status = text("status")
        if (simulated) return PresenceEvidence(status = "Источник пометил данные как симуляцию. Реальное положение неизвестно.")
        if ("latitude" in missing || "longitude" in missing) return PresenceEvidence(status = status.ifBlank { "Источник не передал положение." })
        require(missing.isEmpty()) { "Поля знания изменились; обновите правило чтения. Текущее положение не подтверждено." }
        val age = number("age_seconds")?.takeIf { it in 0.0..31_536_000.0 }
        val time = PresenceDocument.timestamp(field("updated_at")) ?: age?.let { now - (it * 1000).toLong() }
        val lat = number("latitude"); val lon = number("longitude")
        if ("latitude" in fields) require(lat != null && lon != null) { "Координаты в знании не являются числами." }
        val point = if (lat != null && lon != null) GeoFix(lat, lon, time, number("accuracy"), number("heading"), number("speed_mps")).also { require(it.valid()) { "Некорректное положение в данных." } } else null
        return PresenceEvidence(point, text("address").take(220), text("name").take(100), live = point?.fresh(now) == true,
            status = status, freshnessRejected = field("fresh") == false)
    }
}

internal object KnowledgeDocument {
    fun text(body: String): String {
        if (body.trimStart().startsWith('{') || body.trimStart().startsWith('[')) {
            fun strip(value: Any?, depth: Int): Any? {
                if (depth > 6) return "Данные сокращены"
                return when (value) {
                    is JSONObject -> JSONObject().apply { value.keys().asSequence().take(50).filter { it.lowercase() !in setOf("latitude", "longitude", "lat", "lon", "lng", "coordinates", "geometry", "map", "center", "viewport", "tiles", "bounds") }.forEach { put(it, strip(value.opt(it), depth + 1)) } }
                    is JSONArray -> JSONArray().apply { (0 until minOf(value.length(), 20)).forEach { put(strip(value.opt(it), depth + 1)) } }
                    else -> value
                }
            }
            return PresenceFacts.forSpeech(strip(JSONTokener(body).nextValue(), 0).toString().take(5000))
        }
        return android.text.Html.fromHtml(body.replace(Regex("(?is)<(script|style)\\b[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?s)<!--.*?-->"), " "), android.text.Html.FROM_HTML_MODE_COMPACT).toString().replace(Regex("\\s+"), " ").trim().take(5000)
    }
}
