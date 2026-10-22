package local.voice

import java.time.LocalDate

/** Resolve calendar literals before decimal expansion. Display text and commands stay unchanged. */
internal object SpokenCalendar {
    private val months = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")
    private val genitive = listOf("", "первого", "второго", "третьего", "четвёртого", "пятого", "шестого", "седьмого", "восьмого", "девятого", "десятого", "одиннадцатого", "двенадцатого", "тринадцатого", "четырнадцатого", "пятнадцатого", "шестнадцатого", "семнадцатого", "восемнадцатого", "девятнадцатого")
    private val neutral = listOf("", "первое", "второе", "третье", "четвёртое", "пятое", "шестое", "седьмое", "восьмое", "девятое", "десятое", "одиннадцатое", "двенадцатое", "тринадцатое", "четырнадцатое", "пятнадцатое", "шестнадцатое", "семнадцатое", "восемнадцатое", "девятнадцатое")
    private val edges = "(?<![\\p{L}\\p{N}_./:-])"
    private val end = "(?![\\p{L}\\p{N}_/:]|\\.[0-9])"
    private val numericDate = Regex(edges + "([0-3]?[0-9])\\.([01]?[0-9])(?:\\.((?:19|20)[0-9]{2}))?" + end)
    private val namedDate = Regex(edges + "([0-3]?[0-9])\\s+(" + months.joinToString("|") + ")(?:\\s+((?:19|20)[0-9]{2})(?:\\s+года)?)?" + end, RegexOption.IGNORE_CASE)
    private val time = Regex(edges + "([0-2]?[0-9])([:.])([0-5][0-9])(?::([0-5][0-9]))?" + end)
    private val protected = Regex("(?:https?://|www\\.)\\S+|[\\w.+-]+@[\\w.-]+|(?:[vV]|верси[яию]|version)\\s+[0-9.,]+|(?<![0-9])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![0-9])", RegexOption.IGNORE_CASE)
    private data class Edit(val range: IntRange, val text: String)
    private fun ordinal(day: Int, nominative: Boolean): String = when {
        day < 20 -> (if (nominative) neutral else genitive)[day]
        day == 20 -> if (nominative) "двадцатое" else "двадцатого"
        day == 30 -> if (nominative) "тридцатое" else "тридцатого"
        else -> RussianNumbers.expand((day/10*10).toString()) + " " + (if (nominative) neutral else genitive)[day%10]
    }
    private fun year(value: Int): String {
        if (value == 2000) return "двухтысячного"
        if (value == 1900) return "тысяча девятисотого"
        val last = value%100
        val tail = if (last < 40) ordinal(last,false) else {
            val tens = mapOf(40 to "сорокового", 50 to "пятидесятого", 60 to "шестидесятого", 70 to "семидесятого", 80 to "восьмидесятого", 90 to "девяностого")
            if (last%10 == 0) tens.getValue(last) else RussianNumbers.expand((last/10*10).toString()) + " " + genitive[last%10]
        }
        return RussianNumbers.expand((value/100*100).toString()).removePrefix("одна ") + " " + tail
    }
    private fun count(n: Int, labels: List<String>, feminine: Boolean = false, accusative: Boolean = false): String {
        val words = RussianNumbers.expand(n.toString()).let { if (!feminine || n%100 in 11..14) it else when(n%10) {
            1 -> it.removeSuffix("один") + if (accusative) "одну" else "одна"
            2 -> it.removeSuffix("два") + "две"
            else -> it
        } }
        val label = labels[if (n%100 in 11..14) 2 else when(n%10) { 1 -> 0; 2,3,4 -> 1; else -> 2 }]
        return "$words $label"
    }
    fun text(input: String): String {
        val excluded = protected.findAll(input).map { it.range }.toList()
        val edits = mutableListOf<Edit>()
        fun allowed(range: IntRange) = excluded.none { range.first <= it.last && it.first <= range.last } &&
            edits.none { range.first <= it.range.last && it.range.first <= range.last }
        fun before(at: Int) = input.substring(maxOf(0,at-65),at).lowercase()
        fun date(match: MatchResult, named: Boolean) {
            if (!allowed(match.range)) return
            val d = match.groupValues[1].toInt()
            val m = if (named) months.indexOf(match.groupValues[2].lowercase())+1 else match.groupValues[2].toInt()
            val y = match.groupValues[3].toIntOrNull()
            if (runCatching { LocalDate.of(y ?: 2000,m,d) }.isFailure) return
            val next = input.substring(match.range.last+1)
            // Two dotted fields alone are ambiguous: keep ordinary decimal numbers as fractions.
            if (!named && y == null && !Regex("[0-2]?[0-9]:[0-5][0-9]\\s*$").containsMatchIn(before(match.range.first)) && !Regex("^\\s+[0-2]?[0-9]:[0-5][0-9]").containsMatchIn(next) &&
                !Regex("(?:дата|сегодня|завтра|будильник|напоминание|встреча|назначен[ао]?)\\s*(?:на|—|:)?\\s*$").containsMatchIn(before(match.range.first))) return
            val nominative = Regex("(?:^|\\s)(?:сегодня|завтра|вчера|дата|на)\\s*(?:—|:)?\\s*$").containsMatchIn(before(match.range.first))
            edits += Edit(match.range, ordinal(d,nominative)+" "+months[m-1]+if (y != null) " "+year(y)+" года" else "")
        }
        namedDate.findAll(input).forEach { date(it,true) }
        numericDate.findAll(input).forEach { date(it,false) }
        for (match in time.findAll(input)) {
            if (!allowed(match.range)) continue
            val hour = match.groupValues[1].toInt()
            if (hour > 23) continue
            val prefix = before(match.range.first)
            val next = input.substring(match.range.last+1)
            if (match.groupValues[2] == ".") {
                if (Regex("^\\s*(?:%|процент|градус|метр|рубл|килограмм)",RegexOption.IGNORE_CASE).containsMatchIn(next)) continue
                if (Regex("(?:умнож|раздел|подел|увелич|уменьш)[^.!?]*$" ).containsMatchIn(prefix)) continue
                val dateNext = edits.any { it.range.first > match.range.last && input.substring(match.range.last+1,it.range.first).isBlank() }
                if (!dateNext && !Regex("(?:^|\\s)(?:в|на|будильник|время|сейчас|ровно)\\s*$").containsMatchIn(prefix)) continue
            }
            val minute = match.groupValues[3].toInt()
            val second = match.groupValues[4].toIntOrNull()
            val acc = Regex("(?:^|\\s)(?:в|на)\\s*$").containsMatchIn(prefix)
            var words = count(hour,listOf("час","часа","часов"))
            if (minute != 0 || second != null) words += " " + count(minute,listOf(if(acc) "минуту" else "минута","минуты","минут"),true,acc)
            if (second != null) words += " " + count(second,listOf(if(acc) "секунду" else "секунда","секунды","секунд"),true,acc)
            edits += Edit(match.range,words)
        }
        val out = StringBuilder(); var cursor = 0
        for (edit in edits.sortedBy { it.range.first }) {
            out.append(input,cursor,edit.range.first).append(edit.text); cursor = edit.range.last+1
        }
        return out.append(input,cursor,input.length).toString()
    }
}
