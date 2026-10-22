package local.voice

import com.ibm.icu.text.Transliterator
import java.util.Locale

/** Cheap spelling fallbacks generated once, not a translation or an LLM request. */
internal object AppSpeechNames {
    private val latin by lazy { Transliterator.getInstance("Any-Latin; Latin-ASCII") }
    private val cyrillic by lazy { Transliterator.getInstance("Latin-Cyrillic") }
    private val letters = mapOf('a' to "а", 'b' to "б", 'c' to "к", 'd' to "д", 'e' to "е", 'f' to "ф", 'g' to "г", 'h' to "х",
        'i' to "и", 'j' to "дж", 'k' to "к", 'l' to "л", 'm' to "м", 'n' to "н", 'o' to "о", 'p' to "п", 'q' to "к",
        'r' to "р", 's' to "с", 't' to "т", 'u' to "у", 'v' to "в", 'w' to "в", 'x' to "кс", 'y' to "й", 'z' to "з")
    private val pairs = linkedMapOf("tion" to "шен", "tch" to "ч", "sch" to "ш", "sh" to "ш", "ch" to "ч", "ph" to "ф", "th" to "т",
        "ck" to "к", "qu" to "кв", "ee" to "и", "oo" to "у", "ay" to "эй", "ai" to "ай", "oy" to "ой")
    @Synchronized fun variants(label: String): List<String> {
        if (label.isBlank()) return emptyList()
        if (label.any { it in 'А'..'я' || it in "ёЁ" } && label.none { it in 'A'..'Z' || it in 'a'..'z' }) return russianForms(label)
        val ascii = runCatching { latin.transliterate(label) }.getOrDefault(label).lowercase(Locale.ROOT)
        val spoken = buildString {
            var i = 0
            while (i < ascii.length) {
                val pair = pairs.entries.firstOrNull { ascii.startsWith(it.key, i) }
                if (pair != null) { append(pair.value); i += pair.key.length }
                else { append(if (ascii[i] == 'c' && ascii.getOrNull(i + 1) in listOf('e', 'i', 'y')) "с" else letters[ascii[i]] ?: ascii[i].toString()); i++ }
            }
        }
        val formal = runCatching { cyrillic.transliterate(ascii) }.getOrDefault(spoken)
        return listOf(label, spoken, formal).flatMap(::russianForms).map(String::trim).filter { it.isNotBlank() && it.length <= 120 }.distinct()
    }
    private fun russianForms(label: String): List<String> {
        val accusative = label.lowercase(Locale.ROOT).split(' ').joinToString(" ") { word ->
            if (!word.matches(Regex("[а-яё]+"))) word else when {
                word.endsWith("ая") -> word.dropLast(2) + "ую"
                word.endsWith("яя") -> word.dropLast(2) + "юю"
                word.endsWith("а") -> word.dropLast(1) + "у"
                word.endsWith("я") -> word.dropLast(1) + "ю"
                else -> word
            }
        }
        return listOf(label, accusative).distinct()
    }
    private val nonLetters = Regex("[^\\p{L}\\p{N}]")
    private val repeatedLetters = Regex("(.)\\1+")
    private val russianVowels = Regex("[аеёиоуыэюяьъ]")
    /** A suggestion score only. Never use a fuzzy name as permission to launch. */
    fun suggestionScore(query: String, label: String): Double {
        fun clean(s: String) = s.lowercase(Locale.ROOT).replace('ё', 'е').replace('й', 'и')
            .replace(nonLetters, "").replace(repeatedLetters, "$1")
        val a = clean(query); val b = clean(label)
        if (minOf(a.length, b.length) < 6 || maxOf(a.length, b.length) > 40) return 0.0
        if (a.any { it in 'а'..'я' } != b.any { it in 'а'..'я' }) return 0.0
        fun distance(x: String, y: String): Int {
            var previous = IntArray(y.length + 1) { it }
            for (i in x.indices) {
                val current = IntArray(y.length + 1); current[0] = i + 1
                for (j in y.indices) current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1,
                    previous[j] + if (x[i] == y[j]) 0 else 1)
                previous = current
            }
            return previous.last()
        }
        val score = 1.0 - distance(a, b).toDouble() / maxOf(a.length, b.length)
        val x = a.replace(russianVowels, ""); val y = b.replace(russianVowels, "")
        if (score < .5 || minOf(x.length, y.length) < 3 || distance(x, y) > 2) return 0.0
        return score
    }
    fun opening(name: String): String = if (name.any { it in 'А'..'я' || it in "ёЁ" }) russianForms(name).last() else name
    fun keys(text: String): Set<String> {
        val words = text.split(Regex("[^\\p{L}\\p{N}]+")).filter(String::isNotBlank).take(80)
        return buildSet {
            for (start in words.indices) for (end in start + 1..minOf(words.size, start + 8)) {
                add(AppNames.canonical(words.subList(start, end).joinToString(" ")))
                if (size >= 450) return@buildSet
            }
        }
    }
}
