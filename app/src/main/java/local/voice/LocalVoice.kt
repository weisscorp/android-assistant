package local.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.AudioTimestamp
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Selected local voice, with one cancellable PCM playback path shared by all engines. */
class LocalVoice(private val context: Context, private val reference: PlaybackReference? = null, private val phoneSpeaker: Boolean = false) {
    companion object {
        // Rapid headset cancellation must not load several native TTS engines in parallel.
        private val worker = Executors.newSingleThreadExecutor()
        private val warmPiper = IdleNativeModel<OfflineTts> { voice -> worker.execute { voice.release() } }
        private val warmSilero = IdleNativeModel<SileroVoice> { voice -> worker.execute { voice.close() } }
    }
    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicInteger()
    private val output = AtomicReference<AudioTrack?>()
    @Volatile private var closed = false
    private var engineKey = ""
    private var engine: OfflineTts? = null
    private var silero: SileroVoice? = null
    fun initialize(done: (String?) -> Unit) {
        worker.execute {
            if (closed) return@execute
            val error = runCatching {
                val selection = ModelCatalog.voice(context)
                if (selection in listOf("baya", "aidar", "kseniya", "eugene", "xenia")) {
                    engineKey = selection
                    silero = warmSilero.take(engineKey) { SileroVoice(context, selection) }
                    return@runCatching
                }
                val dir = AssetModels.unpack(context, "voice-ru.zip", "voice-ru", "piper-ruslan-int8-v1")
                val custom = VoicePackage.custom(context)
                val voice = if (selection == "custom" && custom.exists()) custom else dir
                val model = voice.listFiles()!!.single { it.name.endsWith(".onnx") }
                engineKey = "${model.absolutePath}:${model.lastModified()}:${model.length()}"
                engine = warmPiper.take(engineKey) { OfflineTts(config = OfflineTtsConfig(model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(model = model.absolutePath, tokens = File(voice, "tokens.txt").absolutePath, dataDir = File(dir, "espeak-ng-data").absolutePath),
                    numThreads = 2
                ), maxNumSentences = 1)) }
            }.exceptionOrNull()?.let { "Не удалось загрузить встроенную голосовую модель." }
            main.post { if (!closed) done(error) }
        }
    }
    fun speak(text: String, done: (String?) -> Unit) {
        stop()
        val token = generation.get()
        worker.execute {
            if (closed || generation.get() != token) return@execute
            var track: AudioTrack? = null
            val error = runCatching {
                val tts = engine
                check(tts != null || silero != null) { "Голосовая модель ещё не готова." }
                val rate = if (silero != null) 48000 else tts!!.sampleRate()
                val size = maxOf(AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT), 8192)
                track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(if (phoneSpeaker) AudioAttributes.USAGE_ASSISTANT else AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(size).setTransferMode(AudioTrack.MODE_STREAM).build()
                val audio = track!!
                check(audio.state == AudioTrack.STATE_INITIALIZED)
                if (phoneSpeaker) {
                    val speaker = context.getSystemService(android.media.AudioManager::class.java).getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
                        .firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                        ?: error("Динамик телефона недоступен.")
                    check(audio.setPreferredDevice(speaker)) { "Не удалось выбрать динамик телефона." }
                }
                output.set(audio)
                if (closed || generation.get() != token) return@runCatching
                reference?.begin(rate)
                audio.play()
                val timestamp = AudioTimestamp()
                fun updateClock() {
                    if (audio.getTimestamp(timestamp) && System.nanoTime() - timestamp.nanoTime < 200_000_000)
                        reference?.clock(timestamp.framePosition, timestamp.nanoTime)
                    else reference?.clock(audio.playbackHeadPosition.toLong() and 0xffffffffL, System.nanoTime())
                }
                var written = 0L
                var playbackError: Throwable? = null
                val emit: (FloatArray) -> Int = emit@{ samples ->
                    if (closed || generation.get() != token) return@emit 0
                    // Never propagate an exception through the native callback boundary.
                    runCatching {
                        var offset = 0
                        while (offset < samples.size && !closed && generation.get() == token) {
                            val chunk = minOf(samples.size - offset, rate / 50)
                            updateClock()
                            reference?.append(samples, offset, chunk, written, audio.playbackHeadPosition.toLong() and 0xffffffffL, System.nanoTime())
                            val count = audio.write(samples, offset, chunk, AudioTrack.WRITE_BLOCKING)
                            updateClock()
                            check(count > 0) { "Аудиоканал отключился." }
                            offset += count; written += count
                        }
                    }.onFailure { playbackError = it }
                    if (playbackError != null || closed || generation.get() != token) 0 else 1
                }
                if (silero != null) silero!!.generate(SpokenReply.text(text), { !closed && generation.get() == token }) { emit(it) == 1 }
                else tts!!.generateWithCallback(SpokenReply.text(text), sid = 0, speed = 1.05f, callback = emit)
                playbackError?.let { throw it }
                val until = SystemClock.elapsedRealtime() + 5000
                while (!closed && generation.get() == token && (audio.playbackHeadPosition.toLong() and 0xffffffffL) < written) {
                    check(SystemClock.elapsedRealtime() < until) { "Гарнитура не завершила воспроизведение." }
                    updateClock(); Thread.sleep(20)
                }
            }.exceptionOrNull()?.let { "Ошибка локальной озвучки. Повторите вызов помощника." }
            output.compareAndSet(track, null)
            runCatching { track?.stop(); track?.release() }
            main.post { if (!closed && generation.get() == token) done(error) }
        }
    }
    internal val outputDeviceType: Int? get() = runCatching { output.get()?.routedDevice?.type }.getOrNull()
    internal val playingFrames: Long get() = runCatching { (output.get()?.playbackHeadPosition?.toLong() ?: 0L) and 0xffffffffL }.getOrDefault(0L)
    fun stop() {
        generation.incrementAndGet()
        // Release also unblocks a WRITE_BLOCKING call. Pausing alone can strand the JNI worker.
        output.getAndSet(null)?.let { track ->
            runCatching { track.pause() }; runCatching { track.flush() }; runCatching { track.release() }
        }
    }
    fun close(done: (() -> Unit)? = null) {
        if (closed) return
        closed = true; stop()
        worker.execute { engine?.let { warmPiper.put(it, engineKey) }; engine = null; silero?.let { warmSilero.put(it, engineKey) }; silero = null; if (done != null) main.post(done) }
    }
}
