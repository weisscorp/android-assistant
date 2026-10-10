package local.voice

import android.content.Context
import android.util.Log
import org.vosk.Model
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Exclusive checkout, no microphone and no retained utterance/decoder state. */
internal class IdleNativeModel<T : Any>(private val retainUntilMemoryPressure: Boolean = false, private val release: (T) -> Unit) {
    companion object {
        private val expiry = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "speech-model-expiry").apply { isDaemon = true } }
    }
    private var idle: T? = null
    private var epoch = 0L
    private var key = ""
    fun take(requestedKey: String = "", create: () -> T): T {
        val (cached, cachedKey) = synchronized(this) { val value = idle; idle = null; epoch++; value to key }
        if (cached != null && cachedKey == requestedKey) return cached
        cached?.let(release)
        return create()
    }
    /** A checkout waits for an in-flight warmup instead of creating a second engine. */
    fun warm(create: () -> T) = synchronized(this) {
        if (idle == null) put(create())
    }
    fun clearIdle() {
        val old = synchronized(this) { epoch++; idle.also { idle = null } }
        old?.let(release)
    }
    fun put(model: T, modelKey: String = "") {
        val (previous, ticket) = synchronized(this) { val old = idle; idle = model; key = modelKey; old to ++epoch }
        previous?.let(release)
        if (retainUntilMemoryPressure) return
        expiry.schedule({
            val expired = synchronized(this) { if (epoch == ticket && idle === model) idle.also { idle = null } else null }
            expired?.let(release)
        }, 10, TimeUnit.MINUTES)
    }
}
internal object WarmSpeechModels {
    val vosk = IdleNativeModel<Model>(retainUntilMemoryPressure = true) { it.close() }
    val tone = IdleNativeModel<OnlineRecognizer>(retainUntilMemoryPressure = true) { it.release() }
    private val loader = Executors.newSingleThreadExecutor { task -> Thread(task, "speech-preload").apply { isDaemon = true } }
    fun trim() { vosk.clearIdle(); tone.clearIdle() }
    fun preload(context: Context) {
        val app = context.applicationContext
        loader.execute {
            runCatching {
                vosk.warm {
                    val directory = AssetModels.unpack(app, "speech-ru.zip", "speech-ru", "vosk-small-ru-0.22")
                    Model(directory.absolutePath)
                }
                if (app.getSharedPreferences("settings", 0).getBoolean("tone_asr", false))
                    tone.warm { ToneModels.open(app) }
            }.onFailure { Log.w("WarmSpeechModels", "Preload failed; normal startup will retry", it) }
        }
    }
}
