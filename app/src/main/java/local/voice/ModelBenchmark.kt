package local.voice

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Cold-model, fresh-KV benchmark. Peak RAM is sampled process RSS, not a lifetime VmHWM. */
object ModelBenchmark {
    data class Result(val modelId: String, val sha256: String, val bindingRevision: String,
        val promptTokens: Long, val generationTokens: Long, val promptMicros: Long, val generationMicros: Long,
        val ttftMicros: Long, val loadMicros: Long, val peakRamBytes: Long, val threads: Int, val contextSize: Int, val measuredAt: Long) {
        val promptTokPerSecond get() = if (promptMicros > 0) promptTokens * 1_000_000.0 / promptMicros else 0.0
        val generationTokPerSecond get() = if (generationMicros > 0) generationTokens * 1_000_000.0 / generationMicros else 0.0
        fun json() = JSONObject().put("modelId", modelId).put("sha256", sha256).put("bindingRevision", bindingRevision)
            .put("promptTokens", promptTokens).put("generationTokens", generationTokens).put("promptMicros", promptMicros)
            .put("generationMicros", generationMicros).put("ttftMicros", ttftMicros).put("loadMicros", loadMicros)
            .put("peakRamBytes", peakRamBytes).put("threads", threads).put("contextSize", contextSize).put("measuredAt", measuredAt)
            .put("promptTokPerSecond", promptTokPerSecond).put("generationTokPerSecond", generationTokPerSecond)
            .put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}").put("api", android.os.Build.VERSION.SDK_INT)
            .put("protocol", "cold-rss-v1").put("ramSamplingMs", 20)
        fun message(): String = String.format(Locale.forLanguageTag("ru"),
            "Prompt processing: %.1f tok/s (%d токенов)\nGeneration: %.1f tok/s (%d токенов)\nTTFT с загрузкой: %.0f мс\nЗагрузка: %.0f мс\nPeak RAM процесса (RSS): %.0f МБ\nПотоки: %d · контекст: %d\n%s",
            promptTokPerSecond, promptTokens, generationTokPerSecond, generationTokens, ttftMicros / 1000.0,
            loadMicros / 1000.0, peakRamBytes / 1024.0 / 1024.0, threads, contextSize,
            java.text.SimpleDateFormat("d MMMM HH:mm", Locale.forLanguageTag("ru")).format(java.util.Date(measuredAt)))
    }
    private fun preferences(context: Context) = context.getSharedPreferences("model-benchmarks", 0)
    fun save(context: Context, result: Result) {
        require(result.promptTokens > 0 && result.generationTokens > 0 && result.promptMicros > 0 && result.generationMicros > 0 && result.peakRamBytes > 0)
        check(preferences(context).edit().putString(result.modelId, result.json().toString()).commit())
    }
    fun read(context: Context, brain: ModelCatalog.Brain): Result? = runCatching {
        val data = JSONObject(preferences(context).getString(brain.id, null) ?: return null)
        if (data.getString("modelId") != brain.id) return null
        if (brain.metadata != null && data.getString("sha256") != brain.metadata.sha256) return null
        if (data.getString("bindingRevision") != LocalBrain.bindingRevision) return null
        Result(data.getString("modelId"), data.getString("sha256"), data.getString("bindingRevision"), data.getLong("promptTokens"),
            data.getLong("generationTokens"), data.getLong("promptMicros"), data.getLong("generationMicros"), data.getLong("ttftMicros"),
            data.getLong("loadMicros"), data.getLong("peakRamBytes"), data.getInt("threads"), data.getInt("contextSize"), data.getLong("measuredAt"))
    }.getOrNull()
    fun delete(context: Context, id: String) { preferences(context).edit().remove(id).commit() }
    internal class RamSampler {
        private val running = AtomicBoolean()
        private val peak = AtomicLong()
        private var thread: Thread? = null
        private fun sample() {
            val bytes = runCatching { File("/proc/self/status").useLines { lines ->
                lines.firstOrNull { it.startsWith("VmRSS:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toLong()?.times(1024) ?: 0
            } }.getOrDefault(0)
            peak.accumulateAndGet(bytes, ::maxOf)
        }
        fun start() { sample(); running.set(true); thread = Thread({
            while (running.get()) { sample(); try { Thread.sleep(20) } catch (_: InterruptedException) { break } }
        }, "model-ram-sample").apply { isDaemon = true; start() } }
        fun peakBytes(): Long { sample(); return peak.get() }
        fun stop() { sample(); running.set(false); thread?.interrupt(); thread?.join(100) }
    }
}
