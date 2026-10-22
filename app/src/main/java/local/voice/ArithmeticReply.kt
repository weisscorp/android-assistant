package local.voice

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/** Exact, bounded two-operand arithmetic. No model, eval, missing-operand guesses or text rewriting. */
internal object ArithmeticReply {
    private val numbers by lazy { (0..999).associate { RussianNumbers.expand(it.toString()) to it.toBigDecimal() } }
    private val operation = Regex("^(.+?)\\s*(умножить на|помножить на|разделить на|поделить на|плюс|минус|[+*/×÷−-])\\s*(.+)$")
    fun answer(raw: String): String? {
        if (raw.length > 180) return null
        val text = raw.lowercase(Locale.ROOT).trim().trimEnd('?', '!', '.').replace(Regex("\\s+"), " ")
            .replace(Regex("^(?:сколько (?:будет|получится)|чему равно|посчитай|вычисли)\\s+"), "")
        val match = operation.matchEntire(text) ?: return null
        fun number(value: String): BigDecimal? {
            val word = value.trim()
            numbers[word]?.let { return it }
            if (word.startsWith("минус ")) return numbers[word.removePrefix("минус ")]?.negate()
            if (!word.matches(Regex("[+−-]?\\d{1,15}(?:[.,]\\d{1,8})?"))) return null
            return word.replace('−', '-').replace(',', '.').toBigDecimalOrNull()
        }
        val a = number(match.groupValues[1]) ?: return null
        val b = number(match.groupValues[3]) ?: return null
        val value = when (match.groupValues[2]) {
            "плюс", "+" -> a + b
            "минус", "-", "−" -> a - b
            "умножить на", "помножить на", "*", "×" -> a * b
            else -> {
                if (b.signum() == 0) return "На ноль делить нельзя."
                val quotient = a.divide(b, 8, RoundingMode.HALF_UP).stripTrailingZeros()
                if ((quotient * b).compareTo(a) != 0)
                    return "Приблизительно ${quotient.toPlainString().replace('.', ',')}."
                quotient
            }
        }
        return "${value.stripTrailingZeros().toPlainString().replace('.', ',')}."
    }
}
