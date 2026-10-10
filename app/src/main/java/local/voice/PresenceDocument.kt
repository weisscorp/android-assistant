package local.voice

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Explicit coordinate records only. A map centre, JS tile URL or photo is not a person's position. */
internal object PresenceDocument {
    fun timestamp(value: Any?): Long? {
        if (value == null || value == JSONObject.NULL) return null
        val number = value.toString().toDoubleOrNull()
        if (number != null) return number.takeIf { it.isFinite() && it > 0 && it <= 32_503_680_000_000 }?.let {
            if (it < 100_000_000_000) (it * 1000).toLong() else it.toLong()
        }
        return runCatching { Instant.parse(value.toString()).toEpochMilli() }.getOrNull()
    }
    fun fix(value: JSONObject): GeoFix? {
        fun numeric(vararg keys: String) = keys.firstNotNullOfOrNull { key ->
            value.opt(key).takeIf { it != null && it != JSONObject.NULL }?.toString()?.toDoubleOrNull()
        }
        val lat = numeric("latitude", "lat") ?: return null
        val lon = numeric("longitude", "lon", "lng") ?: return null
        val time = listOf("updated_at", "updatedAt", "timestamp", "time").firstNotNullOfOrNull { timestamp(value.opt(it)) }
        return GeoFix(lat, lon, time, numeric("accuracy", "horizontal_accuracy"), numeric("heading", "bearing"), numeric("speed_mps")).takeIf { it.valid() }
    }
    fun parse(body: String, participant: String = ""): PresenceEvidence {
        require(body.length <= 1048576) { "Страница слишком большая." }
        val records = mutableListOf<PresenceEvidence>()
        fun walk(value: Any?, depth: Int) {
            if (depth > 6 || records.size > 50) return
            when (value) {
                is JSONArray -> (0 until minOf(value.length(), 100)).forEach { walk(value.opt(it), depth + 1) }
                is JSONObject -> {
                    val properties = value.optJSONObject("properties") ?: value
                    val identity = listOf("name", "person", "participant", "vehicle", "id").firstNotNullOfOrNull { key ->
                        properties.optString(key).takeIf { it.isNotBlank() }
                    }.orEmpty()
                    var point = fix(value)
                    if (value.optString("type") == "Feature" && value.optJSONObject("geometry")?.optString("type") == "Point") {
                        val coords = value.getJSONObject("geometry").optJSONArray("coordinates")
                        if (coords != null && coords.length() >= 2) {
                            val combined = JSONObject(properties.toString()).put("latitude", coords.opt(1)).put("longitude", coords.opt(0))
                            point = fix(combined)
                        }
                    }
                    if (point == null) {
                        val nested = value.optJSONObject("location") ?: value.optJSONObject("position")
                        if (nested != null && fix(nested) != null) {
                            val combined = JSONObject(properties.toString())
                            nested.keys().forEach { key -> combined.put(key, nested.opt(key)) }
                            point = fix(combined)
                        }
                    }
                    if (point != null) {
                        val place = listOf("intersection", "street", "place_name", "address").firstNotNullOfOrNull { key ->
                            properties.opt(key).takeIf { it is String && it.isNotBlank() }?.toString()
                        }.orEmpty()
                        records += PresenceEvidence(point, place.take(220), identity.take(100), live = point.updatedAt != null)
                    } else value.keys().forEach { key -> if (key !in setOf("center", "viewport", "bounds", "map", "tiles")) walk(value.opt(key), depth + 1) }
                }
            }
        }
        val text = body.trim()
        if (text.startsWith("{")) walk(JSONObject(text), 0)
        else if (text.startsWith("[")) walk(JSONArray(text), 0)
        else {
            // Structured schema.org geo only; arbitrary JavaScript often contains viewport centres.
            val blocks = Regex("<script\\b[^>]*type\\s*=\\s*[\"']application/ld\\+json[\"'][^>]*>(.*?)</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .findAll(text).take(10).map { it.groupValues[1] }
            blocks.forEach { block -> runCatching { if (block.trim().startsWith("[")) walk(JSONArray(block), 0) else walk(JSONObject(block), 0) } }
        }
        val matching = records.filter { participant.isBlank() || PresenceFacts.key(it.person) == PresenceFacts.key(participant) }
        if (participant.isNotBlank() && matching.size > 1 && matching.all { it.fix?.updatedAt != null }) return matching.maxBy { it.fix!!.updatedAt!! }
        val unique = matching.groupBy { Triple(it.person, it.fix?.latitude, it.fix?.longitude) }.values.map { duplicates -> duplicates.maxBy { it.fix?.updatedAt ?: 0 } }
        require(unique.size == 1) { if (unique.isEmpty()) "Источник не отдал однозначное положение. Нужен JSON-адрес или сохранённое правило чтения знания."
            else "В источнике несколько людей или точек. Укажите точное имя участника в настройке источника." }
        return unique.single()
    }
    fun telegramText(text: String, participant: String, snapshot: Boolean = false): PresenceEvidence? {
        // Bind the distance/update to the same person's row; never take a global map label.
        val lines = text.lines().map(String::trim).filter(String::isNotBlank)
        val rows = if (participant.isNotBlank()) lines.indices.filter { i -> PresenceFacts.key(lines[i]) == PresenceFacts.key(participant) }
            .map { i -> lines.drop(i + 1).take(2).joinToString(" ") }
        else lines.filter { Regex("(?:обновл[её]н[ао]?|updated)", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        if (rows.size != 1) return null
        val row = rows.single()
        val match = Regex("(\\d+(?:[.,]\\d+)?)\\s*(км|km|м|m)\\s*(?:отсюда|away|от вас)", RegexOption.IGNORE_CASE).find(row)
        val distance = match?.let { it.groupValues[1].replace(',', '.').toDouble() * if (it.groupValues[2].lowercase() in setOf("км", "km")) 1000 else 1 }
        val age = Regex("((?:обновл[её]н[ао]?|updated)\\s+.+?)(?=\\s*[-–—]\\s*\\d|$)", RegexOption.IGNORE_CASE).find(row)?.groupValues?.get(1).orEmpty()
        if (distance == null && age.isBlank()) return null
        return PresenceEvidence(person = participant, distance = distance, ageText = age.take(120), live = !snapshot, snapshot = snapshot)
    }
}
