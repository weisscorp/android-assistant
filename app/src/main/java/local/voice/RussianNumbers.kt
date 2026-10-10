package local.voice

/** Integer expansion for the character-only Silero frontend. Never drops digits. */
internal object RussianNumbers {
    private val units = listOf("", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять")
    private val teens = listOf("десять", "одиннадцать", "двенадцать", "тринадцать", "четырнадцать", "пятнадцать", "шестнадцать", "семнадцать", "восемнадцать", "девятнадцать")
    private val tens = listOf("", "", "двадцать", "тридцать", "сорок", "пятьдесят", "шестьдесят", "семьдесят", "восемьдесят", "девяносто")
    private val hundreds = listOf("", "сто", "двести", "триста", "четыреста", "пятьсот", "шестьсот", "семьсот", "восемьсот", "девятьсот")
    private val groups = listOf(listOf("", "", ""), listOf("тысяча", "тысячи", "тысяч"), listOf("миллион", "миллиона", "миллионов"), listOf("миллиард", "миллиарда", "миллиардов"), listOf("триллион", "триллиона", "триллионов"), listOf("квадриллион", "квадриллиона", "квадриллионов"), listOf("квинтиллион", "квинтиллиона", "квинтиллионов"))
    fun expand(digits: String): String {
        // Leading zeroes are significant in codes/telephone numbers.
        if (digits.length > 1 && digits.startsWith('0') || digits.toLongOrNull() == null)
            return digits.map { if (it == '0') "ноль" else units[it.digitToInt()] }.joinToString(" ")
        var number = digits.toLong()
        if (number == 0L) return "ноль"
        val result = mutableListOf<String>(); var group = 0
        while (number > 0) {
            val n = (number % 1000).toInt()
            if (n > 0) {
                val words = mutableListOf<String>()
                if (n >= 100) words.add(hundreds[n / 100])
                val low = n % 100
                if (low in 10..19) words.add(teens[low - 10])
                else {
                    if (low >= 20) words.add(tens[low / 10])
                    val u = low % 10
                    if (u > 0) words.add(if (group == 1 && u == 1) "одна" else if (group == 1 && u == 2) "две" else units[u])
                }
                if (group > 0) words.add(groups[group][if (low in 11..14) 2 else when (low % 10) { 1 -> 0; 2, 3, 4 -> 1; else -> 2 }])
                result.add(0, words.joinToString(" "))
            }
            number /= 1000; group++
        }
        return result.joinToString(" ")
    }
}
