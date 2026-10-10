package local.voice

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Color

/** Only public session states: never put utterances or private replies on keyguard. */
internal class AssistantStatusView(context: android.content.Context) : LinearLayout(context) {
    private val palette = AssistantTheme(context, cinematic = true)
    private val core = AssistantCoreView(context).apply { speechLevel = { VoiceConversation.activeSpeechLevel } }
    private val message = TextView(context)
    private val helper = TextView(context)
    private val heard = TextView(context)
    private val finishRecording = android.widget.Button(context)
    private val privacyHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var transcriptText = ""
    private var finalTranscript = false
    private var finishingRecording = false
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private val privacyRefresh = object : Runnable {
        override fun run() {
            renderTranscript()
            if (isAttachedToWindow && isShown) privacyHandler.postDelayed(this, 250)
        }
    }
    init {
        orientation = VERTICAL
        gravity = android.view.Gravity.CENTER_HORIZONTAL
        setPadding(dp(24), dp(24), dp(24), dp(24))
        background = AssistantBackdrop()
        layoutParams = android.widget.FrameLayout.LayoutParams(-1, -1)
        setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
            view.setPadding(dp(24) + bars.left, dp(24) + bars.top, dp(24) + bars.right, dp(24) + bars.bottom)
            insets
        }
        addView(TextView(context).apply {
            text = "Ассистент"; textSize = 23f; setTextColor(palette.text)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }, LayoutParams(-1, -2))
        addView(TextView(context).apply {
            text = "ГОЛОСОВОЙ ДИАЛОГ"; textSize = 12f; letterSpacing = .14f
            setTextColor(palette.secondary); setPadding(0, dp(6), 0, 0)
        }, LayoutParams(-1, -2))
        addView(core, LayoutParams(-1, 0, 1f))
        addView(message.apply {
            textSize = 27f; gravity = android.view.Gravity.CENTER; setTextColor(palette.text)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }, LayoutParams(-1, -2))
        addView(helper.apply {
            textSize = 14f; gravity = android.view.Gravity.CENTER; setTextColor(palette.secondary)
            setPadding(0, dp(8), 0, dp(16)); text = "Говорите после восходящего сигнала"
        }, LayoutParams(-1, -2))
        addView(heard.apply {
            textSize = 16f; setTextColor(palette.text); setPadding(dp(16), dp(14), dp(16), dp(14))
            background = palette.shape(palette.surface, dp(20).toFloat(), true)
            maxLines = 4; ellipsize = android.text.TextUtils.TruncateAt.END
        }, LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        addView(finishRecording.apply {
            text = "Закончить запись"; textSize = 16f; isAllCaps = false; isEnabled = false
            setTextColor(palette.onAccent); background = palette.ripple(palette.accent, dp(20).toFloat())
            minHeight = dp(52)
            setOnClickListener { finishingRecording = true; isEnabled = false; VoiceConversation.finishActiveUtterance() }
        }, LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        addView(android.widget.Button(context).apply {
            text = "Остановить помощника"; textSize = 15f; isAllCaps = false; minHeight = dp(48)
            setTextColor(palette.text); background = palette.ripple(palette.surface, dp(18).toFloat())
            setOnClickListener { VoiceConversation.stopActive() }
        }, LayoutParams(-1, -2))
        update("Подключаюсь")
        renderTranscript()
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); privacyHandler.removeCallbacks(privacyRefresh); privacyHandler.post(privacyRefresh) }
    override fun onDetachedFromWindow() { privacyHandler.removeCallbacks(privacyRefresh); super.onDetachedFromWindow() }
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        privacyHandler.removeCallbacks(privacyRefresh)
        if (isVisible) privacyHandler.post(privacyRefresh)
    }
    fun microphoneLevel(rms: Int, silenced: Boolean) {
        core.microphoneLevel(rms, silenced)
        if (silenced && VoiceConversation.activePhase == SessionDiagnostics.Phase.LISTENING) {
            core.mode = AssistantCoreView.Mode.ERROR; helper.text = "Android заглушил микрофон"
        }
    }
    fun transcript(text: String, final: Boolean) {
        transcriptText = text.take(1200); finalTranscript = final
        renderTranscript()
    }
    private fun renderTranscript() {
        val locked = context.getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked
        heard.text = if (locked) "Распознанный текст скрыт на заблокированном экране"
            else if (transcriptText.isBlank()) "Здесь появится распознанная речь"
            else (if (finalTranscript) "Вы сказали:\n" else "Распознаю:\n") + transcriptText
    }
    fun update(text: String) {
        val phase = VoiceConversation.activePhase
        if (text == "Слушаю…") finishingRecording = false
        finishRecording.isEnabled = phase == SessionDiagnostics.Phase.LISTENING && !finishingRecording
        finishRecording.alpha = if (finishRecording.isEnabled) 1f else .4f
        core.mode = when (phase) {
            SessionDiagnostics.Phase.LISTENING -> AssistantCoreView.Mode.LISTENING
            SessionDiagnostics.Phase.THINKING, SessionDiagnostics.Phase.CONTACTS -> AssistantCoreView.Mode.THINKING
            SessionDiagnostics.Phase.SPEAKING -> AssistantCoreView.Mode.SPEAKING
            SessionDiagnostics.Phase.ACTION, SessionDiagnostics.Phase.SERVER -> AssistantCoreView.Mode.WORKING
            SessionDiagnostics.Phase.STOPPED -> AssistantCoreView.Mode.IDLE
            else -> AssistantCoreView.Mode.LOADING
        }
        message.text = when (phase) {
            SessionDiagnostics.Phase.LISTENING -> "Слушаю вас"
            SessionDiagnostics.Phase.THINKING -> "Думаю"
            SessionDiagnostics.Phase.SPEAKING -> "Говорю"
            SessionDiagnostics.Phase.CONTACTS -> "Ищу контакт"
            SessionDiagnostics.Phase.ACTION -> "Выполняю"
            SessionDiagnostics.Phase.SERVER -> "Домашний агент"
            SessionDiagnostics.Phase.STOPPED -> "Разговор завершён"
            else -> "Подключаюсь"
        }
        helper.text = when (phase) {
            SessionDiagnostics.Phase.LISTENING -> "Говорите. Я слушаю."
            SessionDiagnostics.Phase.THINKING -> "Обдумываю ваш запрос"
            SessionDiagnostics.Phase.SPEAKING -> "«Стоп» или кнопка прервёт ответ"
            SessionDiagnostics.Phase.CONTACTS -> "Проверяю контакты"
            SessionDiagnostics.Phase.ACTION -> "Выполняю вашу команду"
            SessionDiagnostics.Phase.SERVER -> "Жду ответ домашнего агента"
            SessionDiagnostics.Phase.STOPPED -> "Можно начать новый разговор"
            else -> "Готовлю голосовой канал"
        }
        val error = text.startsWith("Гарнитура") || text.startsWith("Выбранная гарнитура") ||
            text.startsWith("Не удалось") || text.startsWith("Разрешите") || text.startsWith("Откройте приложение") ||
            text.startsWith("Звуковой канал") || text.startsWith("Речь не распознана")
        if (error) { core.mode = AssistantCoreView.Mode.ERROR; message.text = "Нужна ваша помощь"; helper.text = text }
    }
}

