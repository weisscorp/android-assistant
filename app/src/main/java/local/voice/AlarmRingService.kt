package local.voice

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.*
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.os.PowerManager

/** Alarm audio and a bounded, local stop listener exist only for fired user clocks. */
class AlarmRingService : Service() {
    private var ringtone: Ringtone? = null
    private var focus: AudioFocusRequest? = null
    private var speech: OfflineSpeech? = null
    private var wake: PowerManager.WakeLock? = null
    private var voiceAttempt = false
    private var voiceEpoch = 0
    private val ids = linkedSetOf<Long>()
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { finishRing() }
    // Brief quieter intervals make a spoken stop audible over the alarm itself.
    private var quiet = false
    private val pulse = object : Runnable {
        override fun run() {
            if (active !== this@AlarmRingService) return
            quiet = !quiet
            ringtone?.volume = if (quiet) 0.15f else 1f
            handler.postDelayed(this, if (quiet) 1500 else 2500)
        }
    }
    companion object {
        const val NOTIFICATION_ID = 7001
        private const val MAX_RING_MS = 10 * 60_000L
        @Volatile private var active: AlarmRingService? = null
        internal val ringing: Boolean get() = active != null
        @Volatile internal var ringingIds: Set<Long> = emptySet(); private set
        @Volatile internal var voiceStatus = ""; private set
        internal val listening: Boolean get() = active?.speech != null && voiceStatus == "Слушаю «стоп»"
        internal val soundPlaying: Boolean get() = active?.ringtone?.isPlaying == true
        fun dismiss(context: Context, id: Long) {
            active?.let { service -> service.handler.post { service.remove(id) } }
        }
        internal fun retryVoice() { active?.let { service -> service.handler.post { service.startVoice() } } }
        /** Native stop recognition and tests use the same entry point; no exported intent. */
        internal fun recognizedStop(text: String) {
            if (text.trim().lowercase() !in setOf("стоп", "замолчи", "отмена")) return
            active?.let { service -> service.handler.post {
                if (active === service) service.ids.toList().forEach { PhoneClocks.cancel(service, it) }
            } }
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = synchronized(PhoneClocks) {
        val entry = ClockStore(this).use { it.get(intent?.getLongExtra("clock_id", -1) ?: -1) }
        if (entry == null || entry.state != "fired" || entry.kind !in setOf(ClockKind.ALARM, ClockKind.TIMER)) {
            if (ids.isEmpty()) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!ids.add(entry.id)) return START_NOT_STICKY
        active = this; ringingIds = ids.toSet()
        try {
            startForeground(NOTIFICATION_ID, PhoneClocks.notification(this, entry, true),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } catch (_: Exception) {
            PhoneClocks.notify(this, entry); finishRing(); return START_NOT_STICKY
        }
        if (wake == null) wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "local.voice:clock-ring").apply {
                setReferenceCounted(false)
            }
        wake?.acquire(MAX_RING_MS + 1000)
        handler.removeCallbacks(timeout); handler.postDelayed(timeout, MAX_RING_MS)
        VoiceConversation.interruptForClock()
        if (ringtone == null) startAudio()
        startVoice()
        return START_NOT_STICKY
    }
    private fun current() = ids.firstOrNull()?.let { id -> ClockStore(this).use { it.get(id) } }
    private fun updateNotification() {
        current()?.let { getSystemService(android.app.NotificationManager::class.java)
            .notify(NOTIFICATION_ID, PhoneClocks.notification(this, it, true)) }
    }
    private fun startAudio() {
        val audio = getSystemService(AudioManager::class.java)
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) finishRing()
            }.build()
        if (audio.requestAudioFocus(focus!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            runCatching {
                ringtone = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
                    ?.also { it.audioAttributes = attributes; it.isLooping = true; it.play() }
            }
        }
    }
    private fun setVoiceStatus(value: String) { voiceStatus = value; updateNotification() }
    private fun startVoice() {
        if (active !== this || speech != null || voiceAttempt) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            setVoiceStatus("Для команды «стоп» разрешите микрофон"); return
        }
        if (CallGuard.inCall(this) == true) { setVoiceStatus("Идёт звонок · остановите кнопкой"); return }
        voiceAttempt = true
        // A closing conversation releases its PCM owner asynchronously. Do not create
        // a competing capture, including while a note or another recorder owns audio.
        val until = android.os.SystemClock.elapsedRealtime() + 2000
        fun beginWhenFree() {
            if (active !== this) { voiceAttempt = false; return }
            if (getSystemService(AudioManager::class.java).activeRecordingConfigurations.isNotEmpty()) {
                if (android.os.SystemClock.elapsedRealtime() < until) handler.postDelayed({ beginWhenFree() }, 50)
                else { voiceAttempt = false; setVoiceStatus("Микрофон занят · остановите кнопкой") }
                return
            }
            voiceAttempt = false
            try {
                val entry = current() ?: return
                startForeground(NOTIFICATION_ID, PhoneClocks.notification(this, entry, true),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } catch (_: Exception) {
                setVoiceStatus("Откройте сигнал для команды «стоп»"); return
            }
            setVoiceStatus("Включаю команду «стоп»…")
            val epoch = ++voiceEpoch
            speech = OfflineSpeech(this, phoneMicrophone = true, interruptionOnly = true, fatal = {
                if (epoch == voiceEpoch && active === this) {
                    speech?.close(); speech = null
                    handler.removeCallbacks(pulse); ringtone?.volume = 1f
                    setVoiceStatus("Микрофон недоступен · остановите кнопкой")
                }
            }).also { listener ->
                listener.interrupt(false, ready = {
                    if (active === this && epoch == voiceEpoch) {
                        setVoiceStatus("Слушаю «стоп»")
                        handler.removeCallbacks(pulse); quiet = false; handler.postDelayed(pulse, 2500)
                    }
                }, stopped = { if (active === this && epoch == voiceEpoch) recognizedStop("стоп") })
            }
        }
        beginWhenFree()
    }
    private fun remove(id: Long) {
        if (!ids.remove(id)) return
        ringingIds = ids.toSet()
        if (ids.isEmpty()) finishRing() else updateNotification()
    }
    private fun finishRing() {
        if (active === this) { active = null; ringingIds = emptySet(); voiceStatus = "" }
        ids.clear(); voiceEpoch++; handler.removeCallbacksAndMessages(null)
        speech?.close(); speech = null
        ringtone?.stop(); ringtone = null
        focus?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }; focus = null
        wake?.let { if (it.isHeld) it.release() }; wake = null
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onDestroy() { finishRing(); super.onDestroy() }
}
