package local.voice

import android.content.Context
import com.ibm.icu.text.RuleBasedNumberFormat
import org.json.JSONObject
import org.pytorch.IValue
import org.pytorch.Module
import org.pytorch.Tensor
import java.io.File
import java.util.Locale
import kotlin.math.exp

/** Native TorchScript inference. The ngram accentor and contextual homograph model
 * are the same weights used by Silero's public Python apply_tts frontend. */
internal class SileroVoice(context: Context, private val speaker: String) {
    private val directory = AssetModels.unpack(context, "voice-silero.zip", "voice-silero", "silero-v5.5-android-v2")
    private var acoustic: Module? = null
    private var accent: Module? = null
    private var homo: Module? = null
    private val data = JSONObject(File(directory, "text.json").readText())
    private val intonation = JSONObject(File(directory, "intonation.json").readText())
    private val exceptions = data.getJSONObject("exceptions")
    private val homographs = data.getJSONObject("homographs")
    private val symbols = data.getJSONObject("symbols")
    private val vocab by lazy { File(directory, "vocab.txt").readLines().mapIndexed { i, word -> word to i.toLong() }.toMap() }
    private val vowels = "аоуыэиеяёю"
    private val prefs = context.getSharedPreferences("settings", 0)
    init {
        try {
            org.pytorch.PyTorchAndroid.setNumThreads(2)
            acoustic = Module.load(File(directory, "silero.pt").absolutePath)
            accent = Module.load(File(directory, "accent.pt").absolutePath)
        } catch (e: Exception) { close(); throw e }
    }
    private fun tensor(values: LongArray, vararg shape: Long) = IValue.from(Tensor.fromBlob(values, shape))
    private fun confidence(values: FloatArray, offset: Int, size: Int, best: Int): Double {
        val max = values[offset + best].toDouble()
        return 1.0 / (0 until size).sumOf { exp(values[offset + it] - max) }
    }
    /** WordPiece is case-sensitive, like the packaged SimpleBertTokenizer. */
    internal fun tokenize(text: String): LongArray {
        val pieces = mutableListOf(2L)
        val basic = text.trim().split(Regex("\\s+")).flatMap { word ->
            if (word in listOf("[HOMO]", "[/HOMO]")) listOf(word)
            else Regex("[\\p{L}\\p{N}]+|[^\\s]").findAll(word).map { it.value }.toList()
        }
        for (word in basic) {
            if (word.length > 100) { pieces.add(1L); continue }
            val tokens = mutableListOf<Long>()
            var start = 0
            while (start < word.length) {
                var end = word.length
                var id: Long? = null
                while (end > start) {
                    id = vocab[(if (start > 0) "##" else "") + word.substring(start, end)]
                    if (id != null) break
                    end--
                }
                if (id == null) { tokens.clear(); tokens.add(1L); break }
                tokens.add(id); start = end
            }
            pieces.addAll(tokens)
        }
        pieces.add(3L)
        return pieces.toLongArray()
    }
    private fun resolveHomographs(sentence: String): String {
        // Resolve against the original whole sentence; replacing earlier words must
        // not change the context seen by later predictions.
        val matches = Regex("[а-яё+]+", RegexOption.IGNORE_CASE).findAll(sentence).toList()
        val replacements = mutableMapOf<Int, String>()
        val ambiguous = matches.filter { homographs.has(it.value.lowercase(Locale.ROOT)) }
        if (ambiguous.isEmpty()) return sentence
        val batch = ambiguous.map { match ->
            val tagged = sentence.substring(0, match.range.first) + " [HOMO] " + match.value + " [/HOMO] " + sentence.substring(match.range.last + 1)
            tokenize(tagged)
        }
        val length = batch.maxOf { it.size }
        require(length <= 2048) { "Предложение слишком длинное." }
        val padded = LongArray(batch.size * length)
        val starts = LongArray(batch.size); val ends = LongArray(batch.size)
        batch.forEachIndexed { i, ids ->
            ids.copyInto(padded, i * length)
            starts[i] = ids.indexOf(83828L).toLong(); ends[i] = ids.indexOf(83829L).toLong()
            check(starts[i] >= 0 && ends[i] > starts[i])
        }
        val model = homo ?: Module.load(File(directory, "homo.pt").absolutePath).also { homo = it }
        val logits = model.forward(tensor(padded, batch.size.toLong(), length.toLong()),
            tensor(starts, batch.size.toLong()), tensor(ends, batch.size.toLong())).toTensor().dataAsFloatArray
        for ((i, match) in ambiguous.withIndex()) {
            val logit = logits[i]; check(logit.isFinite())
            val variants = homographs.getJSONArray(match.value.lowercase(Locale.ROOT))
            val sorted = (0 until variants.length()).map(variants::getString).sorted()
            replacements[match.range.first] = sorted[if (logit > 0f) 1 else 0]
        }
        val result = StringBuilder(sentence)
        for (match in matches.asReversed()) replacements[match.range.first]?.let { result.replace(match.range.first, match.range.last + 1, it) }
        return result.toString()
    }
    internal fun accentuate(sentence: String): String {
        val resolved = resolveHomographs(sentence)
        // Keep separators as tokens, including punctuation-only/empty inputs, as
        // AccentorNgram does. This preserves each model output's word index.
        val tokens = mutableListOf<String>()
        val delimiter = Regex("[\\s.,!?;:<>=()/\\\\]+")
        var cursor = 0
        fun addWord(word: String) {
            val parts = word.split('-')
            parts.forEachIndexed { i, part -> tokens.add(part + if (i < parts.lastIndex) "-" else "") }
        }
        for (match in delimiter.findAll(resolved)) {
            addWord(resolved.substring(cursor, match.range.first)); tokens.add(match.value)
            cursor = match.range.last + 1
        }
        addWord(resolved.substring(cursor))
        val clean = tokens.map { it.lowercase(Locale.ROOT).replace(Regex("[^а-яё]"), "") }
        val prediction = accent!!.forward(IValue.listFrom(*clean.map(IValue::from).toTypedArray())).toTuple().map { it.toTensor() }
        val stress = prediction[0].dataAsFloatArray; val yo = prediction[1].dataAsFloatArray
        val sn = prediction[0].shape()[1].toInt(); val yn = prediction[1].shape()[1].toInt()
        return tokens.mapIndexed { index, raw ->
            val word = clean[index]
            if (word.isEmpty() || (word == "то" && index > 0 && tokens[index - 1].endsWith('-'))) return@mapIndexed raw
            if ('+' in raw && 'ё' in raw) return@mapIndexed raw
            val positions = raw.indices.filter { raw[it].lowercaseChar() in vowels }
            if (positions.isEmpty()) return@mapIndexed raw
            val explicitStress = raw.indexOf('+')
            if ('ё' in raw && explicitStress < 0) return@mapIndexed raw.replace("ё", "+ё")
            val result = StringBuilder(raw)
            var stressPosition: Int? = null
            var yoPosition: Int? = null
            if (exceptions.has(word)) {
                val exc = exceptions.getJSONArray(word)
                stressPosition = exc.getInt(0).takeIf { it in raw.indices }
                yoPosition = exc.getInt(1).takeIf { it in raw.indices }
                if (explicitStress >= 0) { if (yoPosition == explicitStress) result.setCharAt(explicitStress + 1, 'ё'); return@mapIndexed result.toString() }
            } else {
                val sb = (0 until sn).maxBy { stress[index * sn + it] }
                val yb = (0 until yn).maxBy { yo[index * yn + it] }
                if (confidence(stress, index * sn, sn, sb) > 0.5) stressPosition = positions.getOrNull(sb)
                val yes = raw.indices.filter { raw[it] == 'е' }
                if (yb > 0 && confidence(yo, index * yn, yn, yb) > 0.5) yoPosition = yes.getOrNull(yb - 1)
                if (explicitStress >= 0) stressPosition = explicitStress + 1
            }
            if (yoPosition != null && yoPosition == stressPosition) result.setCharAt(yoPosition, 'ё')
            if (positions.size == 1) stressPosition = positions[0]
            if (explicitStress < 0 && stressPosition != null) result.insert(stressPosition, '+')
            result.toString()
        }.joinToString("")
    }
    internal fun normalized(text: String): String {
        var input = text.lowercase(Locale.ROOT).replace('—', '–').replace('‑', '-')
        input = Regex("\\d+").replace(input) { match ->
            RussianNumbers.expand(match.value)
        }
        val names = mapOf("telegram" to "телеграм", "bluetooth" to "блютус", "android" to "андроид", "qwen" to "кьювен", "silero" to "силеро", "baya" to "байя", "wifi" to "вай фай", "wi-fi" to "вай фай")
        input = Regex("[a-z]+(?:-[a-z]+)*").replace(input) { names[it.value] ?: it.value.map { c ->
            mapOf('a' to "эй", 'b' to "би", 'c' to "си", 'd' to "ди", 'e' to "и", 'f' to "эф", 'g' to "джи", 'h' to "эйч", 'i' to "ай", 'j' to "джей", 'k' to "кей", 'l' to "эл", 'm' to "эм", 'n' to "эн", 'o' to "оу", 'p' to "пи", 'q' to "кью", 'r' to "ар", 's' to "эс", 't' to "ти", 'u' to "ю", 'v' to "ви", 'w' to "дабл ю", 'x' to "экс", 'y' to "уай", 'z' to "зэд")[c].orEmpty()
        }.joinToString(" ") }
        input = input.replace("%", " процентов ")
        return input.filter { symbols.has(it.toString()) && it !in "_|~" }.replace(Regex("\\s+"), " ").replace(Regex(" +(?=[.,!?;:])"), "").trim()
    }
    internal fun typeIds(text: String, size: Int): LongArray {
        fun strings(key: String): Set<String> { val a = intonation.getJSONArray(key); return (0 until a.length()).map(a::getString).toSet() }
        val openings = strings("QUOTE_OPEN"); val closings = strings("QUOTE_CLOSE")
        val wh = strings("WH_FORMS"); val fillers = strings("LEADING_FILLERS")
        fun classify(raw: String): Long {
            var s = raw.trim()
            while (s.isNotEmpty() && s.first().toString() in openings) s = s.drop(1).trim()
            while (s.isNotEmpty() && s.last().toString() in closings) s = s.dropLast(1).trim()
            var tail = s
            if (tail.endsWith("?!") || tail.endsWith("?..") || tail.endsWith("?...")) tail = tail.trimEnd('.', '!')
            if (tail.endsWith('?')) {
                val clean = tail.replace("+", "").replace('ё', 'е').replace(Regex("\\s+"), " ").trim()
                if (Regex(intonation.getString("TAG_RE"), RegexOption.IGNORE_CASE).containsMatchIn(clean)) return 4
                val words = Regex(intonation.getString("WORD_RE")).findAll(clean.lowercase(Locale.ROOT)).take(8).map { it.value }.filter { it !in fillers }.take(4)
                if (words.any { it in wh }) return 1
                if (wordRegex("\\bили\\b", RegexOption.IGNORE_CASE).containsMatchIn(clean)) return 3
                return 2
            }
            return if (s.endsWith('!')) 5 else 0
        }
        val sentences = text.trim().split(Regex(intonation.getString("SENT_SPLIT_RE")))
        val types = sentences.filter { it.isNotBlank() }.map(::classify).ifEmpty { listOf(0L) }
        val ids = mutableListOf<Long>(types.first())
        for ((i, sentence) in sentences.withIndex()) {
            val type = types.getOrElse(i) { types.last() }
            repeat(sentence.length) { ids.add(type) }
            if (i < sentences.lastIndex) ids.add(type)
        }
        return LongArray(size) { ids.getOrElse(it) { types.first() } }
    }
    fun generate(text: String, active: () -> Boolean, emit: (FloatArray) -> Boolean) {
        val input = normalized(text)
        require(input.any { it in 'а'..'я' || it == 'ё' }) { "Нет текста для озвучки." }
        // Bound native inference work so cancellation waits for at most one short chunk.
        for (sentence in chunks(input)) {
            if (!active()) break
            val accented = accentuate(sentence)
            if (!active()) break
            val ids = ("|$accented~").map { symbols.getLong(it.toString()) }.toLongArray()
            val rate = prefs.getInt("tts_rate", 100).coerceIn(70, 140) / 100f
            val pitch = 1f + prefs.getInt("tts_pitch", 0).coerceIn(-30, 30) / 100f
            val rates = IValue.from(Tensor.fromBlob(FloatArray(ids.size) { rate }, longArrayOf(1, ids.size.toLong())))
            val pitches = IValue.from(Tensor.fromBlob(FloatArray(ids.size) { pitch }, longArrayOf(1, ids.size.toLong())))
            val result = acoustic!!.forward(tensor(ids, 1, ids.size.toLong()), tensor(longArrayOf(data.getJSONObject("speakers").getLong(speaker)), 1),
                IValue.from(48000L), IValue.optionalNull(), rates, pitches, IValue.optionalNull(), IValue.optionalNull(),
                IValue.from("cpu"), IValue.from(-1L), IValue.from(false), tensor(typeIds(sentence, ids.size), 1, ids.size.toLong())).toTuple()[0].toTensor().dataAsFloatArray
            check(result.isNotEmpty() && result.all { it.isFinite() }) { "Модель вернула неверный звук." }
            if (!active() || !emit(result)) break
        }
    }
    fun close() { acoustic?.destroy(); acoustic = null; accent?.destroy(); accent = null; homo?.destroy(); homo = null }
    companion object {
        internal fun chunks(text: String): List<String> {
            val chunks = mutableListOf<String>(); var remaining = text.trim()
            while (remaining.isNotEmpty()) {
                val limit = minOf(remaining.length, 220)
                val end = if (remaining.length <= limit) limit else {
                    val punctuation = remaining.take(limit).indexOfLast { it in ".!?;" }
                    if (punctuation >= 40) punctuation + 1 else remaining.lastIndexOf(' ', limit).takeIf { it > 0 } ?: limit
                }
                chunks.add(remaining.take(end)); remaining = remaining.drop(end).trim()
            }
            return chunks
        }
    }
}
