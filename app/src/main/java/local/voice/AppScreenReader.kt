package local.voice

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.util.concurrent.Executors

/** Local screen-reading module shared by apps; pixels never leave RAM or go to a server. */
internal object AppScreenReader {
    data class Word(val text: String, val confidence: Float, val left: Int, val top: Int, val right: Int, val bottom: Int)
    data class Observation(val app: String, val accessible: String, val visual: String, val words: List<Word>, val capturedAt: Long) {
        fun speech(): String = buildString {
            append("Текст приложения. "); append(accessible.take(1000))
            if (visual.isNotBlank()) { append(". Текст со снимка, возможны ошибки распознавания. "); append(visual.take(1400)) }
        }
    }
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    internal fun recognize(context: Context, bitmap: Bitmap): Pair<String, List<Word>> {
        val path = File(context.filesDir, "ocr")
        val data = File(path, "tessdata").apply { mkdirs() }
        for (language in listOf("rus", "eng")) {
            val file = File(data, "$language.traineddata")
            if (!file.isFile) context.assets.open("ocr/tessdata/${file.name}").use { input -> file.outputStream().use(input::copyTo) }
        }
        val tess = TessBaseAPI()
        try {
            check(tess.init(path.absolutePath, "rus+eng", TessBaseAPI.OEM_LSTM_ONLY))
            tess.pageSegMode = TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT
            fun pass(threshold: String): List<Word> {
                tess.setVariable("thresholding_method", threshold)
                tess.setImage(bitmap)
                tess.utF8Text // Run recognition before requesting its iterator.
                val words = mutableListOf<Word>()
                val iterator = tess.resultIterator
                if (iterator != null) {
                    try {
                        iterator.begin()
                        do {
                            val word = iterator.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD).orEmpty().trim()
                            val box = iterator.getBoundingBox(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                            if (word.isNotBlank() && box.size == 4 && words.size < 400) words += Word(word,
                                iterator.confidence(TessBaseAPI.PageIteratorLevel.RIL_WORD), box[0], box[1], box[2], box[3])
                        } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_WORD) && words.size < 400)
                    } finally { iterator.delete() }
                }
                return words
            }
            val merged = pass("0").toMutableList()
            tess.clear()
            // Adaptive thresholding adds digits on colored rows while preserving
            // normal text (e.g. English words) from the standard pass.
            for (word in pass("2")) {
                fun overlaps(other: Word): Boolean {
                    val width = minOf(word.right, other.right) - maxOf(word.left, other.left)
                    val height = minOf(word.bottom, other.bottom) - maxOf(word.top, other.top)
                    val area = minOf((word.right - word.left) * (word.bottom - word.top), (other.right - other.left) * (other.bottom - other.top))
                    return width > 0 && height > 0 && area > 0 && width.toLong() * height * 2 > area
                }
                if (merged.none { overlaps(it) && it.confidence >= 50 }) {
                    merged.removeAll(::overlaps); merged += word
                }
            }
            val words = merged.sortedWith(compareBy<Word> { it.top }.thenBy { it.left }).take(400)
            val rows = mutableListOf<MutableList<Word>>()
            for (word in words) {
                val row = rows.lastOrNull()?.takeIf { values ->
                    val first = values.first()
                    kotlin.math.abs((first.top + first.bottom) - (word.top + word.bottom)) <= maxOf(first.bottom - first.top, word.bottom - word.top)
                } ?: mutableListOf<Word>().also(rows::add)
                row += word
            }
            return rows.joinToString("\n") { row -> row.sortedBy { it.left }.joinToString(" ") { it.text } }.take(5000) to words
        } finally { tess.recycle() }
    }
    fun read(context: Context, expected: PhoneAccessibility.ForegroundSnapshot, allowed: () -> Boolean,
        originalDone: (Observation?, String?) -> Unit) {
        val trace = InteractionJournal.current?.begin("screen_read", InteractionJournal.data("frame" to expected.toString()))
        val done: (Observation?, String?) -> Unit = { observation, error ->
            trace?.result(InteractionJournal.data("app" to observation?.app, "accessible" to observation?.accessible,
                "visual" to observation?.visual, "captured_at" to observation?.capturedAt, "error" to error))
            originalDone(observation, error)
        }
        val ui = PhoneAccessibility.connected
        if (ui == null || !allowed() || ui.foregroundSnapshot() != expected) { done(null, "Приложение недоступно или экран изменился."); return }
        val accessible = ui.textTree(expected.app)?.all()?.map { it.text.ifBlank { it.description }.trim() }
            ?.filter(String::isNotBlank)?.distinct()?.joinToString(". ").orEmpty().take(4000)
        val at = System.currentTimeMillis()
        ui.screenshot(expected) { bitmap, error ->
            if (bitmap == null) {
                if (allowed()) {
                    val fallback = accessible.takeIf { it.isNotBlank() && ui.foregroundSnapshot() == expected }
                        ?.let { Observation(expected.app, it, "", emptyList(), at) }
                    done(fallback, error)
                }
                return@screenshot
            }
            executor.execute {
                val result = runCatching { recognize(context, bitmap) }
                bitmap.recycle()
                main.post {
                    if (!allowed()) return@post
                    val now = PhoneAccessibility.connected?.foregroundSnapshot()
                    // OCR reads a frozen image. Dynamic text inside the same window
                    // can advance during recognition; it does not change that image.
                    if (now?.app != expected.app || now.window != expected.window) { done(null, "Приложение изменилось во время чтения. Повторите запрос."); return@post }
                    result.onSuccess { (text, words) -> done(Observation(expected.app, accessible, text, words, at), null) }
                        .onFailure { done(null, "Не удалось распознать изображение экрана.") }
                }
            }
        }
    }
}
