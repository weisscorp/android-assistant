package local.voice

/** Expand decimals before either TTS frontend can split them into separate integers. */
internal object SpokenDecimals {
    private val decimal = Regex("(?<![\\p{L}\\p{N}_.,:/])([−-]?)([0-9]{1,12})[.,]([0-9]{1,6})(?![\\p{L}\\p{N}_:/]|[.,][0-9])")
    private val protected = Regex("(?:https?://|www\\.)\\S+|[\\w.+-]+@[\\w.-]+|(?:[vV]|верси[яию]|version)\\s+[0-9.,]+", RegexOption.IGNORE_CASE)
    private val units = Regex("^\\s*(градус(?:ов|а)?|метр(?:ов|а)?|миллиметр(?:ов|а)?|процент(?:ов|а)?|°\\s*[cс]?|%)(?!\\p{L})", RegexOption.IGNORE_CASE)
    private val denominators = listOf("десятая", "сотая", "тысячная", "десятитысячная", "стотысячная", "миллионная")

    fun text(input: String): String {
        val excluded = protected.findAll(input).map { it.range }.toList()
        val out = StringBuilder()
        var cursor = 0
        for (match in decimal.findAll(input)) {
            if (match.range.first < cursor || excluded.any { match.range.first in it }) continue
            out.append(input, cursor, match.range.first)
            val fraction = match.groupValues[3]
            val value = fraction.toInt()
            val words = RussianNumbers.expand(value.toString()).replace(Regex("один$"), "одна").replace(Regex("два$"), "две")
            val singular = value % 10 == 1 && value % 100 != 11
            val denominator = denominators[fraction.length - 1].let { if (singular) it else it.removeSuffix("ая") + "ых" }
            if (match.groupValues[1].isNotEmpty()) out.append("минус ")
            out.append(RussianNumbers.expand(match.groupValues[2])).append(" и ").append(words).append(' ').append(denominator)
            cursor = match.range.last + 1
            units.find(input.substring(cursor))?.let { unit ->
                val label = unit.groupValues[1].lowercase()
                out.append(' ').append(when {
                    label.startsWith("°") -> "градуса" + if (label.any { it == 'c' || it == 'с' }) " Цельсия" else ""
                    label == "%" || label.startsWith("процент") -> "процента"
                    label.startsWith("миллиметр") -> "миллиметра"
                    label.startsWith("метр") -> "метра"
                    else -> "градуса"
                })
                cursor += unit.value.length
            }
        }
        return out.append(input, cursor, input.length).toString()
    }
}
