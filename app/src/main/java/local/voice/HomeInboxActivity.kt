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
        ui.text(content, "Для озвучки скажите: «прочитай сообщения от Гермеса». Уведомления всегда тихие.")
        ui.button(content, "Отметить все прочитанными") {
            entries.maxOfOrNull { it.seq }?.let { inbox.markRead(HomeLink.device(this), it) }; finish()
        }
        HomeLink.kick(this)
    }
    override fun onPause() { super.onPause(); finish() } // Re-open after unlock, never leave private content on keyguard.
}