object AssistantHealth { var message: String = "Системная сессия ещё не запускалась." }

class AssistantService : VoiceInteractionService() {
    private var destroyed = false
    companion object {
        private var current: AssistantService? = null
        fun invoke(): Boolean {
            if (VoiceConversation.stopActive()) return true
            val service = current ?: return false
            service.showSession(Bundle(), VoiceInteractionSession.SHOW_SOURCE_APPLICATION)
            return true
        }
    }
    override fun onReady() {
        super.onReady()
        if (!destroyed) {
            current = this
            HomeLink.attach(applicationContext)
            // Native models only: no microphone, SCO link or ongoing foreground audio.
            WarmSpeechModels.preload(applicationContext)
        }
    }
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) { WarmSpeechModels.trim(); LocalBrain.trimIdle() }
    }
    override fun onShutdown() { if (current === this) { current = null; HomeLink.detach(); VoiceConversation.stopActive() }; super.onShutdown() }
    override fun onDestroy() { destroyed = true; if (current === this) { current = null; HomeLink.detach() }; super.onDestroy() }
    override fun onLaunchVoiceAssistFromKeyguard() {
        if (VoiceConversation.stopActive()) return
        startActivity(Intent(this, VoiceCommandActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

class AssistantSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AssistantSession(this)
}

class AssistantSession(context: android.content.Context) : VoiceInteractionSession(context) {
    private var conversation: VoiceConversation? = null
    private var panel: AssistantStatusView? = null
    private var listening = false
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    override fun onCreateContentView(): View = AssistantStatusView(context).also { panel = it }
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val ownsActive = conversation?.ownsActiveConversation == true
        if (VoiceConversation.stopActive()) {
            // Our current conversation finishes after its descending cue. Finishing
            // its session here would destroy that owner and cut Bluetooth playback.
            if (!ownsActive) finish()
            return
        }
        listening = false
        conversation = VoiceConversation(context, {
            AssistantHealth.message = it
            panel?.update(it)
            if (it == "Слушаю…") listening = true
        }, { if (listening) finish() else main.postDelayed({ finish() }, 3000) }, releaseScreen = { hide() }, transcript = { text, final -> panel?.transcript(text, final) }, microphoneLevel = { rms, silenced -> panel?.microphoneLevel(rms, silenced) }).also { it.start() }
    }
    // Android hides the session overlay on keyguard/screen-off. Audio is owned by the
    // user-invoked foreground conversation, not by the visibility of this overlay.
    override fun onHide() { super.onHide() }
    override fun onDestroy() { main.removeCallbacksAndMessages(null); conversation?.close(); conversation = null; super.onDestroy() }
}

class VoiceCommandActivity : Activity() {
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var entryGeneration = 0
    private lateinit var panel: AssistantStatusView
    private val invoke = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            if (VoiceConversation.stopActive()) { finish(); return }
            val role = getSystemService(android.app.role.RoleManager::class.java)
            if (role.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT)) {
                // This user-visible entry can start capture without waiting for a second
                // system window/service bind. Native assist sessions keep their own entry.
                val activity = java.lang.ref.WeakReference(this@VoiceCommandActivity)
                val handler = main
                val ticket = entryGeneration
                VoiceConversation(applicationContext, { text ->
                    AssistantHealth.message = text
                    activity.get()?.panel?.update(text)
                }, { handler.postDelayed({ activity.get()?.takeIf { it.entryGeneration == ticket }?.finish() }, 3000) }, releaseScreen = { activity.get()?.finish() }, transcript = { text, final -> activity.get()?.panel?.transcript(text, final) }, microphoneLevel = { rms, silenced -> activity.get()?.panel?.microphoneLevel(rms, silenced) }).start()
            } else {
                startActivity(Intent(this@VoiceCommandActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                finish()
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        panel = AssistantStatusView(this)
        setContentView(panel)
        window.statusBarColor = android.graphics.Color.rgb(6, 13, 22)
        window.navigationBarColor = android.graphics.Color.rgb(6, 13, 22)
        window.insetsController?.setSystemBarsAppearance(0, android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        main.post(invoke)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        entryGeneration++
        main.removeCallbacks(invoke); main.post(invoke)
    }
    override fun onDestroy() { main.removeCallbacksAndMessages(null); super.onDestroy() }
}
