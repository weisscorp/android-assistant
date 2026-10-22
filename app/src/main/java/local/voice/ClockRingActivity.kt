package local.voice

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.*

/** A clock-specific alarm surface; it never dismisses the keyguard or starts a conversation. */
class ClockRingActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var id = -1L
    private lateinit var status: TextView
    private val tick = object : Runnable {
        override fun run() {
            val entry = ClockStore(this@ClockRingActivity).use { it.get(id) }
            if (entry == null || entry.state != "fired" || id !in AlarmRingService.ringingIds) { finish(); return }
            status.text = AlarmRingService.voiceStatus
            handler.postDelayed(this, 250)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true); setTurnScreenOn(true)
        volumeControlStream = android.media.AudioManager.STREAM_ALARM
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        render()
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); render() }
    private fun render() {
        id = intent.getLongExtra("clock_id", -1)
        val entry = ClockStore(this).use { it.get(id) }
        if (entry == null || entry.state != "fired") { finish(); return }
        val palette = AssistantTheme(this, cinematic = true)
        val ui = SettingsUi(this, palette)
        val column = ui.column()
        ui.heading(column, if (entry.kind == ClockKind.TIMER) "Таймер завершён" else "Будильник", 32f)
        ui.heading(column, entry.title, 26f)
        status = ui.text(column, "", 20f)
        ui.button(column, "Остановить", true) { PhoneClocks.cancel(this, id); finish() }
        ui.button(column, "Разрешить остановку голосом") {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 41)
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(palette.background); addView(column) })
    }
    override fun onPostResume() { super.onPostResume(); AlarmRingService.retryVoice(); handler.removeCallbacks(tick); handler.post(tick) }
    override fun onPause() { handler.removeCallbacks(tick); super.onPause() }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }
    @Deprecated("Platform permission callback")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 41) AlarmRingService.retryVoice()
    }
}
