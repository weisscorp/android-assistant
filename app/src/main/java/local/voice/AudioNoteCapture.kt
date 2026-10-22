package local.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Record first, transcribe the saved WAV afterwards. Silence never terminates a note. */
internal class AudioNoteCapture(context: Context, private val changed: (String) -> Unit) {
    private val app = context.applicationContext
    private val store = AudioNotes(app)
    private val main = Handler(Looper.getMainLooper())
    private val stopping = AtomicBoolean()
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var recorder: AudioRecord? = null
    private var lease = 0
    private var started = false
    fun start() {
        if (started) return
        started = true
        VoiceConversation.stopActive()
        lease = AudioForeground.acquire(app, ::stop) { error ->
            if (error != null || stopping.get()) { complete(error ?: "Запись отменена"); worker.shutdown() }
            else worker.execute { record() }
        }
    }
    private fun record() {
        var id: String? = null
        val failure = runCatching {
            if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                throw SecurityException("Нет разрешения на запись микрофона")
            val size = maxOf(6400, AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT))
            val capture = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setBufferSizeInBytes(size).build()
            recorder = capture
            check(capture.state == AudioRecord.STATE_INITIALIZED) { "Не удалось открыть микрофон" }
            if (stopping.get()) return@runCatching
            capture.startRecording()
            check(capture.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Микрофон занят" }
            val note = store.create(); id = note.getString("id")
            store.audio(id!!).outputStream().use { output ->
                output.write(AudioNotes.wavHeader(0))
                val pcm = ByteArray(6400); var bytes = 0
                main.post { changed("Запись идёт · до 10 минут. Нажмите «Сохранить запись».") }
                while (!stopping.get() && bytes < 16000 * 2 * 600) {
                    val read = capture.read(pcm, 0, minOf(pcm.size, 16000 * 2 * 600 - bytes))
                    if (read <= 0 && stopping.get()) break
                    check(read > 0) { "Микрофон отключился" }
                    check(capture.activeRecordingConfiguration?.isClientSilenced != true) { "Android заглушил микрофон. Запись остановлена." }
                    output.write(pcm, 0, read); bytes += read
                }
                output.flush(); output.fd.sync()
            }
        }.exceptionOrNull()
        runCatching { recorder?.stop() }; runCatching { recorder?.release() }; recorder = null
        val saved = id?.let { runCatching { store.finalize(it) }.getOrNull() }
        if (saved != null) AudioNoteTranscriber.enqueue(app, saved.getString("id"))
        complete(if (saved != null) "Запись сохранена. Распознаю текст…" + (failure?.message?.let { "\n$it" } ?: "")
            else failure?.message ?: "Запись пуста. Попробуйте ещё раз.")
        worker.shutdown()
    }
    private fun complete(message: String) { main.post { AudioForeground.release(app, lease); changed(message); finished?.invoke() } }
    var finished: (() -> Unit)? = null
    fun stop() { stopping.set(true); runCatching { recorder?.stop() } }
}

internal object AudioNoteTranscriber {
    private val worker = Executors.newSingleThreadExecutor()
    private val queued = mutableSetOf<String>()
    fun resume(context: Context) {
        runCatching {
            val store = AudioNotes(context); store.recover()
            store.local().filter { it.getString("status") == "pending" }.forEach { enqueue(context, it.getString("id")) }
        }
    }
    fun enqueue(context: Context, id: String) {
        synchronized(queued) { if (!queued.add(id)) return }
        val app = context.applicationContext
        worker.execute {
            val store = AudioNotes(app)
            var model: org.vosk.Model? = null
            var tone: com.k2fsa.sherpa.onnx.OnlineRecognizer? = null
            var giga: com.k2fsa.sherpa.onnx.OfflineRecognizer? = null
            try {
                store.update(id) { it.put("status", "pending") }
                val engine = SpeechEngines.selected(app)
                if (engine != SpeechEngine.TONE) {
                    val dir = AssetModels.unpack(app, "speech-ru.zip", "speech-ru", "vosk-small-ru-0.22")
                    model = WarmSpeechModels.vosk.take { org.vosk.Model(dir.absolutePath) }
                }
                when (engine) {
                    SpeechEngine.TONE -> tone = WarmSpeechModels.tone.take { ToneModels.open(app) }
                    SpeechEngine.GIGAAM -> giga = WarmSpeechModels.giga.take { GigaAmModels.open(app) }
                    else -> Unit
                }
                fun decoder(): SpeechDecoder = when (engine) {
                    SpeechEngine.TONE -> ToneDecoder(tone!!)
                    SpeechEngine.GIGAAM -> GigaAmDecoder(giga!!, org.vosk.Recognizer(model, 16000f))
                    else -> VoskDecoder(org.vosk.Recognizer(model, 16000f))
                }
                val words = mutableListOf<String>()
                var current = decoder()
                try {
                    store.audio(id).inputStream().use { input ->
                        check(input.skip(44) == 44L)
                        val bytes = ByteArray(6400)
                        while (true) {
                            val count = input.read(bytes); if (count < 0) break
                            check(count % 2 == 0)
                            val samples = ShortArray(count / 2) { i -> ((bytes[i*2].toInt() and 255) or (bytes[i*2+1].toInt() shl 8)).toShort() }
                            if (current.accept(samples)) {
                                words.add(current.result())
                                if (engine != SpeechEngine.VOSK) { current.close(); current = decoder() }
                            }
                        }
                        words.add(current.finish())
                    }
                } finally { current.close() }
                val text = words.filter { it.isNotBlank() }.joinToString(" ")
                check(text.length <= 16000) { "Текст слишком длинный; оригинал сохранён." }
                store.update(id) { it.put("text", text).put("status", "ready").put("engine", engine.id)
                    if (text.isNotBlank()) it.put("title", text.take(60)) }
            } catch (_: Exception) { runCatching { store.update(id) { it.put("status", "error") } } }
            finally {
                model?.let(WarmSpeechModels.vosk::put); tone?.let(WarmSpeechModels.tone::put)
                giga?.let(WarmSpeechModels.giga::put)
                synchronized(queued) { queued.remove(id) }; HomeLink.kick(app)
            }
        }
    }
}
