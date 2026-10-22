package local.voice

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ScrollView
import android.widget.TextView

/** Uses the system credential UI. Never handles a PIN or displays a private command. */
class UnlockAppActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var token = ""
    private var requested = false
    private var executing = false
    private lateinit var status: TextView
    private val expired = Runnable { UnlockAppLaunch.cancel(token); status.text = "Запрос истёк. Повторите команду открытия." }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true); setTurnScreenOn(true)
        load()
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        UnlockAppLaunch.cancel(token); setIntent(intent); handler.removeCallbacksAndMessages(null)
        requested = false; executing = false; load()
    }
    private fun load() {
        token = intent.getStringExtra("request_token").orEmpty()
        val ui = SettingsUi(this, AssistantTheme(this, cinematic = true))
        val column = ui.column()
        ui.heading(column, "Разблокируйте телефон", 28f)
        status = ui.text(column, "После системной разблокировки открою выбранное приложение. Запрос действует одну минуту.")
        ui.button(column, "Разблокировать", true) { requested = false; unlock() }
        ui.button(column, "Отмена") { UnlockAppLaunch.cancel(token); finish() }
        setContentView(ScrollView(this).apply { addView(column) })
    }
    override fun onPostResume() {
        super.onPostResume()
        window.decorView.post { if (hasWindowFocus()) unlock() }
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Asking before the showWhenLocked window is actually visible can make
        // Android cancel its own bouncer during the opening transition.
        if (hasFocus && ::status.isInitialized) window.decorView.post { unlock() }
    }
    private fun unlock() {
        if (executing || isFinishing || isDestroyed) return
        val left = UnlockAppLaunch.remaining(token)
        if (left == 0L) { status.text = "Запрос истёк или отменён. Повторите команду."; return }
        handler.removeCallbacks(expired); handler.postDelayed(expired, left)
        if (!UnlockAppLaunch.locked(this)) { complete(); return }
        if (requested) return
        requested = true
        val requestToken = token
        getSystemService(KeyguardManager::class.java).requestDismissKeyguard(this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    if (token != requestToken || isFinishing || isDestroyed) return
                    requested = false; unlock()
                }
                override fun onDismissCancelled() {
                    if (token == requestToken) { UnlockAppLaunch.cancel(token); finish() }
                }
                override fun onDismissError() {
                    if (token == requestToken && !isFinishing) {
                        requested = false
                        status.text = "Android не открыл разблокировку. Нажмите «Разблокировать» ещё раз или отмените запрос."
                    }
                }
            })
    }
    private fun complete() {
        if (executing) return
        executing = true
        val error = UnlockAppLaunch.complete(this, token)
        if (error == null) finish() else {
            // Keep a terminal failure visible; a later focus callback must not
            // replace it with a generic "expired" message or replay the action.
            executing = UnlockAppLaunch.remaining(token) == 0L
            status.text = error
        }
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (!isChangingConfigurations) UnlockAppLaunch.cancel(token)
        super.onDestroy()
    }
}
