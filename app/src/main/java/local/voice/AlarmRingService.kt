package local.voice

import android.app.Service
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.*
import android.os.Handler
import android.os.Looper
import android.os.IBinder

/** Rings only an explicit user alarm. Never overrides silent mode or Do Not Disturb. */
class AlarmRingService : Service() {
    private var ringtone: Ringtone? = null
    private var focus: AudioFocusRequest? = null
    private var id = -1L
    private val handler=Handler(Looper.getMainLooper())
    private val timeout=Runnable { finishRing() }
    companion object {
        @Volatile private var active: AlarmRingService? = null
        fun dismiss(context: Context, id: Long) { active?.let { if(it.id==id) it.handler.post { it.finishRing() } } }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = synchronized(PhoneClocks) {
        val entry=ClockStore(this).use { it.get(intent?.getLongExtra("clock_id",-1) ?: -1) }
        if(entry == null || entry.state!="fired" || entry.kind!=ClockKind.ALARM) { stopSelf(); return START_NOT_STICKY }
        stopAudio(); handler.removeCallbacks(timeout)
        id=entry.id; active=this
        try { startForeground(7001,PhoneClocks.notification(this,entry,true),ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) }
        catch(e: Exception) { PhoneClocks.notify(this,entry); stopSelf(); return START_NOT_STICKY }
        val audio=getSystemService(AudioManager::class.java)
        val notifications=getSystemService(NotificationManager::class.java)
        if(audio.ringerMode==AudioManager.RINGER_MODE_NORMAL && notifications.currentInterruptionFilter==NotificationManager.INTERRUPTION_FILTER_ALL) {
            val attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes).setOnAudioFocusChangeListener { change ->
                if(change==AudioManager.AUDIOFOCUS_LOSS || change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) finishRing()
            }.build()
            if(audio.requestAudioFocus(focus!!)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                runCatching { RingtoneManager.getRingtone(this,RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)).also {
                    ringtone=it; it.audioAttributes=attributes; it.isLooping=true; it.play()
                } }
            }
        }
        handler.postDelayed(timeout,60_000)
        return START_NOT_STICKY
    }
    private fun stopAudio() {
        ringtone?.stop(); ringtone=null
        focus?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }; focus=null
    }
    private fun finishRing() { stopAudio(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    override fun onDestroy() { handler.removeCallbacks(timeout); stopAudio(); if(active===this) active=null; super.onDestroy() }
}
