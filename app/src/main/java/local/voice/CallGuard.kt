package local.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager

/** Reads only Telecom's ongoing-call boolean while the user invokes a voice session. */
class CallGuard(private val context: Context, private val onCall: () -> Unit) {
    companion object {
        fun hasAccess(context: Context) = context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        fun inCall(context: Context): Boolean? {
            if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return null
            return try { context.getSystemService(TelecomManager::class.java)?.isInCall ?: false }
            catch (_: SecurityException) { null }
            catch (_: RuntimeException) { null }
        }
    }
    private val main = Handler(Looper.getMainLooper())
    private var closed = false
    private var started = false
    private val check = object : Runnable {
        override fun run() {
            if (closed) return
            if (inCall(context) == true) { onCall(); return }
            main.postDelayed(this, 500)
        }
    }
    fun start() { if (!started && !closed) { started = true; check.run() } }
    fun close() { closed = true; main.removeCallbacks(check) }
}
