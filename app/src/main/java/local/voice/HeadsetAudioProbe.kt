package local.voice

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt

/** User-started raw duplex check. No models, decoding, audio storage or phone-mic fallback. */
internal class HeadsetAudioProbe(context: Context, private val update: (String, Int) -> Unit,
                                  private val done: (String) -> Unit) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val recording = AtomicReference<AudioRecord?>()
    @Volatile private var closed = false
    private var started = false
    private var lease: Int? = null
    private var route: AudioRoute? = null
    private val guard = CallGuard(this.context) { finish("Проверка остановлена: идёт звонок.") }
    private val timeout = Runnable { finish("Проверка гарнитуры не завершилась.") }
    private val results = JSONArray()
    private val measurements = mutableListOf<CaptureSourcePolicy.Measurement>()

    fun start() {
        if (started || closed) return
        started = true
        VoiceConversation.stopActive()
        guard.start()
        if (closed) return
        update("Подключаю гарнитуру…", 0)
        main.postDelayed(timeout, 45_000)
        lease = AudioForeground.acquire(context, { finish("Проверка остановлена.") }) { error ->
            if (closed) return@acquire
            if (error != null) { finish(error); return@acquire }
            route = AudioRoute(context) { finish("Голосовой канал гарнитуры отключился.") }
            route!!.start(true) { failure ->
                if (closed) return@start
                if (failure != null) { finish(failure); return@start }
                worker.execute { capture() }
            }
        }
    }
    private fun capture() {
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            main.post { finish("Разрешите микрофон в настройках приложения.") }
            return
        }
        val manager = context.getSystemService(AudioManager::class.java)
        val communication = manager.communicationDevice
        val input = manager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
            it.type == communication?.type && it.address == communication.address
        }
        val error = runCatching {
            check(input != null && input.type in setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET)) { "Микрофон гарнитуры не найден." }
            for (source in CaptureSourcePolicy.probeSources) {
                if (closed) break
                val name = when (source) { 7 -> "Разговор"; 1 -> "Обычный захват"; else -> "Распознавание" }
                val audio = AudioRecord.Builder().setAudioSource(source)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(maxOf(6400, AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT))).build()
                recording.set(audio)
                try {
                    check(audio.state == AudioRecord.STATE_INITIALIZED && audio.setPreferredDevice(input)) { "Не удалось открыть выбранный микрофон." }
                    if (closed) break
                    audio.startRecording()
                    val startedAt = SystemClock.elapsedRealtime()
                    var lastUpdate = 0L
                    var peak = 0
                    var nonzero = 0L
                    var samples = 0L
                    var silenced = false
                    val pcm = ShortArray(320)
                    while (!closed && SystemClock.elapsedRealtime() - startedAt < 8000) {
                        val read = audio.read(pcm, 0, pcm.size)
                        if (closed) break
                        check(read > 0 && audio.routedDevice?.id == input.id) { "Микрофон отключился или изменился маршрут." }
                        val elapsed = SystemClock.elapsedRealtime() - startedAt
                        // Skip routing startup transients; measure real sustained capture.
                        val rms = sqrt((0 until read).sumOf { pcm[it].toDouble() * pcm[it] } / read).toInt()
                        if (elapsed >= 1000) {
                            peak = maxOf(peak, rms)
                            nonzero += (0 until read).count { pcm[it].toInt() != 0 }
                            samples += read
                            silenced = silenced || audio.activeRecordingConfiguration?.isClientSilenced == true
                        }
                        if (elapsed - lastUpdate >= 200) {
                            lastUpdate = elapsed
                            main.post { if (!closed) update("$name · говорите ещё ${(8000 - elapsed) / 1000 + 1} с\nУровень: $rms", (rms * 100 / 1800).coerceIn(0, 100)) }
                        }
                    }
                    if (!closed) {
                        measurements.add(CaptureSourcePolicy.Measurement(source, samples, nonzero, peak, silenced))
                        results.put(JSONObject().put("source", source).put("inputType", audio.routedDevice?.type)
                            .put("peakRms", peak).put("nonzeroSamples", nonzero).put("samples", samples).put("silenced", silenced))
                    }
                } finally {
                    recording.compareAndSet(audio, null)
                    runCatching { audio.stop() }; audio.release()
                }
            }
        }.exceptionOrNull()
        main.post {
            if (!closed) {
                context.getSharedPreferences("diagnostics", 0).edit().putString("headset_probe", results.toString()).apply()
                val chosen = if (error == null && communication != null)
                    HeadsetCapturePreferences.save(context, AudioRoute.key(communication), measurements) else null
                finish(error?.message ?: "Проверка завершена.\n" + (0 until results.length()).joinToString("\n") {
                    val result = results.getJSONObject(it)
                    "Вход ${result.getInt("source")}: уровень ${result.getInt("peakRms")}, ${result.getLong("nonzeroSamples")} ненулевых отсчётов"
                } + if (chosen != null) "\nРабочий режим $chosen сохранён для этой гарнитуры."
                    else "\nРабочий микрофон не подтверждён. Проверьте микрофон гарнитуры.")
            }
        }
    }
    private fun finish(message: String) { if (!closed) { close(); done(message) } }
    fun close() {
        if (closed) return
        closed = true
        main.removeCallbacks(timeout)
        runCatching { recording.get()?.stop() }
        guard.close(); route?.close(); route = null
        lease?.let { AudioForeground.release(context, it) }; lease = null
        worker.shutdown()
    }
}
