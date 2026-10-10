package local.voice

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Comparative evidence through production APIs; completed evaluation is not a quality pass. */
class RussianModelEvaluationTest {
    @Test fun evaluateProductionRussianConversationAndTools() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        val id = args.getString("evaluation_id") ?: error("Use tools/evaluate_models.py on an owned emulator")
        val source = File(context.filesDir, "quality-evaluation.gguf")
        assertEquals(args.getString("evaluation_sha256"), ModelMetadata.digest(source))
        val settings = context.getSharedPreferences("settings", 0)
        val memory = context.getSharedPreferences("conversation_memory", 0)
        val originalSettings = settings.all.toMap()
        val oldNote = memory.getString("note", null)
        val report = JSONObject().put("id", id).put("bindingRevision", LocalBrain.bindingRevision)
            .put("evaluationSuite", "neutral-russian-v2")
            .put("sha256", args.getString("evaluation_sha256")).put("physicalPhone", false)
        val cases = JSONArray(); report.put("cases", cases)
        val output = File(context.cacheDir, "quality-$id.json")
        val ram = ModelBenchmark.RamSampler().also { it.start() }
        var brain: ModelCatalog.Brain? = null
        fun await(start: ((String) -> Unit) -> Unit): String {
            val done = CountDownLatch(1); var response = ""
            instrumentation.runOnMainSync { start { response = it; done.countDown() } }
            assertTrue("Inference timed out for $id", done.await(100, TimeUnit.SECONDS))
            return response
        }
        fun record(name: String, question: String, matches: (String) -> Boolean, start: ((String) -> Unit) -> Unit) {
            val began = android.os.SystemClock.elapsedRealtime()
            val response = await(start)
            val stats = LocalBrain.lastStats
            cases.put(JSONObject().put("case", name).put("question", question).put("response", response)
                .put("criterionMet", matches(response)).put("failure", LocalBrain.lastFailure)
                .put("elapsedMs", android.os.SystemClock.elapsedRealtime() - began)
                .put("promptTokens", stats?.promptTokens).put("prefillMicros", stats?.prefillMicros)
                .put("totalMicros", stats?.totalMicros).put("threads", stats?.threads))
            output.writeText(report.toString(2))
        }
        fun ask(name: String, question: String, regex: String, note: String? = null) {
            assertTrue(if (note == null) memory.edit().remove("note").commit() else memory.edit().putString("note", note).commit())
            record(name, question, { Regex(regex, RegexOption.IGNORE_CASE).containsMatchIn(it) }) { done ->
                LocalBrain.answerWithMemory(context, emptyList(), question, done)
            }
        }
        try {
            val validated = CountDownLatch(1); var info: Result<GgufMetadata>? = null
            instrumentation.runOnMainSync { LocalBrain.validate(source) { info = it; validated.countDown() } }
            assertTrue(validated.await(190, TimeUnit.SECONDS))
            val metadata = info!!.getOrThrow()
            report.put("architecture", metadata.architecture).put("chatTemplate", metadata.chatTemplate)
            brain = ModelCatalog.installBrain(context, source, id,
                ModelMetadata.imported(metadata, source, id, args.getString("evaluation_sha256")!!))
            ask("count", "Назови число после двух.", "(?:^|\\D)3(?:\\D|$)|\\bтри\\b")
            ask("arithmetic", "Сколько будет семь плюс пять? Ответь числом.", "(?:^|\\D)12(?:\\D|$)|двенадцать")
            ask("negation", "Я не в Москве, я в Казани. В каком городе я нахожусь?", "казан")
            ask("order", "Первая задача — проверить почту, вторая — открыть календарь. Что нужно сделать вторым?", "календар")
            ask("brief-russian", "Что такое набережная? Ответь одним коротким предложением.", "берег|реки|моря|вод[ыао]")
            ask("polite-russian", "Перефразируй вежливо по-русски: «Отстань, я занят». Одно предложение.", "пожалуйста|извин|занят|позже")
            ask("unknown-location", "Где сейчас курьер? У тебя нет доступа к его геолокации. Не придумывай.", "не (?:знаю|могу)|нет (?:данных|доступа|информации)|неизвест|не располагаю")
            val note = JSONObject().put("topics", JSONArray(listOf("поездка")))
                .put("points", JSONArray(listOf("Место назначения — Казань", "Пользователь едет поездом"))).toString()
            ask("memory", "Куда я собираюсь и на чём?", "(?s)(?=.*казан)(?=.*поезд)", note)
            memory.edit().remove("note").commit()
            for ((name, question, expected) in listOf(Triple("tool-battery", "Узнай сколько заряда осталось.", "battery"),
                Triple("tool-pause", "Приостанови воспроизведение музыки.", "media"),
                Triple("tool-open", "Открой Telegram.", "open"),
                Triple("tool-media-required", "что щас играет за песня", "media"))) {
                var raw = ""
                record(name, question, { runCatching { JSONObject(raw).getString("tool") == expected }.getOrDefault(false) }) { done ->
                    LocalBrain.plan(context, emptyList(), question, rawObserver = { raw = it }) { done(raw) }
                }
            }
            for ((name, state, title, artist) in listOf(
                listOf("read-playing", "playing", "Road to Nowhere", "Talking Heads"),
                listOf("read-paused", "paused", "Звезда по имени Солнце", "Кино"))) {
                var raw = ""
                val observed = ToolObservation("media", "info", "ok", mapOf("title" to title, "artist" to artist, "state" to state),
                    if (state == "paused") "На паузе «$title» — $artist." else "Сейчас играет «$title» — $artist.")
                record(name, "Что сейчас играет?", { raw.contains(title, true) && raw.contains(artist, true) &&
                    Regex(if (state == "paused") "пауз|приостанов" else "играет|воспроизвод", RegexOption.IGNORE_CASE).containsMatchIn(raw) &&
                    Regex("[А-Яа-яЁё]").containsMatchIn(raw) }) { done ->
                    LocalBrain.answerTool(context, "Что сейчас играет?", observed, rawObserver = { raw = it }, done = done)
                }
                cases.getJSONObject(cases.length() - 1).put("rawResponse", raw).put("fallbackUsed", raw != cases.getJSONObject(cases.length() - 1).getString("response"))
            }
            report.put("peakRamBytes", ram.peakBytes()).put("evaluationCompleted", true)
            // Required media call is a runtime check, not evidence of freely chosen tools.
            report.put("scoredCases", cases.length() - 1).put("criteriaMet", (0 until cases.length()).count {
                cases.getJSONObject(it).getString("case") != "tool-media-required" && cases.getJSONObject(it).getBoolean("criterionMet") })
            output.writeText(report.toString(2))
        } finally {
            ram.stop()
            instrumentation.runOnMainSync { LocalBrain.cancel(); LocalBrain.trimIdle() }
            brain?.let { ModelCatalog.deleteBrain(context, it) }
            source.delete()
            val restored = settings.edit().clear()
            originalSettings.forEach { (key, value) -> when (value) {
                is String -> restored.putString(key, value); is Boolean -> restored.putBoolean(key, value)
                is Int -> restored.putInt(key, value); is Long -> restored.putLong(key, value); is Float -> restored.putFloat(key, value)
                is Set<*> -> restored.putStringSet(key, value.filterIsInstance<String>().toSet())
            } }; restored.commit()
            if (oldNote == null) memory.edit().remove("note").commit() else memory.edit().putString("note", oldNote).commit()
        }
    }
}
