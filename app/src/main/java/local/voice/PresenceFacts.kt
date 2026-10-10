package local.voice

import java.net.URI
import java.net.URLDecoder
import java.util.Locale
import kotlin.math.*

/** Coordinates are evidence, never inferred from map labels or a map's viewport. */
data class GeoFix(val latitude: Double, val longitude: Double, val updatedAt: Long? = null,
    val accuracy: Double? = null, val heading: Double? = null, val speedMps: Double? = null) {
    fun valid() = latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
        (accuracy == null || accuracy.isFinite() && accuracy in 0.0..10000.0) &&
        (heading == null || heading.isFinite() && heading in 0.0..360.0) &&
        (speedMps == null || speedMps.isFinite() && speedMps in 0.0..100.0)
    fun fresh(now: Long) = updatedAt?.let { now - it in 0..120_000 } == true
}
data class PresenceEvidence(val fix: GeoFix? = null, val place: String = "", val person: String = "",
    val distance: Double? = null, val ageText: String = "", val live: Boolean = false, val snapshot: Boolean = false, val fixedPlace: Boolean = false,
    val status: String = "", val freshnessRejected: Boolean = false)

object PresenceFacts {
    fun key(value: String) = value.lowercase(Locale.ROOT).replace('ё', 'е').trim().replace(Regex("\\s+"), " ")
    fun validName(value: String) = value.trim().length in 1..100 && value.none { it.isISOControl() }
    fun distance(a: GeoFix, b: GeoFix): Double {
        val p1 = Math.toRadians(a.latitude); val p2 = Math.toRadians(b.latitude)
        val dp = p2 - p1; val dl = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 6371000.0 * 2 * atan2(sqrt(h.coerceIn(0.0, 1.0)), sqrt((1 - h).coerceIn(0.0, 1.0)))
    }
    fun bearing(a: GeoFix, b: GeoFix): Double {
        val p1 = Math.toRadians(a.latitude); val p2 = Math.toRadians(b.latitude)
        val dl = Math.toRadians(b.longitude - a.longitude)
        return (Math.toDegrees(atan2(sin(dl) * cos(p2), cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl))) + 360) % 360
    }
    fun movement(previous: GeoFix?, current: GeoFix, now: Long): Double? {
        if (!current.valid() || !current.fresh(now)) return null
        val reported = current.heading.takeIf { current.speedMps?.let { it in 0.5..65.0 } == true }
        val old = previous ?: return reported
        val elapsed = (current.updatedAt ?: return null) - (old.updatedAt ?: return null)
        if (!old.valid() || !old.fresh(now) || elapsed !in 5000..120000) return reported
        val moved = distance(old, current)
        if (moved < maxOf(25.0, (old.accuracy ?: 25.0) + (current.accuracy ?: 25.0)) || moved / (elapsed / 1000.0) > 65) return null
        return bearing(old, current)
    }
    fun direction(heading: Double) = listOf("север", "северо-восток", "восток", "юго-восток", "юг", "юго-запад", "запад", "северо-запад")[((heading + 22.5) / 45).toInt() % 8]
    fun meters(value: Double): String = if (value < 10) "менее 10 метров" else if (value < 1000) "${(value / 10).roundToInt() * 10} метров"
        else "${String.format(Locale.forLanguageTag("ru"), "%.1f", value / 1000)} километра"
    fun speech(name: String, evidence: PresenceEvidence, now: Long, me: GeoFix? = null,
        previous: GeoFix? = null, landmarks: List<Pair<String, GeoFix>> = emptyList()): String = buildString {
        append("$name. ")
        if (evidence.status.isNotBlank()) append("${evidence.status.take(700)} ")
        if (evidence.snapshot) append("Сохранённый снимок, положение сейчас по нему проверить нельзя. ")
        val point = evidence.fix?.takeIf { it.valid() }
        if (evidence.place.isNotBlank()) append("${evidence.place.take(220)}. ")
        else if (point != null) append("Точка получена, название улицы недоступно. ")
        if (point != null) {
            val at = point.updatedAt
            when {
                evidence.fixedPlace -> Unit
                evidence.freshnessRejected -> append("Источник пометил точку устаревшей; текущее положение неизвестно. ")
                at == null -> append("Это точка без времени обновления; актуальность неизвестна. ")
                at > now + 30_000 -> append("Время источника некорректно. ")
                point.fresh(now) -> append("Обновлено ${(now - at) / 1000} секунд назад. ")
                else -> append("Последняя точка ${(now - at).coerceAtLeast(0) / 60000} минут назад; текущее положение неизвестно. ")
            }
            if (evidence.live && !evidence.snapshot && !evidence.freshnessRejected) movement(previous, point, now)?.let { heading ->
                val target = landmarks.filter { (_, place) -> place.valid() && distance(point, place) > 100 }
                    .filter { (_, place) -> abs((bearing(point, place) - heading + 540) % 360 - 180) <= 25 }
                    .minByOrNull { (_, place) -> distance(point, place) }
                append(if (target == null) "Движется на ${direction(heading)}. " else "Движется в сторону ${target.first}. ")
            }
            if (me?.valid() == true && me.fresh(now)) {
                append("До вас по прямой примерно ${meters(distance(point, me))}")
                if ((me.accuracy ?: 0.0) + (point.accuracy ?: 0.0) > 100) append("; погрешность положения до ${meters((me.accuracy ?: 0.0) + (point.accuracy ?: 0.0))}")
                if ((!point.fresh(now) || evidence.freshnessRejected) && !evidence.fixedPlace) append(" от сохранённой точки")
                append(". ")
            } else append("Расстояние до вас не рассчитано: нет свежего положения телефона. ")
        } else {
            if (evidence.ageText.isNotBlank()) append("${evidence.ageText.take(120)}. ")
            evidence.distance?.takeIf { it.isFinite() && it >= 0 }?.let {
                append("${if (evidence.snapshot) "На снимке" else "Источник показывает"}: примерно ${meters(it)} до вас. ")
            }
            if (evidence.status.isBlank()) append("Точные координаты и направление движения источник не предоставил.")
        }
    }.trim()
    fun pointFromLink(text: String): GeoFix? = runCatching {
        val uri = URI(text.trim())
        fun pair(raw: String): GeoFix? {
            val match = Regex("^\\s*(-?\\d{1,2}(?:\\.\\d+)?)\\s*,\\s*(-?\\d{1,3}(?:\\.\\d+)?)(?:\\s*\\([^)]*\\))?\\s*$").matchEntire(raw) ?: return null
            return GeoFix(match.groupValues[1].toDouble(), match.groupValues[2].toDouble()).takeIf { it.valid() }
        }
        val query = (uri.rawQuery ?: uri.rawSchemeSpecificPart.substringAfter('?', "")).split('&').mapNotNull {
            val pieces = it.split('=', limit = 2)
            if (pieces.size == 2) pieces[0] to URLDecoder.decode(pieces[1], "UTF-8") else null
        }.toMap()
        if (uri.scheme == "geo") {
            if (query.containsKey("q")) pair(query["q"].orEmpty()) else pair(uri.rawSchemeSpecificPart.substringBefore('?').substringBefore(';'))
        }
        else {
            val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
            when {
                host == "openstreetmap.org" || host == "www.openstreetmap.org" -> {
                    val lat = query["mlat"]?.toDoubleOrNull(); val lon = query["mlon"]?.toDoubleOrNull()
                    if (lat != null && lon != null) GeoFix(lat, lon).takeIf { it.valid() } else null
                }
                host in setOf("maps.google.com", "www.google.com", "google.com") -> pair(query["q"] ?: query["query"].orEmpty())
                else -> null
            }
        }
    }.getOrNull()
    fun query(raw: String): String? {
        val value = raw.trim().trimEnd('.', '!', '?')
        val subject = Regex("^(?:где|местоположение|найди\\s+местоположение|сколько\\s+(?:метров|расстояние)\\s+до)\\s+(.+)$", RegexOption.IGNORE_CASE)
            .matchEntire(value)?.groupValues?.get(1) ?: return null
        val modifiers = "(?:сейчас|находится|находятся|расположен[аоы]?)"
        return subject.replace(Regex("^(?:$modifiers\\s+)+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("(?:\\s+$modifiers)+$", RegexOption.IGNORE_CASE), "").trim().takeIf(::validName)
    }
    fun sharedReference(raw: String): String {
        val text = raw.trim()
        if (text.startsWith("https://") && !text.contains(Regex("\\s")) || text.startsWith("tg://") || text.startsWith("geo:")) return text
        val links = Regex("(?:https://|tg://|geo:)[^\\s<>\"']+").findAll(text).map { it.value.trimEnd('.', ',', ';') }.toList()
        return links.singleOrNull() ?: text
    }
    fun forSpeech(raw: String): String {
        val links=Regex("(?:https?://|tg://|geo:)[^\\s<>\"']+")
        return links.replace(raw) { match ->
            val host=runCatching { URI(match.value).host }.getOrNull()
            if (host.isNullOrBlank()) "ссылка на место" else "ссылка на $host"
        }.replace(Regex("(?<!\\d)-?\\d{1,2}\\.\\d{3,}\\s*[,;]\\s*-?\\d{1,3}\\.\\d{3,}(?!\\d)"),"координаты точки")
    }
}
