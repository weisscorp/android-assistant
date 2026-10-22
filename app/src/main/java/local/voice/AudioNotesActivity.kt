package local.voice

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.*
import android.text.TextWatcher
import android.text.Editable
import java.text.DateFormat
import java.util.Date

/** A quiet library. Opening it never starts capture or playback. */
class AudioNotesActivity : Activity() {
    private lateinit var list: LinearLayout
    private lateinit var state: TextView
    private lateinit var record: Button
    private lateinit var search: EditText
    private lateinit var palette: AssistantTheme
    private var capture: AudioNoteCapture? = null
    private var player: MediaPlayer? = null
    private val main = Handler(Looper.getMainLooper())
    private var fingerprint = ""
    private val refresh = object : Runnable { override fun run() { render(); main.postDelayed(this, 1000) } }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun Button.style(primary: Boolean = false) {
        isAllCaps=false; setTextColor(if(primary) palette.onAccent else palette.text)
        background=palette.ripple(if(primary) palette.accent else palette.surface,dp(18).toFloat())
        minHeight=dp(48)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VoiceConversation.stopActive()
        palette = AssistantTheme(this, cinematic = true)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(40), dp(20), dp(24)); setBackgroundColor(palette.background) }
        root.addView(Button(this).apply { text = "← Ассистент"; style(); setOnClickListener { finish() } })
        root.addView(TextView(this).apply { text = "Аудиозаметки"; textSize = 28f; setTextColor(palette.text) })
        state = TextView(this).apply { text = "Запишите мысль. Звук и текст сохранятся отдельной заметкой."; setTextColor(palette.secondary); setPadding(0,dp(12),0,dp(12)) }
        root.addView(state)
        record = Button(this).apply {
            id = R.id.audio_note_record; text = "Записать заметку"; style(true); minHeight = dp(56)
            setOnClickListener { if (capture != null) { isEnabled = false; text = "Сохраняю…"; capture?.stop() } else begin() }
        }
        root.addView(record)
        root.addView(Button(this).apply {text="Долгая диктовка для обучения";style();setOnClickListener {startActivity(android.content.Intent(this@AudioNotesActivity,SpeechDatasetActivity::class.java))}})
        root.addView(Button(this).apply { text = "Синхронизировать с хабом"; style(); setOnClickListener { HomeLink.kick(this@AudioNotesActivity); state.text = HomeLink.status(this@AudioNotesActivity) } })
        search = EditText(this).apply { id = R.id.audio_note_search; hint = "Поиск по тексту заметок"; setTextColor(palette.text); setHintTextColor(palette.secondary); maxLines = 1 }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { fingerprint = ""; render() }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        root.addView(search)
        list = LinearLayout(this).apply { id = R.id.audio_note_list; orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime())
            view.setPadding(dp(20)+bars.left, dp(12)+bars.top, dp(20)+bars.right, dp(12)+bars.bottom); insets
        }
        setContentView(root); root.requestApplyInsets(); HomeLink.kick(this)
    }
    private fun begin() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 201); return
        }
        stopPlayback(); record.text = "Сохранить запись"; state.text = "Открываю микрофон…"
        capture = AudioNoteCapture(this) { state.text = it }.also { session ->
            session.finished = { if (capture === session) { capture = null; record.text = "Записать заметку"; record.isEnabled = true; fingerprint = ""; render() } }
            session.start()
        }
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == 201 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) begin()
        else state.text = "Для записи разрешите доступ к микрофону."
    }
    private fun render() {
        if (!::list.isInitialized) return
        val notes = runCatching { AudioNotes(this).all() }.getOrElse { state.text = "Не удалось прочитать заметки: ${it.message}"; return }
        val stamp = notes.toString() + HomeLink.status(this)
        if (fingerprint == stamp) return
        fingerprint = stamp; list.removeAllViews()
        val query = search.text.toString().trim()
        val prefs = getSharedPreferences("home_link", 0)
        notes.filter { (it.getString("title") + " " + it.getString("text")).contains(query, true) }.forEach { note ->
            val id = note.getString("id")
            val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16),dp(16),dp(16),dp(16)); background = palette.shape(palette.surface,dp(18).toFloat(),true) }
            card.addView(TextView(this).apply { text = note.getString("title"); textSize = 18f; setTextColor(palette.text) })
            val status = when (note.getString("status")) { "recording" -> "Записывается"; "pending" -> "Распознаётся"; "error" -> "Не удалось распознать; звук сохранён"; else -> "Текст готов" }
            val synced = note.has("device") || prefs.getLong("audio_note_$id",0) >= note.getLong("revision")
            card.addView(TextView(this).apply { text = "${DateFormat.getDateTimeInstance().format(Date(note.getLong("createdAt")))} · ${note.getLong("durationMs")/1000} с\n$status · ${if(synced) "В хабе" else "На телефоне · ожидает синхронизации"}"; setTextColor(palette.secondary) })
            val transcript = note.getString("text")
            card.addView(TextView(this).apply { text = transcript.ifBlank { if (note.getString("status") == "ready") "Речь не найдена. Можно прослушать оригинал." else "Текст появится после распознавания." }; setTextIsSelectable(true); setTextColor(palette.text); setPadding(0,dp(10),0,dp(10)) })
            val audio = AudioNotes(this).audio(id)
            card.addView(Button(this).apply { text = if (audio.exists()) "Прослушать запись" else "Оригинал доступен в хабе"; style(); isEnabled=audio.exists() && capture==null
                setOnClickListener {
                    stopPlayback()
                    runCatching { player = MediaPlayer().apply { setDataSource(audio.absolutePath); prepare(); setOnCompletionListener { stopPlayback(); fingerprint=""; render() }; start() }; state.text = "Воспроизведение записи"; fingerprint=""; render() }
                        .onFailure { stopPlayback(); state.text = "Не удалось воспроизвести запись" }
                }
            })
            if (note.getString("status") in setOf("error","ready") && audio.exists()) card.addView(Button(this).apply {
                text="Распознать ещё раз"; style(); setOnClickListener { AudioNoteTranscriber.enqueue(this@AudioNotesActivity,id); state.text="Распознаю сохранённую запись…" }
            })
            list.addView(card, LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(12) })
        }
        if (list.childCount==0) list.addView(TextView(this).apply { text=if(notes.isEmpty()) "Пока нет аудиозаметок" else "Ничего не найдено"; setTextColor(palette.secondary); gravity=Gravity.CENTER })
        if (player != null) list.addView(Button(this).apply { text="Остановить воспроизведение"; style(); setOnClickListener { stopPlayback(); fingerprint=""; render() } })
    }
    private fun stopPlayback() { player?.let { runCatching { it.stop() }; it.release() }; player=null }
    override fun onResume() { super.onResume(); main.post(refresh) }
    // Leaving the screen saves what was recorded and releases the microphone.
    override fun onStop() { capture?.stop(); stopPlayback(); main.removeCallbacks(refresh); super.onStop() }
    override fun onDestroy() { capture?.finished=null; super.onDestroy() }
}
