package local.voice

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.widget.*

/** Management and permission recovery; setting clocks uses the ordinary text/voice conversation. */
class ClockActivity : Activity() {
    private val palette by lazy { AssistantTheme(this, cinematic = true) }
    private val ui by lazy { SettingsUi(this, palette) }
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var elapsed: TextView
    private lateinit var active: LinearLayout
    private var fingerprint = ""
    private val tick = object : Runnable {
        override fun run() {
            val state = ClockStore(this@ClockActivity).use { it.stopwatch(PhoneClocks.boot(this@ClockActivity)) }
            val ms = state.milliseconds(SystemClock.elapsedRealtime())
            elapsed.text = "%02d:%02d:%02d · %s".format(ms / 3600000, ms / 60000 % 60, ms / 1000 % 60, if (state.running) "идёт" else "пауза")
            refreshEntries()
            handler.postDelayed(this, 500)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); render() }
    override fun onResume() { super.onResume(); render(); PhoneClocks.restore(this); handler.removeCallbacks(tick); handler.post(tick) }
    override fun onPause() { handler.removeCallbacks(tick); super.onPause() }
    private fun perform(action: () -> Unit) {
        runCatching(action).onFailure { AlertDialog.Builder(this).setTitle("Не удалось выполнить").setMessage(it.message ?: "Повторите действие.").setPositiveButton("Понятно", null).show() }
        render()
    }
    private fun render() {
        window.statusBarColor = palette.background; window.navigationBarColor = palette.background
        val column = ui.column()
        ui.button(column, "← Назад") { finish() }
        ui.heading(column, "Время и напоминания", 28f)
        ui.text(column, "Скажите или напишите ассистенту команду. Всё работает на телефоне, без сервера и Google-сервисов.")
        val stopwatch = ui.group(column, "Секундомер")
        elapsed = ui.heading(stopwatch, "00:00:00", 28f)
        ui.button(stopwatch, "Старт / продолжить", true) { perform { PhoneClocks.stopwatch(this, ClockOperation.START) } }
        ui.button(stopwatch, "Пауза") { perform { PhoneClocks.stopwatch(this, ClockOperation.PAUSE) } }
        ui.button(stopwatch, "Круг") { perform {
            val (_, laps) = PhoneClocks.stopwatch(this, ClockOperation.LAP)
            AlertDialog.Builder(this).setTitle("Круги").setMessage(laps.mapIndexed { i, ms -> "${i+1}: ${ClockRequests.durationSpeech(ms/1000)}" }.joinToString("\n")).setPositiveButton("Готово", null).show()
        } }
        ui.button(stopwatch, "Сбросить") { AlertDialog.Builder(this).setMessage("Сбросить секундомер и круги?").setPositiveButton("Сбросить") { _, _ -> perform { PhoneClocks.stopwatch(this, ClockOperation.RESET) } }.setNegativeButton("Отмена", null).show() }
        ui.text(stopwatch, "Сохраняется после закрытия приложения. После перезагрузки телефона сбрасывается.")
        active = ui.group(column, "Таймеры, будильники и напоминания")
        fingerprint = ""; refreshEntries()
        val permissions = ui.group(column, "Доступы для срабатывания")
        ui.text(permissions, "Точные сигналы: ${if (PhoneClocks.exact(this)) "разрешены" else "нужен доступ"}\nУведомления: ${if (PhoneClocks.alerts(this)) "разрешены" else "нужен доступ"}")
        ui.button(permissions, "Разрешить точные таймеры и будильники") { perform {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
        } }
        ui.button(permissions, "Разрешить уведомления") { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 31) }
        ui.text(permissions, "Экран звонка поверх блокировки: ${if (PhoneClocks.fullScreen(this)) "разрешён" else "нужен доступ"}")
        if (android.os.Build.VERSION.SDK_INT >= 34) ui.button(permissions, "Разрешить полноэкранные сигналы") { perform {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
        } }
        ui.button(permissions, "Разрешить команду стоп во время звонка") { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 33) }
        ui.button(permissions, "Настройки сигналов и уведомлений") { perform {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        } }
        ui.text(permissions, "Таймер и будильник звонят до остановки, максимум десять минут, через громкость будильника. Во время сигнала можно сказать «стоп» или нажать «Остановить». Микрофон выключается вместе с сигналом. При запрете Android откройте сигнал для команды голосом. Настройки «Не беспокоить» и нулевая громкость будильника не изменяются. Напоминания остаются обычными уведомлениями. Принудительная остановка приложения в Android может отключить часы до следующего запуска.")
        val calendars = ui.group(column, "Календарь")
        val selected = runCatching { ClockCalendar.selected(this) }.getOrNull()
        ui.text(calendars, if (!ClockCalendar.permitted(this)) "Нужен доступ к календарю." else selected?.let { "Выбран: ${it.name}" } ?: "Выберите календарь или создайте локальный.")
        ui.button(calendars, "Разрешить чтение и запись календаря") { requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR), 32) }
        ui.button(calendars, "Выбрать календарь") { perform {
            check(ClockCalendar.permitted(this)) { "Сначала разрешите доступ к календарю." }
            val choices = ClockCalendar.calendars(this)
            check(choices.isNotEmpty()) { "Доступных календарей нет. Можно создать локальный календарь." }
            AlertDialog.Builder(this).setTitle("Календарь напоминаний").setItems(choices.map { it.name }.toTypedArray()) { _, i -> perform { ClockCalendar.select(this, choices[i].id) } }.setNegativeButton("Отмена", null).show()
        } }
        ui.button(calendars, "Создать локальный календарь") { AlertDialog.Builder(this).setTitle("Календарь «Ассистент»").setMessage("Создать на телефоне календарь без аккаунта? События останутся локальными. Если он уже есть, выберу его.").setPositiveButton("Создать") { _, _ -> perform { ClockCalendar.createLocal(this) } }.setNegativeButton("Отмена", null).show() }
        ui.text(calendars, "Перед записью ассистент уточняет текст, дату и календарь. Сигнал подаёт сам ассистент; дополнительный сигнал календаря не добавляется.")
        val examples = ui.group(column, "Примеры команд")
        ui.text(examples, "«Поставь таймер на десять минут»\n«Сколько осталось на таймере?»\n«Запусти секундомер»\n«Останови секундомер»\n«Будильник завтра в восемь тридцать»\n«Напомни завтра в десять забрать посылку»\n«Отмени все таймеры»\n\nБудильники и напоминания пока однократные. Для записи или отмены скажите «подтверждаю». Часы после перезагрузки восстанавливаются при наличии разрешений.")
        setContentView(ScrollView(this).apply {
            setBackgroundColor(palette.background); isVerticalScrollBarEnabled = false
            addView(column, FrameLayout.LayoutParams(-1, -2, android.view.Gravity.CENTER_HORIZONTAL))
            setOnApplyWindowInsetsListener { view, insets -> val b = insets.getInsets(android.view.WindowInsets.Type.systemBars()); view.setPadding(b.left,b.top,b.right,b.bottom); insets }
        })
    }
    private fun refreshEntries() {
        val entries = ClockStore(this).use { it.list() }
        val state = entries.toString()
        if (fingerprint == state) return
        fingerprint = state
        // Keep the group heading; rebuild only when an alarm changes state.
        while (active.childCount > 1) active.removeViewAt(1)
        if (entries.isEmpty()) ui.text(active, "Активных часов нет.")
        entries.forEach { entry ->
            ui.text(active, "${PhoneClocks.description(entry)}${if(entry.state == "fired") " · сработал" else ""}", muted = false)
            ui.button(active, if(entry.state == "fired") "Закрыть сигнал" else "Отменить") {
                val deleteEvent = entry.calendarEvent != null && entry.state != "fired"
                AlertDialog.Builder(this).setMessage("${if(entry.state == "fired") "Закрыть сигнал" else "Отменить"} ${PhoneClocks.description(entry)}${if(deleteEvent) " и удалить запись календаря" else ""}?")
                    .setPositiveButton("Да") { _, _ -> perform { PhoneClocks.cancel(this, entry.id, deleteEvent) } }.setNegativeButton("Оставить", null).show()
            }
        }
    }
    @Deprecated("Platform permission callback")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) { super.onRequestPermissionsResult(requestCode, permissions, grantResults); render() }
}
