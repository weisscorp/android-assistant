package local.voice

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import local.voice.SessionDiagnostics.Phase

/** The same conversation engine, presented as a living voice console with an ordinary text composer. */
class MainActivity : Activity() {
    private lateinit var palette: AssistantTheme
    private lateinit var core: AudioSignalView
    private lateinit var messages: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var state: TextView
    private lateinit var caption: TextView
    private lateinit var input: EditText
    private lateinit var mic: Button
    private lateinit var voiceDock: LinearLayout
    private lateinit var stop: ImageButton
    private lateinit var send: ImageButton
    private var conversation: VoiceConversation? = null
    private var textMode = false
    private var voiceBubble: TextView? = null
    private lateinit var notesButton: Button
    private var busy = false
    private var keyboard = false
    private val landscape get() = resources.configuration.screenWidthDp > resources.configuration.screenHeightDp
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun text(value: String, size: Float, color: Int) = TextView(this).apply { text = value; textSize = size; setTextColor(color) }
    private fun label(value: String) = text(value, 12f, palette.secondary).apply { letterSpacing = .14f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HomeLink.kick(applicationContext)
        palette = AssistantTheme(this, cinematic = true)
        window.statusBarColor = palette.background; window.navigationBarColor = palette.background
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = AssistantBackdrop() }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(12)) }
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(text("Ассистент", 24f, palette.text).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setPadding(0, dp(4), 0, 0) })
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(icon(R.drawable.ic_settings, "Настройки", palette.surface, palette.secondary).apply {
            setOnClickListener {
                conversation?.close(); reset()
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(header)
        notesButton = Button(this).apply {
            id = R.id.assistant_audio_notes; text = "Аудиозаметки"; isAllCaps = false
            setTextColor(palette.text); background = palette.ripple(palette.surface, dp(18).toFloat())
            minHeight = dp(56); textSize = 14f
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { conversation?.close(); reset(); startActivity(Intent(this@MainActivity, AudioNotesActivity::class.java)) }
        }
        val stage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        core = AudioSignalView(this).apply { id = R.id.assistant_core; spectrum = { VoiceConversation.activeAudioSpectrum } }
        val compact = resources.configuration.screenHeightDp < 640 || resources.configuration.fontScale > 1.3f
        stage.addView(core, LinearLayout.LayoutParams(-1, if (landscape) 0 else dp(if (compact) 96 else 140), if (landscape) 1f else 0f))
        state = text("", if (compact) 23f else 28f, palette.text).apply {
            visibility = View.GONE
            gravity = Gravity.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        stage.addView(state, LinearLayout.LayoutParams(-1, -2))
        caption = text("", 14f, palette.secondary).apply { visibility = View.GONE; gravity = Gravity.CENTER; setPadding(dp(8), dp(6), dp(8), dp(12)) }
        stage.addView(caption, LinearLayout.LayoutParams(-1, -2))
        val chat = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        messages = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, dp(8)) }
        scroll = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(messages) }
        chat.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        voiceDock = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, dp(10)) }
        mic = Button(this).apply {
            id = R.id.assistant_mic; text = "Говорить"; contentDescription = "Говорить"; textSize = 17f; isAllCaps = false
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setTextColor(palette.onAccent)
            background = palette.ripple(palette.accent, dp(22).toFloat()); minHeight = dp(56)
            setPadding(dp(20), 0, dp(20), 0)
            compoundDrawablePadding = dp(12); setMicIcon(false)
            setOnClickListener {
                val current = conversation
                if (current != null && !textMode) {
                    if (current.listening) current.finishUtterance() else VoiceConversation.stopActive()
                } else beginVoice()
            }
        }
        voiceDock.addView(mic, LinearLayout.LayoutParams(0, -2, 1f))
        voiceDock.addView(notesButton, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
        stop = icon(R.drawable.ic_stop, "Остановить помощника", palette.surface, palette.text).apply {
            id = R.id.assistant_stop
            setOnClickListener { VoiceConversation.stopActive(); reset() }; visibility = View.GONE
        }
        voiceDock.addView(stop, LinearLayout.LayoutParams(dp(56), dp(56)).apply { leftMargin = dp(8) })
        val composerLabel = label("").apply { visibility = View.GONE; setPadding(0, dp(10), 0, dp(8)) }
        chat.addView(composerLabel)
        val composer = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; background = palette.shape(palette.surface, dp(22).toFloat(), true)
            setPadding(dp(16), dp(4), dp(4), dp(4))
        }
        input = EditText(this).apply {
            id = R.id.assistant_message; hint = "Напишите мне…"; contentDescription = "Сообщение ассистенту"; setTextColor(palette.text); setHintTextColor(palette.secondary)
            textSize = 16f; background = null; minHeight = dp(48); maxLines = 3
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_SEND) { submitText(input.text.toString()); true } else false }
        }
        composerLabel.labelFor = input.id
        composer.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        send = icon(R.drawable.ic_send, "Отправить сообщение", palette.accent, palette.onAccent).apply {
            id = R.id.assistant_send; setOnClickListener { submitText(input.text.toString()) }
        }
        composer.addView(send, LinearLayout.LayoutParams(dp(48), dp(48)))
        if (landscape) {
            val body = LinearLayout(this)
            stage.addView(voiceDock)
            body.addView(stage, LinearLayout.LayoutParams(0, -1, .45f).apply { rightMargin = dp(24) })
            body.addView(chat, LinearLayout.LayoutParams(0, -1, .55f))
            root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            root.addView(stage)
            root.addView(chat, LinearLayout.LayoutParams(-1, 0, 1f))
            // Keep the voice action above the composer, within the same stable dock.
            chat.addView(voiceDock, chat.indexOfChild(composerLabel))
        }
        chat.addView(composer)
        val gutter = if (!landscape && resources.configuration.screenWidthDp >= 600) ((resources.configuration.screenWidthDp - 600) / 2).coerceAtLeast(24) else 24
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            keyboard = insets.isVisible(WindowInsets.Type.ime())
            view.setPadding(dp(gutter) + bars.left, bars.top + dp(8), dp(gutter) + bars.right, maxOf(bars.bottom, ime.bottom) + dp(12))
            header.visibility = if (keyboard) View.GONE else View.VISIBLE
            if (!landscape) core.visibility = if (keyboard) View.GONE else View.VISIBLE
            voiceDock.visibility = if (keyboard && (conversation == null || textMode)) View.GONE else View.VISIBLE
            insets
        }
        setContentView(root); root.requestApplyInsets()
        window.insetsController?.setSystemBarsAppearance(0, android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        root.viewTreeObserver.addOnGlobalLayoutListener {
            if (!landscape && !keyboard) {
                val available = root.height - root.paddingTop - root.paddingBottom - header.height - (if (state.visibility == View.VISIBLE) state.height else 0) - (if (caption.visibility == View.VISIBLE) caption.height else 0) -
                    voiceDock.height - composerLabel.height - composer.height
                val chatReserve = if (resources.configuration.screenHeightDp < 560) 0 else dp(120)
                val maximum = dp(if (compact) 96 else 140)
                val height = (available - chatReserve).coerceIn(0, maximum)
                val visibility = if (height >= dp(48)) View.VISIBLE else View.GONE
                if (core.visibility != visibility) core.visibility = visibility
                if (core.layoutParams.height != height) core.layoutParams = core.layoutParams.apply { this.height = height }
            }
        }
    }
    private fun Button.setMicIcon(stopping: Boolean) {
        val drawable = getDrawable(if (stopping) R.drawable.ic_stop else R.drawable.ic_microphone)!!.mutate()
        drawable.setTint(palette.onAccent); drawable.setBounds(0, 0, dp(24), dp(24))
        setCompoundDrawables(drawable, null, null, null)
    }
    private fun icon(drawable: Int, label: String, color: Int, tint: Int) = ImageButton(this).apply {
        contentDescription = label; setImageResource(drawable); imageTintList = android.content.res.ColorStateList.valueOf(tint)
        background = palette.ripple(color, dp(18).toFloat()); setPadding(dp(13), dp(13), dp(13), dp(13))
    }
    private fun bubble(value: String, own: Boolean): TextView {
        if (!landscape && core.layoutParams.height > dp(156)) {
            core.layoutParams = core.layoutParams.apply { height = dp(156) }
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(if (own) dp(24) else 0, 0, if (own) 0 else dp(24), 0) }
        row.addView(label(if (own) "ВЫ" else "АССИСТЕНТ").apply { gravity = if (own) Gravity.END else Gravity.START; setPadding(dp(4), 0, dp(4), dp(6)) })
        val valueView = text(value, 17f, palette.text).apply {
            setTextIsSelectable(true); setPadding(dp(16), dp(14), dp(16), dp(14))
            background = palette.shape(if (own) palette.user else palette.surface, dp(20).toFloat(), true)
        }
        row.addView(valueView, LinearLayout.LayoutParams(-1, -2))
        messages.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        return valueView
    }
    private fun session(typed: Boolean): VoiceConversation {
        conversation?.close(); VoiceConversation.stopActive(); textMode = typed
        return VoiceConversation(this, { status ->
            val phase = VoiceConversation.activePhase
            renderState(phase, status, typed)
            busy = phase != Phase.LISTENING
            send.isEnabled = !busy; send.alpha = if (busy) .45f else 1f
            stop.visibility = if (!typed || busy) View.VISIBLE else View.GONE
            mic.text = if (!typed) { if (phase == Phase.LISTENING) "Закончить запись" else "Остановить" } else "Говорить"
            mic.contentDescription = mic.text; mic.setMicIcon(!typed)
            notesButton.visibility = if (typed) View.VISIBLE else View.GONE
            if (status == "Слушаю…") voiceBubble = null
        }, { reset() }, transcript = { words, _ ->
            if (voiceBubble == null) voiceBubble = bubble(words, true) else voiceBubble?.text = words
        }, speechSample = { id, words ->
            val target=voiceBubble
            val row=target?.parent as? LinearLayout
            row?.addView(Button(this).apply {
                text="Исправить распознавание";isAllCaps=false;setTextColor(palette.text);minHeight=dp(48)
                background=palette.ripple(palette.surface,0f)
                setOnClickListener { SpeechDatasetActivity.correct(this@MainActivity,id) { corrected -> target?.takeIf {it.parent===row}?.text=corrected } }
            })
        }, textOnly = typed, replyObserver = { reply -> bubble(reply, false) }, microphoneLevel = { rms, silenced ->
                if (VoiceConversation.activePhase == Phase.LISTENING) {
                core.mode = if (silenced) AudioSignalView.Mode.ERROR else AudioSignalView.Mode.LISTENING
                if (silenced) { caption.text = "Android заглушил микрофон. Проверьте разрешения."; caption.visibility = View.VISIBLE }
            }
        }).also { conversation = it; it.start() }
    }
    private fun renderState(phase: Phase?, status: String, typed: Boolean) {
        core.mode = when (phase) {
            Phase.LISTENING -> if (typed) AudioSignalView.Mode.IDLE else AudioSignalView.Mode.LISTENING
            Phase.THINKING, Phase.CONTACTS -> AudioSignalView.Mode.THINKING
            Phase.SPEAKING -> if (typed) AudioSignalView.Mode.THINKING else AudioSignalView.Mode.SPEAKING
            Phase.ACTION, Phase.SERVER -> AudioSignalView.Mode.WORKING
            Phase.STARTING, Phase.ROUTING, Phase.LOADING -> AudioSignalView.Mode.LOADING
            else -> AudioSignalView.Mode.IDLE
        }
        state.visibility = View.GONE; caption.visibility = View.GONE
        if (status.startsWith("Не удалось") || status.startsWith("Разрешите") || status.startsWith("Откройте приложение") || status.startsWith("Речь не распознана")) {
            core.mode = AudioSignalView.Mode.ERROR; state.text = "Нужна ваша помощь"; caption.text = status
            state.visibility = View.VISIBLE; caption.visibility = View.VISIBLE
        }
    }
    private fun beginVoice() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS), 100); return
        }
        input.clearFocus(); getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(input.windowToken, 0)
        session(false)
    }
    private fun submitText(raw: String) {
        val value = raw.trim().take(600)
        if (value.isEmpty() || busy) return
        input.setText(""); bubble(value, true)
        val active = if (conversation == null || !textMode) session(true) else conversation!!
        active.respond(value)
    }
    private fun reset() {
        conversation = null; busy = false
        core.mode = if (state.text == "Нужна ваша помощь") AudioSignalView.Mode.ERROR else AudioSignalView.Mode.IDLE
        // Preserve a visible failure until the next interaction instead of erasing it on close.
        if (state.text != "Нужна ваша помощь") { state.text = ""; caption.text = ""; state.visibility = View.GONE; caption.visibility = View.GONE }
        notesButton.visibility = View.VISIBLE
        send.isEnabled = true; send.alpha = 1f; stop.visibility = View.GONE
        mic.text = "Говорить"; mic.contentDescription = "Говорить"; mic.setMicIcon(false)
        voiceDock.visibility = if (keyboard) View.GONE else View.VISIBLE
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == 100 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) beginVoice()
        else { state.visibility = View.VISIBLE; caption.visibility = View.VISIBLE; state.text = "Нужен доступ к микрофону"; caption.text = "Для голоса разрешите микрофон. Можно написать сообщение."; core.mode = AudioSignalView.Mode.ERROR }
    }
    // The foreground audio service owns capture while another app or keyguard covers this UI.
    // Explicit exit/settings and destruction still release this Activity's conversation.
    override fun onStop() {
        if (isFinishing) { conversation?.close(); reset() }
        super.onStop()
    }
    override fun onDestroy() { conversation?.close(); super.onDestroy() }
}
