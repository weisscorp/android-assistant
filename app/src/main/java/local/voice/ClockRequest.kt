package local.voice

import java.time.*
import java.util.Locale

enum class ClockKind { TIMER, ALARM, STOPWATCH, REMINDER }
enum class ClockOperation { CREATE, STATUS, CANCEL, START, PAUSE, RESUME, RESET, LAP }
data class ClockMoment(val delaySeconds: Long? = null, val hour: Int? = null, val minute: Int = 0,
    val dayOffset: Int? = null, val date: LocalDate? = null, val weekday: DayOfWeek? = null) {
    fun resolve(now: ZonedDateTime): Instant? {
        delaySeconds?.let { return now.toInstant().plusSeconds(it) }
        val h = hour ?: return null
        var day = date ?: now.toLocalDate().plusDays(dayOffset?.toLong() ?: 0)
        if (weekday != null) day = day.plusDays(((weekday.value - day.dayOfWeek.value + 7) % 7).toLong())
        var local = day.atTime(h, minute)
        // A nonexistent wall time during a DST jump must be clarified, never silently shifted.
        if (now.zone.rules.getValidOffsets(local).size != 1) return null
        var result = local.atZone(now.zone)
        if (!result.isAfter(now) && date == null && dayOffset == null) {
            local = local.plusDays(if (weekday == null) 1 else 7)
            if (now.zone.rules.getValidOffsets(local).size != 1) return null
            result = local.atZone(now.zone)
        }
        return result.toInstant().takeIf { it.isAfter(now.toInstant()) }
    }
}
data class ClockRequest(val kind: ClockKind, val operation: ClockOperation, val seconds: Long? = null,
    val moment: ClockMoment? = null, val title: String = "", val problem: String? = null)

