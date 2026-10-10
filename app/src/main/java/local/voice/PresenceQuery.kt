package local.voice

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.Locale

internal class PresenceQuery(private val context: Context, private val source: SavedSource,
    private val allowed: () -> Boolean, private val question: String = "", private val done: (String) -> Unit) {
    private val worker = Executors.newSingleThreadExecutor()
    private val trace = InteractionJournal.current?.begin("knowledge", InteractionJournal.data("source_id" to source.id, "name" to source.name, "kind" to source.kind.toString(), "question" to question, "source" to source.value, "instructions" to source.instructions, "reader_recipe" to source.readerRecipe))
    private val main = Handler(Looper.getMainLooper())
    private val http = PresenceHttp()
    @Volatile private var cancelled = false
    private fun valid() = !cancelled && allowed()
    fun cancel() { cancelled = true; http.cancel(); worker.shutdownNow() }
    fun start() {
        worker.execute {
            val result = runCatching {
                when (source.kind) {
                    SourceKind.NOTE -> { readText(source.value, saved = true); return@execute }
                    SourceKind.IMAGE -> {
                        val file = SavedSources(context).image(source)
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(file.path, bounds)
                        val options = BitmapFactory.Options().apply { inSampleSize = 1; while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 1600) inSampleSize *= 2 }
                        val bitmap = BitmapFactory.decodeFile(file.path, options) ?: error("Не удалось прочитать изображение.")
                        val text = try { AppScreenReader.recognize(context, bitmap).first } finally { bitmap.recycle() }
                        val evidence = PresenceDocument.telegramText(text, source.participant, snapshot = true)
                        finish(if (evidence != null) PresenceFacts.speech(source.name, evidence, System.currentTimeMillis())
                            else "${source.name}. Сохранённая картинка; положение сейчас неизвестно. Распознанный текст, возможны ошибки: ${text.take(1500).ifBlank { "текст не найден" }}")
                        return@execute
                    }
                    SourceKind.PLACE -> PresenceEvidence(fix = PresenceFacts.pointFromLink(source.value), place = "Сохранённый ориентир ${source.name}", fixedPlace = true)
                    SourceKind.WEB -> {
                        val recipe = source.readerRecipe.takeIf(String::isNotBlank)?.let(KnowledgeReaderRecipe::decode)
                        val body = http.get(recipe?.endpoint(source.value) ?: source.value)
                        if (recipe != null) recipe.parse(body)
                        else {
                            val point = runCatching { PresenceDocument.parse(body, source.participant) }.getOrNull()
                            if (point != null) point else { readText(KnowledgeDocument.text(body), saved = false); return@execute }
                        }
                    }
                    SourceKind.TELEGRAM -> error("Для Telegram нужен доступ к экрану.")
                }
            }
            result.onSuccess(::describe).onFailure { finish(if (it is IllegalArgumentException || it is IllegalStateException) it.message?.take(250) ?: "Не удалось прочитать источник." else "Не удалось прочитать источник: сеть или формат данных недоступны.") }
        }
    }
    private fun readText(value: String, saved: Boolean) {
        trace?.event("data", InteractionJournal.data("text" to value, "saved" to saved))
        val text = PresenceFacts.forSpeech(value).take(3000)
        if (text.isBlank()) { finish("${source.name}. Страница не передала читаемые данные. Если они загружаются отдельно, добавьте правило чтения к этому знанию."); return }
        if (question.isBlank() || !LocalBrain.model(context).exists()) {
            finish("${source.name}. ${if (saved) "Сохранённый текст" else "Прочитанный текст, геопозиция не подтверждена"}: ${text.take(2000)}"); return
        }
        main.post {
            if (!valid()) return@post
            LocalBrain.answerKnowledge(context, question, source.instructions, text) { answer ->
                if (valid()) finish("${source.name}. ${if (!saved) "По тексту источника; положение на карте не подтверждено. " else ""}$answer")
            }
        }
    }
    fun describe(evidence: PresenceEvidence) {
        trace?.event("data", InteractionJournal.data("evidence" to evidence.toString()))
        if (!valid()) return
        val store = SavedSources(context)
        val point = evidence.fix
        val fingerprint=java.security.MessageDigest.getInstance("SHA-256").digest("${source.kind}:${source.value}:${source.participant}:${source.readerRecipe}".toByteArray()).joinToString("") { "%02x".format(it) }
        val previous = store.previous(source.id,fingerprint)
        if (point != null && !evidence.snapshot && !evidence.fixedPlace && !evidence.freshnessRejected) store.remember(source.id, point,fingerprint)
        // A developer-operated endpoint only. Public Nominatim prohibits personal tracking data.
        val resolver = context.getSharedPreferences("settings", 0).getString("presence_geocoder", "").orEmpty()
        worker.execute {
            var fact = evidence
            if (point != null && evidence.place.isBlank() && resolver.isNotBlank() && point.accuracy?.let { it <= 100 } != false) {
                val address = runCatching {
                    require(java.net.URI(resolver).host != "nominatim.openstreetmap.org")
                    val sep = if (resolver.contains('?')) "&" else "?"
                    val json = JSONObject(http.get(resolver + sep + String.format(Locale.US, "lat=%.6f&lon=%.6f&format=jsonv2&addressdetails=1&accept-language=ru", point.latitude, point.longitude)))
                    val fields = json.optJSONObject("address")
                    val street = listOf("road", "pedestrian", "footway", "residential").firstNotNullOfOrNull { fields?.optString(it)?.takeIf(String::isNotBlank) }
                    street?.let { "По карте: рядом с $it" } ?: json.optString("name").takeIf(String::isNotBlank)?.let { "По карте: рядом с $it" }
                }.getOrNull()
                if (address != null) fact = evidence.copy(place = address.take(220))
            }
            val description = fact
            main.post {
                if (!valid()) return@post
                if (point == null) { finish(PresenceFacts.speech(source.name, description, System.currentTimeMillis())); return@post }
                PresencePosition.get(context, ::valid) { me ->
                    finish(PresenceFacts.speech(source.name, description, System.currentTimeMillis(), me, previous, store.landmarks()))
                }
            }
        }
    }
    private fun finish(text: String) { main.post { if (valid()) { trace?.result(InteractionJournal.data("text" to text)); done(text); cancel() } } }
}
