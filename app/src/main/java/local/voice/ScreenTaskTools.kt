package local.voice

import java.util.Locale

/** Native grammar capabilities come only from the human request; screen text never enters here. */
internal object ScreenTaskTools {
    const val MODE = 16
    const val OPEN = 1
    const val TAP = 2
    const val WRITE = 4
    const val SCROLL = 8
    fun mask(request: String, allowOpen: Boolean): Int {
        val words = request.lowercase(Locale.ROOT)
        val writing = Regex("напиш|введ|впиш|набери|запиш|ответь").find(words)
        val replacement = Regex("^замени\\s+.+?\\s+на\\s+").find(words)
        val literalWriting = writing != null || replacement != null
        // A dictated suffix is data, including words like «нажми» or «прокрути».
        val boundary = listOfNotNull(writing?.range?.first, replacement?.let { it.range.last + 1 }).minOrNull()
        val instructions = boundary?.let(words::take) ?: words
        val opening = Regex("открой|запусти|зайди|перейди").containsMatchIn(instructions)
        val taps = Regex("нажм|нажат|кнопк|выбер|найди|найти").containsMatchIn(instructions)
        val scrolls = Regex("прокрут|листай|найди|найти").containsMatchIn(instructions)
        // A goal can require several UI primitives without naming them («найди настройку…»).
        // Keep literal dictation constrained; ordinary tasks may use screen tools, each effect still confirmed.
        val readOnly = words.trim().trimEnd('.', '!', '?') in setOf("прочитай экран", "что на экране", "кнопки на экране")
        val search = Regex("найди|найти|отыщи").containsMatchIn(instructions)
        val general = !literalWriting && (!taps && !scrolls || search) && !readOnly
        return MODE or (if (allowOpen && (opening || general)) OPEN else 0) or (if (taps || general) TAP else 0) or
            (if (literalWriting || general) WRITE else 0) or (if (scrolls || general) SCROLL else 0)
    }
}
