package local.voice

import android.Manifest
import android.content.pm.PackageManager
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import org.vosk.Model
import org.vosk.Recognizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** A single PCM owner across listening/speaking/thinking; no second microphone for barge-in. */
class OfflineSpeech(
    private val context: Context,
    private val reference: PlaybackReference = PlaybackReference(),
    private val persistent: Boolean = false,
    private val phoneMicrophone: Boolean = false,
    private val readyCueExpected: Boolean = false,
    private val level: ((Int, Int, Boolean) -> Unit)? = null,
    private val beforeModelLoad: (() -> Unit)? = null,
    private val fatal: ((String) -> Unit)? = null
) {
    internal companion object {
        const val NO_HEADSET_AUDIO = "От микрофона гарнитуры не приходит звук. Проверьте микрофон и выполните проверку звука в настройках."
    }
    private data class Request(val id: Int, val interrupt: Boolean, val headset: Boolean, val partial: (String) -> Unit, val done: (String?, String?) -> Unit)
    private val worker = Executors.newSingleThreadExecutor()
    private val modelLoader = Executors.newSingleThreadExecutor()
    @Volatile private var modelsReady = false
    private val main = Handler(Looper.getMainLooper())
    private val control = Any()
    private val generation = AtomicInteger()
    private val recorder = AtomicReference<AudioRecord?>()
    private val request = AtomicReference<Request?>()
    private val capturing = AtomicBoolean()
    @Volatile internal var captureSource = -1
        private set
    @Volatile internal var inputDeviceType = -1
        private set
    @Volatile internal var rawInputSamples = 0L
        private set
    @Volatile internal var nonzeroInputSamples = 0L
        private set
    @Volatile private var closed = false
    @Volatile private var finishRequested = false
    @Volatile private var cueUntil = 0L
    fun muteReadyCue(milliseconds: Long = 260) { cueUntil = SystemClock.elapsedRealtime() + milliseconds }
    private var model: Model? = null
    // A setting change applies to the next instance, not half of an ongoing utterance.
    private val useTone = context.getSharedPreferences("settings", 0).getBoolean("tone_asr", false)
    internal val engineName: String get() = if (useTone) "t-one" else "vosk"
    private var tone: OnlineRecognizer? = null
    private fun loadTone(): OnlineRecognizer {
        tone?.let { return it }
        return WarmSpeechModels.tone.take { ToneModels.open(context) }.also { tone = it }
    }
    private fun loadModel(): Model {
        model?.let { return it }
        val directory = AssetModels.unpack(context, "speech-ru.zip", "speech-ru", "vosk-small-ru-0.22")
        return WarmSpeechModels.vosk.take { Model(directory.absolutePath) }.also { model = it }
    }
    fun prepare(done: (String?) -> Unit) {
        modelLoader.execute {
            if (closed) return@execute
            val error = runCatching {
                if (!modelsReady) beforeModelLoad?.invoke()
                if (!closed) { loadModel(); if (useTone) loadTone(); modelsReady = true }
            }.exceptionOrNull()?.let { "Не удалось загрузить русское распознавание." }
            main.post { if (!closed) done(error) }
        }
    }
    fun listen(requireHeadset: Boolean, partial: (String) -> Unit, done: (String?, String?) -> Unit) = begin(false, requireHeadset, partial, done)
    fun interrupt(requireHeadset: Boolean, ready: () -> Unit, stopped: () -> Unit) = begin(true, requireHeadset, { if (it == "Слушаю прерывание…") ready() }, { text, error ->
        if (error == null && text != null) stopped()
    })
    private fun begin(interrupt: Boolean, headset: Boolean, partial: (String) -> Unit, done: (String?, String?) -> Unit) = synchronized(control) {
        if (closed) return
        cancel()
        prepare { error ->
            if (error != null && !closed) {
                fatal?.invoke(error) ?: request.get()?.done?.invoke(null, error)
                cancel()
            }
        }
        finishRequested = false
        request.set(Request(generation.get(), interrupt, headset, partial, done))
        if (capturing.compareAndSet(false, true)) worker.execute { capture() }
    }
    private fun capture() {
        val epoch = generation.get()
        var capture: AudioRecord? = null
        var suppressor: NoiseSuppressor? = null
        var hardwareEcho: AcousticEchoCanceler? = null
        var echo: EchoProcessor? = null
        var decoder: SpeechDecoder? = null
        var current: Request? = null
        val pending = ArrayDeque<ShortArray>()
        var pendingSamples = 0
        val error = runCatching {
            if (closed) return@runCatching
            val audio = context.getSystemService(AudioManager::class.java)
            val communication = audio.communicationDevice
            val types = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET)
            val preferred = if (phoneMicrophone) audio.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                else if (communication?.type in types) audio.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
                it.type == communication!!.type && it.address == communication.address
            } else null
            check(!phoneMicrophone || preferred != null) { "Микрофон телефона недоступен." }
            check(request.get()?.headset != true || preferred?.type in types) { "Микрофон гарнитуры недоступен." }
            check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { "Разрешите микрофон в настройках приложения." }
            val size = maxOf(AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), 6400)
            val bluetooth = preferred?.type in setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET)
            val calibrated = if (bluetooth && communication != null)
                HeadsetCapturePreferences.source(context, AudioRoute.key(communication)) else null
            captureSource = CaptureSourcePolicy.select(phoneMicrophone, bluetooth, calibrated)
            capture = AudioRecord.Builder().setAudioSource(captureSource)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(size).build()
            val recording = capture!!
            check(recording.state == AudioRecord.STATE_INITIALIZED) { "Не удалось открыть микрофон." }
            if (preferred != null) check(recording.setPreferredDevice(preferred)) { "Не удалось выбрать микрофон гарнитуры." }
            suppressor = runCatching { if (!phoneMicrophone && !bluetooth && NoiseSuppressor.isAvailable()) NoiseSuppressor.create(recording.audioSessionId)?.apply { enabled = true } else null }.getOrNull()
            hardwareEcho = runCatching { if (!bluetooth && AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(recording.audioSessionId)?.apply { enabled = true } else null }.getOrNull()
            echo = EchoProcessor()
            val gate = EchoGate(reference)
            val pcm = ShortArray(160); val play = ShortArray(160); val processed = ShortArray(160)
            recorder.set(recording)
            if (closed) return@runCatching
            recording.startRecording()
            check(recording.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Микрофон занят." }
            val timestamp = AudioTimestamp()
            var frames = 0L
            var until = Long.MAX_VALUE
            var lastPartial = 0L
            var candidate = ""
            var candidateSince = 0L
            var lastLevel = 0L
            var rawPeak = 0
            var cleanPeak = 0
            var inputWatchdog: ZeroInputWatchdog? = null
            while (!closed && (persistent || request.get() != null)) {
                var count = 0
                while (count < pcm.size && !closed) {
                    val read = recording.read(pcm, count, pcm.size - count)
                    check(read > 0) { "Микрофон отключился." }; count += read
                }
                if (closed) break
                if (preferred != null) check(recording.routedDevice?.id == preferred.id) { "Аудиомаршрут изменился. Вызовите помощника снова." }
                inputDeviceType = recording.routedDevice?.type ?: -1
                rawInputSamples += pcm.size
                nonzeroInputSamples += pcm.count { it.toInt() != 0 }
                val nanos = if (recording.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS)
                    timestamp.nanoTime + (frames - timestamp.framePosition) * 1_000_000_000L / 16000
                else System.nanoTime() - 10_000_000
                frames += pcm.size
                reference.frame(nanos, play)
                echo!!.process(pcm, play, processed)
                // During quiet listening, stacked suppression must not erase the user's speech.
                val clean = if (SystemClock.elapsedRealtime() < cueUntil) ShortArray(pcm.size)
                    else if (reference.hasRecentAudio(nanos)) gate.process(processed, nanos, pcm)
                    else pcm
                val active = request.get()
                if (active !== current) {
                    decoder?.close(); decoder = null; current = active
                    candidate = ""; candidateSince = 0; lastPartial = 0
                    pending.clear(); pendingSamples = 0
                    inputWatchdog = if (bluetooth && active != null && !active.interrupt)
                        ZeroInputWatchdog(SystemClock.elapsedRealtime()) else null
                    if (active != null) {
                        check(!active.headset || preferred != null) { "Микрофон гарнитуры недоступен." }
                        if (!active.interrupt && readyCueExpected) muteReadyCue()
                        until = if (active.interrupt) Long.MAX_VALUE else SystemClock.elapsedRealtime() + 15000
                        main.post { if (!closed && generation.get() == active.id) active.partial(if (active.interrupt) "Слушаю прерывание…" else "Слушаю…") }
                    }
                }
                if (active == null) continue // Keep AEC history, but do not decode idle PCM.
                check(inputWatchdog?.failed(SystemClock.elapsedRealtime(), pcm,
                    recording.activeRecordingConfiguration?.isClientSilenced == true) != true) { NO_HEADSET_AUDIO }
                if (!active.interrupt && level != null) {
                    fun rms(samples: ShortArray) = kotlin.math.sqrt(samples.sumOf { it.toDouble() * it } / samples.size).toInt()
                    rawPeak = maxOf(rawPeak, rms(pcm)); cleanPeak = maxOf(cleanPeak, rms(clean))
                    val tick = SystemClock.elapsedRealtime()
                    if (tick - lastLevel >= 300) {
                        lastLevel = tick
                        val raw = rawPeak; val filtered = cleanPeak
                        val silenced = recording.activeRecordingConfiguration?.isClientSilenced ?: false
                        rawPeak = 0; cleanPeak = 0
                        main.post { if (!closed && generation.get() == active.id) level.invoke(raw, filtered, silenced) }
                    }
                }
                if (!modelsReady) {
                    // Explicit finish freezes this utterance even while its model is loading.
                    if (!finishRequested) {
                        check(pendingSamples + clean.size <= 16000 * 30) { "Распознавание не загрузилось за 30 секунд." }
                        pending.addLast(clean.copyOf()); pendingSamples += clean.size
                    }
                    continue
                }
                if (decoder == null) {
                    val localModel = model ?: error("Модель распознавания недоступна.")
                    decoder = if (active.interrupt && phoneMicrophone) VoskDecoder(Recognizer(localModel, 16000f))
                        else if (active.interrupt) VoskDecoder(Recognizer(localModel, 16000f, "[\"стоп\",\"замолчи\",\"отмена\",\"[unk]\"]"))
                        else if (useTone) ToneDecoder(tone ?: error("Модель распознавания недоступна."))
                        else VoskDecoder(Recognizer(localModel, 16000f))
                }
                // Replay the start of the utterance captured while the decoder loaded.
                // Keep microphone reads responsive by processing at most 100ms of backlog per frame.
                var ended = false
                repeat(10) {
                    if (!ended && pending.isNotEmpty()) ended = decoder!!.accept(pending.removeFirst()).also { pendingSamples -= 160 }
                }
                if (!ended && !finishRequested) {
                    if (pending.isNotEmpty()) { pending.addLast(clean.copyOf()); pendingSamples += clean.size }
                    else ended = decoder!!.accept(clean)
                }
                val now = SystemClock.elapsedRealtime()
                var text: String? = null
                if (ended) text = decoder!!.result().ifBlank { null }
                if (!ended && now - lastPartial > if (active.interrupt) 80 else 500) {
                    lastPartial = now
                    val phrase = decoder!!.partial()
                    if (active.interrupt) {
                        if (phrase != candidate) { candidate = phrase; candidateSince = now }
                        if (phrase in setOf("стоп", "замолчи", "отмена") && now - candidateSince >= 160) text = phrase
                    } else if (phrase.isNotEmpty()) main.post { if (!closed && generation.get() == active.id) active.partial(phrase) }
                }
                if ((finishRequested || now > until) && pending.isEmpty()) text = decoder!!.finish().ifBlank { null }
                val deliver = if (active.interrupt) text in setOf("стоп", "замолчи", "отмена") else text != null || (pending.isEmpty() && (finishRequested || now > until))
                if (deliver && request.compareAndSet(active, null)) {
                    val result = text
                    main.post { if (!closed && generation.get() == active.id) active.done(result, null) }
                }
            }
        }.exceptionOrNull()?.message
        runCatching { capture?.stop() }; recorder.compareAndSet(capture, null)
        runCatching { suppressor?.release() }; runCatching { hardwareEcho?.release() }
        runCatching { capture?.release() }; runCatching { decoder?.close() }; runCatching { echo?.close() }
        synchronized(control) {
            capturing.set(false)
            // RecognitionService may cancel and immediately start a new request. A stopped
            // old AudioRecord must not consume or fail that newer request.
            val cancelledCapture = !persistent && generation.get() != epoch
            if (error != null && !cancelledCapture) {
                val active = request.getAndSet(null)
                main.post { if (!closed) {
                    if (fatal != null) fatal.invoke(error)
                    else if (active != null && generation.get() == active.id) active.done(null, error)
                } }
            } else if (!closed && request.get() != null && capturing.compareAndSet(false, true)) worker.execute { capture() }
        }
    }
    fun finishUtterance() { finishRequested = true }
    fun cancel() = synchronized(control) {
        generation.incrementAndGet(); request.set(null)
        if (!persistent) runCatching { recorder.get()?.stop() }
    }
    fun close() {
        if (closed) return
        closed = true; cancel(); runCatching { recorder.get()?.stop() }
        worker.execute {
            // Decoder ownership ends before models return to the idle pool. The loader may
            // still be finishing a cold load, so return them on that same serial queue.
            modelLoader.execute { model?.let(WarmSpeechModels.vosk::put); model = null; tone?.let(WarmSpeechModels.tone::put); tone = null }
            modelLoader.shutdown()
        }
        worker.shutdown()
    }
}
