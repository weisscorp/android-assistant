package local.voice

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.ScrollView

class HomeInboxActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val palette = AssistantTheme(this, cinematic = true)
        val ui = SettingsUi(this, palette); val content = ui.column()
        window.statusBarColor = palette.background; window.navigationBarColor = palette.background
        setContentView(ScrollView(this).apply { setBackgroundColor(palette.background); addView(content) })
        ui.button(content, "← К ассистенту") { startActivity(Intent(this, MainActivity::class.java)); finish() }
        ui.heading(content, "Сообщения от Hermes", 24f)
        val inbox = HomeInbox(this)
        if (!inbox.allowed()) { ui.text(content, "Разблокируйте телефон для чтения."); return }
        val entries = inbox.entries()
        if (entries.isEmpty()) ui.text(content, "Сообщений пока нет. Домашний агент сможет присылать их после подключения.")
        entries.forEach { entry ->
            val group = ui.group(content, entry.title, "${entry.source} · ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(entry.createdAt))}")
            ui.text(group, entry.body)
        }
        val outgoing = ui.group(content, "Отправить событие в Hub", "Сохранится в очереди до соединения. Разбор не запускает команды на устройствах.")
        val input = android.widget.EditText(this).apply {
            hint = "Что произошло?"; setTextColor(palette.text); setHintTextColor(palette.secondary)
            minLines = 2; maxLines = 6; filters = arrayOf(android.text.InputFilter.LengthFilter(8000))
        }
        outgoing.addView(input)
        val targets = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@HomeInboxActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("По активности", "На телефон", "К маскоту Coucou", "На оба устройства"))
        }
        outgoing.addView(targets)
        val status = ui.text(outgoing, "Исходящих в очереди: ${HomeEvents(this).pendingCount()}")
        ui.button(outgoing, "Отправить на разбор") {
            runCatching { HomeEvents(this).enqueue(input.text.toString().trim(), listOf("auto", "phone", "desktop", "both")[targets.selectedItemPosition]) }
                .onSuccess { input.text.clear(); status.text = "Событие сохранено. Hub подтвердит при соединении." }
                .onFailure { status.text = it.message ?: "Не удалось сохранить событие." }
        }
        HomeEvents(this).recent().forEach { event ->
            val state = when (event.status) {
                "queued" -> "Ожидает соединения"
                "accepted" -> "Hub принял на разбор"
                "failed" -> "Hub отклонил событие. Проверьте подключение и права ключа."
                else -> "Сохранено"
            }
            ui.text(outgoing, "$state\n${event.message.take(300)}")
        }
        ui.text(content, "Для озвучки скажите: «прочитай сообщения от Гермеса». Уведомления всегда тихие.")
        ui.button(content, "Отметить все прочитанными") {
            entries.maxOfOrNull { it.seq }?.let { inbox.markRead(HomeLink.device(this), it) }; finish()
        }
        HomeLink.kick(this)
    }
    override fun onPause() { super.onPause(); finish() } // Re-open after unlock, never leave private content on keyguard.
}