/** Recognizes device commands before any model is loaded. Parsing never schedules an action. */
object ClockRequests {
    const val MAX_TIMER_SECONDS = 7 * 24 * 60 * 60L
    private val infinitives = mapOf("поставить" to "поставь", "установить" to "установи", "создать" to "создай", "запустить" to "запусти",
        "включить" to "включи", "остановить" to "останови", "выключить" to "выключи", "отключить" to "отключи", "удалить" to "удали",
        "продолжить" to "продолжи", "возобновить" to "возобнови", "сбросить" to "сбрось", "напомнить" to "напомни")
    private fun normalize(text: String): String {
        val first = wordRegex("^\\s*(\\p{L}+)\\b").find(text) ?: return text
        val replacement = infinitives[first.groupValues[1].lowercase(Locale.ROOT)] ?: return text
        return text.replaceRange(first.range,replacement)
    }
    private val numberNames by lazy {
        (0..999).associateBy { RussianNumbers.expand(it.toString()) }.toMutableMap().apply {
            put("одна", 1); put("одну", 1); put("две", 2)
        }
    }
    private val numberWord = "ноль|нуль|один|одна|одну|два|две|три|четыре|пять|шесть|семь|восемь|девять|десять|одиннадцать|двенадцать|тринадцать|четырнадцать|пятнадцать|шестнадцать|семнадцать|восемнадцать|девятнадцать|двадцать|тридцать|сорок|пятьдесят|шестьдесят|семьдесят|восемьдесят|девяносто|сто|двести|триста|четыреста|пятьсот|шестьсот|семьсот|восемьсот|девятьсот"
    private val numeral = "(?:\\d+(?:[.,]\\d+)?|(?:$numberWord)(?:\\s+(?:$numberWord)){0,3})"
    private val durationPart = Regex("(?<![\\p{L}\\d])($numeral)\\s*(час(?:а|ов)?|минут(?:а|ы|у)?|мин\\.?|секунд(?:а|ы|у)?|сек\\.?)(?![\\p{L}])")
    private val clockNumber = "(?:\\d{1,2}|${(0..99).map { RussianNumbers.expand(it.toString()) }.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }})"
    private val clockPart = Regex("(?:^|\\s)(?:в|на)\\s+($clockNumber)(?:\\s*[:.]\\s*(\\d{1,2})|\\s+(?:час(?:а|ов)?\\s+)?($clockNumber)(?:\\s+минут(?:ы|а|у)?)?)?(?:\\s+час(?:а|ов)?)?(?:\\s+(утра|вечера|дня|ночи))?(?=\\s|$|[,;])")
    private val weekdays = mapOf("понедельник" to DayOfWeek.MONDAY, "вторник" to DayOfWeek.TUESDAY, "среду" to DayOfWeek.WEDNESDAY,
        "четверг" to DayOfWeek.THURSDAY, "пятницу" to DayOfWeek.FRIDAY, "субботу" to DayOfWeek.SATURDAY, "воскресенье" to DayOfWeek.SUNDAY)
    private val months = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")
    fun kind(text: String): ClockKind? {
        val s = normalize(text).lowercase(Locale.ROOT)
        if (Regex("^\\s*(?:что такое|как работает|почему|зачем|расскажи|объясни|разработ|добав.*функци|напиши.*код)").containsMatchIn(s)) return null
        if (!wordRegex("^\\s*(?:поставь|установи|создай|добавь|запусти|включи|начни|старт|засеки|отмени|отменить|удали|выключи|отключи|останови|приостанови|пауза|продолжи|возобнови|сбрось|обнули|круг|отсечка|сколько|покажи|прочитай|какие|мои|статус|что с|таймер\\p{L}*|будильник\\p{L}*|секундомер\\p{L}*|напоминани\\p{L}*|напомни)\\b").containsMatchIn(s)) return null
        val noun = Regex("таймер|будильник|секундомер|напомни").find(s)
        if (noun != null && wordRegex("\\bне\\b").containsMatchIn(s.substring(0, noun.range.first))) return null
        return when {
            wordRegex("\\bсекундомер\\p{L}*\\b").containsMatchIn(s) -> ClockKind.STOPWATCH
            wordRegex("\\bтаймер\\p{L}*\\b").containsMatchIn(s) -> ClockKind.TIMER
            wordRegex("\\bбудильник\\p{L}*\\b").containsMatchIn(s) -> ClockKind.ALARM
            wordRegex("^\\s*(?:(?:поставь|создай|добавь|установи)\\s+)?(?:напомни\\b|напоминани\\p{L}*\\b)|^\\s*(?:мои|покажи|прочитай|отмени|удали)\\s+напоминани").containsMatchIn(s) -> ClockKind.REMINDER
            else -> null
        }
    }
    private fun number(text: String): Double? = text.replace(',', '.').toDoubleOrNull() ?: numberNames[text]?.toDouble()
    fun duration(text: String): Long? {
        val s = text.lowercase(Locale.ROOT).trim()
        if (s in setOf("полчаса", "пол часа", "половину часа")) return 1800
        if (s in setOf("полтора часа", "полторы часы")) return 5400
        val parts = durationPart.findAll(s).toList()
        if (parts.isEmpty()) return null
        val rest = durationPart.replace(s, "").replace(wordRegex("\\bи\\b|[,;+]"), "").trim()
        if (rest.isNotEmpty()) return null
        var sum = 0.0
        for (p in parts) {
            val n = number(p.groupValues[1]) ?: return null
            sum += n * when { p.groupValues[2].startsWith("час") -> 3600; p.groupValues[2].startsWith("мин") -> 60; else -> 1 }
        }
        return sum.toLong().takeIf { sum == it.toDouble() && it in 1..MAX_TIMER_SECONDS }
    }
    fun parse(text: String, today: LocalDate = LocalDate.now()): ClockRequest? {
        val kind = kind(text) ?: return null
        val raw = normalize(text).trim().trimEnd('.', '!', '?')
        val s = raw.lowercase(Locale.ROOT).replace('ё', 'е')
        val cancel = wordRegex("^(?:отмени|отменить|удали|выключи|отключи|останови)\\b").containsMatchIn(s)
        val status = wordRegex("^(?:сколько|покажи|прочитай|какие|мои|статус|что с)\\b").containsMatchIn(s)
        if (kind == ClockKind.STOPWATCH) {
            val op = when {
                Regex("сброс|обнули").containsMatchIn(s) -> ClockOperation.RESET
                Regex("круг|отсечк").containsMatchIn(s) -> ClockOperation.LAP
                cancel || Regex("пауз|приостанов").containsMatchIn(s) -> ClockOperation.PAUSE
                Regex("продолж|возобнов").containsMatchIn(s) -> ClockOperation.RESUME
                status || s == "секундомер" -> ClockOperation.STATUS
                Regex("^(?:запусти|включи|начни|старт|поставь)").containsMatchIn(s) -> ClockOperation.START
                else -> ClockOperation.STATUS
            }
            return ClockRequest(kind, op)
        }
        if (cancel || status || s in setOf("таймеры", "будильники", "напоминания")) {
            val all = wordRegex("\\bвс[её]\\b").containsMatchIn(s)
            val label = raw.replace(Regex("^(?:отмени|отменить|удали|выключи|отключи|останови|покажи|прочитай|какие|мои|статус)\\s+", RegexOption.IGNORE_CASE), "")
                .replace(Regex("^(?:все\\s+)?(?:таймер\\p{L}*|будильник\\p{L}*|напоминани\\p{L}*)\\s*", RegexOption.IGNORE_CASE), "").trim()
            return ClockRequest(kind, if (cancel) ClockOperation.CANCEL else ClockOperation.STATUS, title = if (all) "*" else label)
        }
        if (Regex("ежеднев|кажд|по будням|по выходным").containsMatchIn(s))
            return ClockRequest(kind, ClockOperation.CREATE, problem = "Пока поддерживаю однократные будильники и напоминания. Назовите конкретное время.")
        if (kind == ClockKind.TIMER) {
            val tail = s.replace(Regex("^.*?таймер\\p{L}*(?:\\s+на)?\\s*"), "")
            val labelPart = Regex("\\s+(?:с названием|под названием|назови|название)\\s+(.+)$").find(tail)
            val amount = if (labelPart == null) tail else tail.substring(0, labelPart.range.first)
            val seconds = duration(amount)
            return ClockRequest(kind, ClockOperation.CREATE, seconds = seconds, title = labelPart?.groupValues?.get(1).orEmpty().take(160),
                problem = if (seconds == null) "На сколько поставить таймер? Например, на десять минут. Максимум — семь дней." else null)
        }
        var remainder = raw.replace(Regex("^(?:(?:поставь|создай|добавь|установи)\\s+)?(?:будильник|напомни(?:\\s+мне)?|напоминание)(?:\\s+в календарь)?\\s*", RegexOption.IGNORE_CASE), "")
        val relative = Regex("через\\s+((?:(?:$numeral)\\s*(?:час(?:а|ов)?|минут(?:а|ы|у)?|секунд(?:а|ы|у)?)(?:\\s+и)?\\s*)+|полчаса|полтора часа)", RegexOption.IGNORE_CASE).find(remainder)
        var moment: ClockMoment? = null
        var issue: String? = null
        if (relative != null) {
            val seconds = duration(relative.groupValues[1])
            if (seconds != null) moment = ClockMoment(delaySeconds = seconds) else issue = "Не разобрал длительность. Назовите часы, минуты или секунды."
            remainder = remainder.removeRange(relative.range)
        } else {
            var dayOffset: Int? = null
            val day = wordRegex("\\b(послезавтра|завтра|сегодня)\\b", RegexOption.IGNORE_CASE).find(remainder)
            if (day != null) { dayOffset = when(day.value.lowercase(Locale.ROOT)) { "завтра" -> 1; "послезавтра" -> 2; else -> 0 } }
            val weekday = wordRegex("\\bв\\s+(${weekdays.keys.joinToString("|")})\\b", RegexOption.IGNORE_CASE).find(remainder)
            val datePart = wordRegex("\\b(\\d{1,2})\\s+(${months.joinToString("|")})(?:\\s+(20\\d{2}))?\\b", RegexOption.IGNORE_CASE).find(remainder)
            val time = clockPart.findAll(remainder.lowercase(Locale.ROOT)).firstOrNull { t -> datePart == null || t.range.last < datePart.range.first || t.range.first > datePart.range.last }
            var date: LocalDate? = null
            if (datePart != null) {
                date = runCatching { LocalDate.of(datePart.groupValues[3].toIntOrNull() ?: today.year, months.indexOf(datePart.groupValues[2].lowercase(Locale.ROOT)) + 1, datePart.groupValues[1].toInt()) }.getOrNull()
                if (date == null) issue = "Такой даты нет. Назовите дату и время ещё раз."
            }
            if (time != null) {
                var h = number(time.groupValues[1])?.toInt()
                val m = number(time.groupValues[2].ifBlank { time.groupValues[3] }.ifBlank { "0" })?.toInt()
                if(time.groupValues[4] in setOf("вечера","дня") && h in 1..11) h=h!!+12
                if(time.groupValues[4]=="ночи" && h==12) h=0
                if (h !in 0..23 || m !in 0..59) issue = "Назовите время от нуля до двадцати трёх часов и минуты от нуля до пятидесяти девяти."
                else moment = ClockMoment(hour = h, minute = m!!, dayOffset = dayOffset, date = date, weekday = weekday?.let { weekdays[it.groupValues[1].lowercase(Locale.ROOT)] })
            }
            val removals = listOfNotNull(time?.range, day?.range, weekday?.range, datePart?.range)
            remainder = remainder.filterIndexed { index, _ -> removals.none { index in it } }
        }
        val title = remainder.replace(Regex("^\\s*(?:на|о том,? что|что|о|про|с названием)\\s+", RegexOption.IGNORE_CASE), "").trim(' ', ',', ':', ';').replace(Regex("\\s+"), " ").take(160)
        if (moment == null && issue == null) issue = "На какое время? Например, завтра в восемь тридцать."
        if (kind == ClockKind.REMINDER && title.isBlank() && issue == null) issue = "Что напомнить? Назовите текст напоминания."
        return ClockRequest(kind, ClockOperation.CREATE, moment = moment, title = title, problem = issue)
    }
    fun durationSpeech(seconds: Long): String {
        fun unit(n: Long, one: String, few: String, many: String): String = "$n " + if (n % 100 in 11..14) many else when(n % 10) { 1L -> one; 2L, 3L, 4L -> few; else -> many }
        val parts = mutableListOf<String>()
        if (seconds / 3600 > 0) parts += unit(seconds / 3600, "час", "часа", "часов")
        if (seconds / 60 % 60 > 0) parts += unit(seconds / 60 % 60, "минута", "минуты", "минут")
        if (seconds % 60 > 0 || parts.isEmpty()) parts += unit(seconds % 60, "секунда", "секунды", "секунд")
        return parts.joinToString(" ")
    }
}
