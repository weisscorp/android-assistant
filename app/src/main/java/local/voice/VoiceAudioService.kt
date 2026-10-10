package local.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/** Foreground lifetime only while a user-invoked conversation owns audio. */
object AudioForeground {
    private data class Lease(val stop: () -> Unit, var ready: ((String?) -> Unit)?)
    private val leases = mutableMapOf<Int, Lease>()
    private val main = Handler(Looper.getMainLooper())
    private var counter = 0
    private var running = false
    private var owner: VoiceAudioService? = null
    private var idleStopping = false
    private var pendingStop: Runnable? = null
    fun acquire(context: Context, stop: () -> Unit, ready: (String?) -> Unit): Int {
        pendingStop?.let(main::removeCallbacks); pendingStop = null
        val id = ++counter
        leases[id] = Lease(stop, ready)
        try {
            context.startForegroundService(Intent(context, VoiceAudioService::class.java))
            if (running && !idleStopping) main.post { if (running && !idleStopping) deliver(id, null) }
        } catch (_: Exception) { main.post { deliver(id, "Android не разрешил голосовую сессию. Выберите приложение системным ассистентом и проверьте разрешения.") } }
        return id
    }
    private fun deliver(id: Int, error: String?) { leases[id]?.let { val callback = it.ready; it.ready = null; callback?.invoke(error) } }
    fun started(service: VoiceAudioService) { owner = service; running = true; idleStopping = false; leases.keys.toList().forEach { deliver(it, null) } }
    fun failed() { running = false; leases.keys.toList().forEach { deliver(it, "Не удалось запустить голосовой сервис.") } }
    fun release(context: Context, id: Int) {
        leases.remove(id)
        if (leases.isEmpty()) pendingStop = Runnable {
            if (leases.isEmpty()) { idleStopping = true; context.stopService(Intent(context, VoiceAudioService::class.java)) }
        }.also { main.postDelayed(it, 300) }
    }
    fun stopAll() { leases.values.toList().forEach { it.stop() } }
    fun destroyed(service: VoiceAudioService) {
        if (owner !== service) return
        owner = null; running = false
        if (idleStopping && leases.isNotEmpty()) {
            // A new headset invocation arrived between stopService and onDestroy.
            // Wait for the replacement foreground service instead of cancelling its lease.
            runCatching { service.applicationContext.startForegroundService(Intent(service.applicationContext, VoiceAudioService::class.java)) }
                .onFailure { failed() }
        } else { idleStopping = false; stopAll() }
    }
}

class VoiceAudioService : Service() {
    private var wake: PowerManager.WakeLock? = null
    private val main = Handler(Looper.getMainLooper())
    private val maximumSession = Runnable { AudioForeground.stopAll(); stopSelf() }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { AudioForeground.stopAll(); stopSelf(); return START_NOT_STICKY }
        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("conversation", "Голосовой разговор", NotificationManager.IMPORTANCE_LOW))
            val stop = PendingIntent.getService(this, 0, Intent(this, VoiceAudioService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notification = Notification.Builder(this, "conversation").setSmallIcon(local.voice.R.drawable.ic_assistant)
                .setContentTitle("Ассистент · голосовой разговор").setContentText("Локальное распознавание и озвучка")
                .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "Остановить", stop).build()).build()
            startForeground(100, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            if (wake == null) {
                wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "local.voice:conversation").apply {
                    setReferenceCounted(false)
                    acquire(10 * 60_000L + 1_000L)
                }
                main.postDelayed(maximumSession, 10 * 60_000L)
            }
            AudioForeground.started(this)
        } catch (_: Exception) { AudioForeground.failed(); stopSelf() }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        main.removeCallbacks(maximumSession)
        wake?.let { if (it.isHeld) it.release() }; wake = null
        AudioForeground.destroyed(this); super.onDestroy()
    }
}
