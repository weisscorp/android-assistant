package local.voice

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.*
import java.io.File
import java.util.concurrent.Executors

class SettingsActivity : Activity() {
    private val palette by lazy { AssistantTheme(this, cinematic = true) }
    private lateinit var status: TextView
    private lateinit var capability: TextView
    private var journalStatus: TextView? = null
    private var conversation: VoiceConversation? = null
    private var headsetProbe: HeadsetAudioProbe? = null
    private var headsetProbeDialog: android.app.Dialog? = null
    private val files = Executors.newSingleThreadExecutor()
    private var importing = false
    private lateinit var modelStatus: TextView
    private lateinit var asrChoice: Button
    private lateinit var ttsChoice: Button
    private lateinit var brainChoice: Button
    private var preview: LocalVoice? = null
    private var previewDialog: android.app.Dialog? = null
    private val modelHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val modelProgress = object : Runnable {
        override fun run() {
            if (!isDestroyed) {
                if (::modelStatus.isInitialized) modelStatus.text = ModelDownload.message.ifBlank { modelDescription() }
                refresh()
                modelHandler.postDelayed(this, 1500)
            }
        }
    }
    private fun modelDescription(): String {
        val model = LocalBrain.model(this)
        return if (model.exists()) "Подключена ${ModelCatalog.selectedBrain(this)?.name ?: "GGUF"} · ${model.length() / 1024 / 1024} МБ"
        else "Модель ещё не подключена. Откройте каталог или выберите свой GGUF-файл."
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    companion object { internal const val EXTRA_SECTION = "settings_section" }
    private val ui by lazy { SettingsUi(this, palette) }
    private lateinit var content: LinearLayout
    private val navigation = mutableListOf<SettingsUi.NavigationRow>()
    private val section by lazy {
        SettingsSection.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_SECTION) } ?: SettingsSection.HOME
    }
    private fun label(text: String, size: Float = 15f) = ui.text(content, text, size)
    private fun button(text: String, action: () -> Unit) = ui.button(content, text, action = action)
    private fun group(title: String, description: String = "", body: () -> Unit) {
        val parent = content
        content = ui.group(parent, title, description)
        body()
        content = parent
    }
    private fun info(title: String, message: String) {
        android.app.AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("Понятно", null).show()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = palette.background; window.navigationBarColor = palette.background
        val layout = ui.column(); content = layout
        ui.button(content, if (section == SettingsSection.HOME) "← К ассистенту" else "← Параметры") { finish() }
            .apply { id = R.id.settings_back; background = palette.ripple(palette.background, dp(16).toFloat()); layoutParams = LinearLayout.LayoutParams(-1, -2) }
        label(if (section == SettingsSection.HOME) "СИСТЕМА АССИСТЕНТА" else "ПАРАМЕТРЫ / ${section.title.uppercase(java.util.Locale.ROOT)}", 12f)
            .apply { letterSpacing = .12f; setTextColor(palette.accent); setPadding(0, dp(4), 0, 0); layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) } }
        ui.heading(content, section.title, if (resources.configuration.fontScale > 1.3f) 26f else 32f)
            .apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) } }
        label(section.subtitle, 16f).apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) } }
        when (section) {
            SettingsSection.HOME -> showMenu()
            SettingsSection.ACCESS -> showAccess()
            SettingsSection.MODELS -> showModels()
            SettingsSection.AUDIO -> showAudio()
            SettingsSection.TESTS -> showTests()
            SettingsSection.PERSONAL -> showPersonal()
            SettingsSection.CLOCK -> button("Открыть время и напоминания") { startActivity(Intent(this, ClockActivity::class.java)) }
            SettingsSection.SERVER -> showServer()
        }
        status = label("").apply { accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE }
        label("Ассистент · ${packageManager.getPackageInfo(packageName, 0).versionName}", 13f).apply { setPadding(0, dp(12), 0, 0) }
        setContentView(ScrollView(this).apply {
            id = R.id.settings_scroll; isVerticalScrollBarEnabled = false
            setBackgroundColor(palette.background)
            addView(layout, android.widget.FrameLayout.LayoutParams(-1, -2, android.view.Gravity.CENTER_HORIZONTAL))
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                val keyboard = insets.getInsets(android.view.WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom)); insets
            }
        })
    }
    private fun showMenu() {
        for (item in listOf(SettingsSection.ACCESS, SettingsSection.MODELS, SettingsSection.AUDIO, SettingsSection.TESTS)) {
            navigation += ui.navigation(content, item) { openSection(item) }
        }
        label("ЕЩЁ ПАРАМЕТРЫ", 12f).apply { letterSpacing = .1f; setPadding(0, dp(20), 0, 0) }
        for (item in listOf(SettingsSection.CLOCK, SettingsSection.PERSONAL, SettingsSection.SERVER)) {
            navigation += ui.navigation(content, item, compact = true) { openSection(item) }
        }
    }
    private fun openSection(item: SettingsSection) {
        if (item == SettingsSection.CLOCK) { startActivity(Intent(this, ClockActivity::class.java)); return }
        startActivity(Intent(this, SettingsActivity::class.java).putExtra(EXTRA_SECTION, item.name))
    }
    private fun showAccess() {
        group("Состояние доступов") { capability = label("") }
        group("Голос и системный вызов", "Выберите ассистента и разрешите микрофон с Bluetooth.") {
            button("Выбрать системным ассистентом") { openAssistantSettings() }
            button("Разрешить микрофон и Bluetooth") { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS), 10) }
            button("Местоположение для погоды и расстояния") { requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION), 14) }
            button("Уступать разговор входящим звонкам") { requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE), 12) }
        }
        group("Приложения и личные данные") {
            button("Разрешить чтение и нажатия на экране") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            button("Разрешить чтение уведомлений") { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        content.addView(Switch(this).apply {
            text = "Читать личные уведомления при блокировке"; setTextColor(palette.text)
            isChecked = getSharedPreferences("settings", 0).getBoolean("notifications_when_locked", false)
            setOnCheckedChangeListener { _, checked -> getSharedPreferences("settings", 0).edit().putBoolean("notifications_when_locked", checked).apply() }
        })
            button("Разрешить звонки и поиск контактов") { requestPermissions(arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_CONTACTS), 12) }
            label("Действия в приложениях требуют разблокировки телефона.")
        }
        group("Запуск при блокировке", "Для вызова кнопкой гарнитуры.") {
            val xiaomi = listOf(android.os.Build.MANUFACTURER, android.os.Build.BRAND).any {
                it.lowercase(java.util.Locale.ROOT) in setOf("xiaomi", "redmi", "poco")
            }
            if (xiaomi) {
                label("В MIUI / HyperOS включите показ на экране блокировки и открытие окон в фоне в «Других разрешениях».")
                button("Открыть разрешения приложения") { openAppSettings() }
            }
            button("Как настроить вызов при блокировке") {
                info("Запуск при блокировке", "Выберите «Ассистент» в разделе «Цифровой помощник» настроек Android.\n\nВ MIUI / HyperOS: «Ассистент» → «Другие разрешения» → «Показывать на экране блокировки». Для открытия windy.app и других приложений разрешите «Открывать новые окна, когда запущено в фоновом режиме».\n\nЕсли пунктов нет, откройте настройки телефона → «Защита конфиденциальности» → «Другие разрешения». Прошивка может сбросить разрешения после обновления. Названия зависят от прошивки.\n\nДоступ к защищённым приложениям потребует разблокировки.")
            }
        }
        group("Установка новых версий") {
            button("Разрешить установку обновлений") { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:$packageName"))) }
        }
    }
    private fun showModels() {
        group("Модель для ответов") {
            modelStatus = label(modelDescription())
            brainChoice = button("Каталог моделей и benchmark") { stopForModelChange(); startActivity(Intent(this, ModelCatalogActivity::class.java)) }
            button("Выбрать свой GGUF-файл") {
                if (!importing && !ModelDownload.importBusy && !ModelDownload.busy && !LocalBrain.maintenanceBusy) {
                    VoiceConversation.stopActive(); conversation = null; LocalBrain.cancel()
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE) }, 20)
                } else status.text = "Дождитесь загрузки, импорта или теста модели."
            }
            button("О каталоге и совместимости") {
                info("Локальные модели", "LFM2.5, Qwen3, Qwen3.5 и Gemma 3: скачивание, выбор, удаление и benchmark. В каталоге только модели, проверенные с текущим Android runtime, до 2 ГБ на файл.\n\nТокенизация и шаблон диалога берутся из GGUF. Свой файл проверяется загрузкой и генерацией до переключения. Без chat template используется текстовый completion. Импорт ограничен свободным местом и памятью телефона.")
            }
        }
        group("Распознавание и синтез", "Оба этапа работают на телефоне без сети и Google-приложений.") {
        asrChoice = button("") {
            val selected = if (getSharedPreferences("settings", 0).getBoolean("tone_asr", false)) 1 else 0
            android.app.AlertDialog.Builder(this).setTitle("Распознавание речи")
                .setSingleChoiceItems(arrayOf("Vosk · компактная русская модель", "T-one · экспериментальная русская модель"), selected) { dialog, index ->
                    stopForModelChange()
                    getSharedPreferences("settings", 0).edit().putBoolean("tone_asr", index == 1).commit()
                    ModelDownload.message = ""; refresh(); dialog.dismiss()
                }.setNegativeButton("Закрыть", null).show()
        }
        ttsChoice = button("") {
            val voices = listOf("baya", "ruslan", "aidar", "kseniya", "eugene", "xenia") +
                if (VoicePackage.custom(this).isDirectory) listOf("custom") else emptyList()
            android.app.AlertDialog.Builder(this).setTitle("Голос ответа")
                .setSingleChoiceItems(voices.map(::voiceLabel).toTypedArray(), voices.indexOf(ModelCatalog.voice(this))) { dialog, index ->
                    stopForModelChange(); ModelCatalog.selectVoice(this, voices[index]); refresh(); dialog.dismiss()
                }.setNegativeButton("Закрыть", null).show()
        }
            label("Vosk — компактный. T-one требует больше памяти; качество в шуме зависит от гарнитуры.")
        }
        group("Собственная модель голоса", "Готовый русский голос Piper / VITS в ZIP, до 256 МБ после распаковки.") {
        button("Формат пакета голоса") {
            android.app.AlertDialog.Builder(this).setTitle("Пакет голоса: ZIP")
                .setMessage("""
                    В корне архива должны быть ровно три файла, без папок:
                    manifest.json
                    model.onnx
                    tokens.txt

                    Содержимое manifest.json:
                    {"engine":"piper-vits","language":"ru","name":"Мой голос"}

                    model.onnx — модель Piper/VITS, совместимая с sherpa-onnx, с метаданными Piper. Размер больше 1 МБ.
                    tokens.txt — словарь токенов для этой модели, от 100 байт до 64 КиБ.

                    Используется встроенный русский фонемизатор espeak-ng. Общий размер распакованных файлов — до 256 МБ.

                    Пакет содержит готовую обученную модель. Аудиозаписи WAV/MP3 и обычный ONNX без нужных метаданных не подходят.
                """.trimIndent()).setPositiveButton("Понятно", null).show()
        }
        button("Импортировать пакет голоса") {
            if (!importing) {
                VoiceConversation.stopActive(); conversation = null
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "application/zip"; addCategory(Intent.CATEGORY_OPENABLE) }, 21)
            }
        }
        }
    }
    private fun showAudio() {
        group("Голосовой канал", "Подключённая гарнитура выбирается автоматически. Без неё работают микрофон и динамик телефона.") {
            capability = label("")
        button("Выбрать гарнитуру") {
            runCatching {
                val devices = AudioRoute.headsets(this)
                val labels = arrayOf("Автоматически: текущее аудиоустройство") + devices.map { it.productName.toString().take(100) }.toTypedArray()
                android.app.AlertDialog.Builder(this).setTitle("Голосовой канал")
                    .setItems(labels) { _, index ->
                        val key = if (index == 0) "" else AudioRoute.key(devices[index - 1])
                        VoiceConversation.stopActive(); conversation = null
                        getSharedPreferences("settings", 0).edit().putString("headset_key", key).apply()
                        status.text = if (index == 0) "Автоматический выбор гарнитуры." else "Выбрана ${labels[index]}. Если она отключена, используется текущее аудиоустройство."
                    }.setNegativeButton("Закрыть", null).show()
            }.onFailure { status.text = "Разрешите Bluetooth и подключите гарнитуру." }
        }
        }
        group("Звучание ответа") {
            label("Голос: ${voiceLabel(ModelCatalog.voice(this))}")
            button("Прослушать выбранный голос") { showTtsPreview() }
            button("Тональность и скорость Silero") { showVoiceTuning() }
            button("Выбрать модель голоса") { openSection(SettingsSection.MODELS) }
        }
        group("Сигналы записи", "Три тона вверх — начало записи. Три тона вниз — запись закончена. Повторная кнопка гарнитуры останавливает разговор.") {
            val volume = label(getString(R.string.cue_volume_label, ReadyCue.volumePercent(this)), 16f).apply {
                id = R.id.settings_cue_volume_label
                labelFor = R.id.settings_cue_volume
                setTextColor(palette.text)
            }
            content.addView(SeekBar(this).apply {
                id = R.id.settings_cue_volume
                min = 0; max = 100; progress = ReadyCue.volumePercent(this@SettingsActivity)
                minHeight = dp(48)
                contentDescription = getString(R.string.cue_volume_description)
                stateDescription = "$progress%"
                progressTintList = android.content.res.ColorStateList.valueOf(palette.accent)
                thumbTintList = android.content.res.ColorStateList.valueOf(palette.accent)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        volume.text = getString(R.string.cue_volume_label, progress)
                        seekBar.stateDescription = "$progress%"
                        if (fromUser) ReadyCue.setVolumePercent(this@SettingsActivity, progress)
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
                })
            }, LinearLayout.LayoutParams(-1, dp(48)))
            label(getString(R.string.cue_volume_hint))
            button("Проверить подключение и звук") { openSection(SettingsSection.TESTS) }
        }
    }
    private fun showTests() {
        group("Журнал запросов и действий", "Фразы, полный вход и ответы LLM, инструменты, подтверждения и результаты. Хранится на телефоне; при заполнении удаляются старые записи.") {
            journalStatus = label(InteractionJournal.description(this))
            content.addView(Switch(this).apply {
                text = "Сохранять журнал локально"; setTextColor(palette.text)
                isChecked = InteractionJournal.isEnabled(this@SettingsActivity)
                setOnCheckedChangeListener { _, checked ->
                    InteractionJournal.setEnabled(this@SettingsActivity, checked)
                    journalStatus?.text = InteractionJournal.description(this@SettingsActivity)
                }
            })
            button("Сохранить журнал в файл…") {
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/x-ndjson").putExtra(Intent.EXTRA_TITLE, "assistant-journal-${java.time.LocalDate.now()}.jsonl"), 22)
            }
            button("Очистить журнал") {
                android.app.AlertDialog.Builder(this).setTitle("Очистить журнал?")
                    .setMessage("Сохранённые запросы, ответы и действия будут удалены с телефона.")
                    .setNegativeButton("Оставить", null).setPositiveButton("Очистить") { _, _ ->
                        files.execute {
                            val result = runCatching { InteractionJournal.clear(this) }
                            runOnUiThread { if (!isDestroyed) {
                                status.text = result.fold({ "Журнал очищен." }, { "Не удалось очистить журнал: ${it.message}" })
                                journalStatus?.text = InteractionJournal.description(this)
                            } }
                        }
                    }.show()
            }
            label("Лимит — 50 МБ. Экспорт: выберите папку Download, затем скопируйте файл по USB. Журнал не отправляется в Hermes. Аудиозаписи не сохраняются.", 13f)
        }
        group("Сначала подключение") {
            capability = label("")
        button("Как проверить запуск с гарнитуры") {
            android.app.AlertDialog.Builder(this).setTitle("Проверка гарнитуры")
                .setMessage("Подключите гарнитуру и сначала проверьте с включённым экраном: кнопка вызова ассистента → восходящий сигнал → «который час» → нисходящий сигнал → ответ.\n\nПовторите с заблокированным экраном, затем скажите «стоп». Сигнал самой гарнитуры не означает, что приложение уже записывает.\n\nЕсли запуск не состоялся, проверьте системного ассистента и разрешения телефона. Если запись началась, но речи нет, используйте «Проверить звук гарнитуры» ниже.")
                .setPositiveButton("Понятно", null).show()
        }
        }
        group("Голосовой тракт", "Эти проверки включают микрофон и звук только после вашего нажатия.") {
        button("Проверить голос здесь") { showVoiceCheck() }
        button("Проверить звук гарнитуры") { showHeadsetProbe() }
        button("Вызвать системную сессию") {
            conversation?.close(); conversation = null
            if (!AssistantService.invoke()) status.text = "Сначала выберите Ассистент системным ассистентом."
        }
        button("Остановить разговор") { VoiceConversation.stopActive(); conversation = null; status.text = "Остановлено" }
        button("Диагностика последнего разговора") {
            android.app.AlertDialog.Builder(this).setTitle("Последняя сессия")
                .setMessage(SessionDiagnostics.last(this)).setPositiveButton("Закрыть", null).show()
        }
        }
        group("Модели и сервер") {
            button("Замерить скорость локальной модели") { stopForModelChange(); startActivity(Intent(this, ModelCatalogActivity::class.java)) }
            button("Подключение к Hermes") { openSection(SettingsSection.SERVER) }
        }
    }
    private fun showPersonal() {
        group("Знания и источники", "Свяжите чат, страницу, место или заметку с понятным названием.") {
            button("Знания и источники") { startActivity(Intent(this, SourcesActivity::class.java)) }
            label("Добавьте вручную или через «Поделиться» → «Ассистент». Затем скажите «Прочитай источник» и его название. Снимок карты не заменяет живую геопозицию.")
        }
        group("Память разговора и люди") {
        button("Память последнего общения") {
            android.app.AlertDialog.Builder(this).setTitle("Тема и ключевые моменты")
                .setMessage(ConversationMemory.message(this))
                .setPositiveButton("Закрыть", null)
                .setNeutralButton("Очистить") { _, _ ->
                    VoiceConversation.stopActive(); conversation = null
                    ConversationMemory.clear(this); status.text = "Память разговора очищена."
                }.show()
        }
        button("Возможности телефона и Hermes") {
            android.app.AlertDialog.Builder(this).setTitle("Каталог возможностей").setMessage(CapabilityCatalog.message(this)).setPositiveButton("Закрыть", null).show()
        }
        button("Связи людей: жена, мама, имена") {
            android.app.AlertDialog.Builder(this).setTitle("Память контактов").setMessage(PeopleMemory(this).message())
                .setPositiveButton("Закрыть", null).show()
        }
            button("Как запомнить людей") {
                info("Связи людей", "«Запомни жена это Мария Иванова» → выбор и подтверждение.\n\nИмя Telegram отдельно: «Запомни телеграм жены это @username». Затем «Напиши жене сообщение Буду через час».\n\nУдаление: «Забудь жена». Связи сохраняются на телефоне после перезапуска.")
            }
        }
        group("Навигация и музыка") {
        button("Предпочитаемый навигатор") {
            val choices = MobileApps.navigators(this)
            android.app.AlertDialog.Builder(this).setTitle("Навигатор")
                .setItems(arrayOf("Спрашивать каждый раз") + choices.map { it.label }.toTypedArray()) { _, index ->
                    MobileApps.prefer(this, if (index == 0) "" else choices[index - 1].pkg)
                    status.text = if (index == 0) "Буду спрашивать навигатор." else "Выбран ${choices[index - 1].label}. Перед открытием запрошу подтверждение."
                }.setNegativeButton("Закрыть", null).show()
        }
            button("Команды навигации и музыки") {
                info("Навигация и музыка", "«Маршрут до Батуми пешком». Если способ не назван, помощник спросит: машина, пешком или транспорт. Google Maps принимает маршрут и способ; остальные навигаторы пока получают поиск адреса.\n\n«Найди Металлику в Spotify», «Включи Metallica в Spotify».")
            }
        }
        group("Справка") {
            button("Голосовые команды") {
                info("Голосовые команды", "«Который час», «Заряд», «Открой Telegram», «Прочитай уведомления», «Пауза музыки», «Продолжи музыку», «Следующий трек», «Музыку тише», «Прочитай экран», «Нажми Отправить», «Напиши Буду через десять минут», «Прокрути вниз», «Позвони Иван», «Статус сервера», «Отмени задачу сервера».\n\n«Стоп», «Замолчи», «Отмена» или повторная кнопка прерывают разговор. Для нового разговора нажмите ещё раз. Экран и приложения требуют разблокировки.")
            }
            button("Погода и windy.app") {
                info("Погода", "«Какая погода?» — Open-Meteo по геолокации. «Погода в Батуми завтра» — поиск города и прогноз в фоне. Нужен интернет, Google и Hermes не требуются. Координаты передаются для прогноза, но не озвучиваются; модель выбирается сервисом автоматически. Геокодирование: GeoNames.\n\n«Погода из windy.app» — карточка выбранного в приложении места. «Ветер из windy.app» — открыть карточку и читать подробности. Нужны разблокировка и доступ к экрану. Данные приложения могут отличаться от Open-Meteo.")
            }
        }
    }
    private fun showServer() {
        group("Обработка вопросов") {
        button("Выбор мозга по умолчанию") {
            val modes = AssistantRequests.Mode.entries
            val selected = getSharedPreferences("settings", 0).getString("brain_mode", "AUTO")
            android.app.AlertDialog.Builder(this).setTitle("Обработка вопросов")
                .setSingleChoiceItems(arrayOf("Автоматически · команды здесь, сложные вопросы в Hermes", "Локально · без передачи разговоров", "Hermes · телефон распознаёт и озвучивает"), modes.indexOfFirst { it.name == selected }) { dialog, index ->
                    VoiceConversation.stopActive()
                    getSharedPreferences("settings", 0).edit().putString("brain_mode", modes[index].name).apply()
                    status.text = "Режим сохранён для новых разговоров."; dialog.dismiss()
                }.setNegativeButton("Закрыть", null).show()
        }
            label("Локально, автоматически или через домашнего Hermes. Распознавание и голос остаются на телефоне.")
        }
        group("Подключение к Hermes", "Если сервера пока нет, оставьте адрес пустым.") {
            label("HTTPS-адрес сервера")
            val endpoint = EditText(this).apply {
                id = R.id.settings_endpoint
                hint = "https://192.168.1.2:8765"; setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                setText(BackendClient.endpoint(this@SettingsActivity)); setTextColor(palette.text); setHintTextColor(palette.secondary)
                content.addView(this, LinearLayout.LayoutParams(-1, -2))
            }
            label("Bearer-токен")
            val token = EditText(this).apply {
                id = R.id.settings_token
                hint = "Пусто — оставить прежний"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setSingleLine(); setTextColor(palette.text); setHintTextColor(palette.secondary)
                content.addView(this, LinearLayout.LayoutParams(-1, -2))
            }
            label("Отпечаток сертификата домашнего сервера · необязательно")
            val certificate = EditText(this).apply {
                id = R.id.settings_certificate
                hint = "SHA-256 · 64 знака"; setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                setText(BackendClient.certificateFingerprint(this@SettingsActivity)); setTextColor(palette.text); setHintTextColor(palette.secondary)
                content.addView(this, LinearLayout.LayoutParams(-1, -2))
            }
            label("Для HTTPS по локальному IP вставьте отпечаток, проверенный на домашнем сервере. Он сохраняется; при смене сертификата подключение остановится. Пустое поле использует системные сертификаты.")
            button("Сохранить подключение") {
                runCatching { BackendClient.save(this, endpoint.text.toString(), token.text.toString(), certificate.text.toString()) }
                    .onSuccess { token.text.clear(); certificate.setText(BackendClient.certificateFingerprint(this)); status.text = "Подключение к Hermes сохранено. Для разговора: «свяжи с гермесом»." }
                    .onFailure { status.text = it.message ?: "Неверный адрес"; endpoint.error = status.text }
            }
            button("Как работает серверный разговор") {
                info("Hermes", "«Свяжи с гермесом» — вопросы серверу в текущем разговоре. «Локальный режим» — без сервера. «Автоматический режим» — выбор по сложности.\n\nВ автоматическом режиме команды выполняются телефоном, открытые вопросы и ошибки модели идут в Hermes. Перед первым запросом прозвучит «Подключаю Hermes». Передаются запрос, до четырёх пар разговора и каталог инструментов; скриншоты и вся адресная книга автоматически не отправляются.\n\nДля разговора нужен маршрут /conversation. Старая очередь заданий отдельно: «Сервер …», «Статус сервера», «Отмени задачу сервера». Серверная задача сохраняется при завершении разговора.")
            }
        }
        group("Знания и сообщения", "Личные знания, связи людей, краткая память и картинки доступны домашнему Hermes. Вся адресная книга не передаётся.") {
            val prefs = getSharedPreferences("settings", 0)
            content.addView(Switch(this).apply {
                text = "Синхронизация с Hermes"; setTextColor(palette.text)
                isChecked = prefs.getBoolean("home_link_enabled", true)
                setOnCheckedChangeListener { _, enabled ->
                    prefs.edit().putBoolean("home_link_enabled", enabled).commit(); HomeLink.kick(this@SettingsActivity)
                    status.text = if (enabled) "Синхронизация включена." else "Синхронизация остановлена. Уже переданные знания остаются на сервере."
                }
            })
            label("Изменения и удаления отправляются при соединении. Без сети знания остаются на телефоне. Сообщения сохраняются во входящих. Android может задерживать доставку в режиме сна.")
            button("Синхронизировать сейчас") { HomeLink.kick(this); status.text = "Соединяюсь с Hermes…" }
            button("Статус синхронизации") { status.text = HomeLink.status(this) }
            button("Сообщения от Hermes") { startActivity(Intent(this, HomeInboxActivity::class.java)) }
            button("Разрешить уведомления от Hermes") { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7312) }
            content.addView(Switch(this).apply {
                text = "Всплывающие важные события Hub"; setTextColor(palette.text)
                isChecked = prefs.getBoolean("home_popup_notifications", true)
                setOnCheckedChangeListener { _, enabled -> prefs.edit().putBoolean("home_popup_notifications", enabled).commit(); HomeLink.kick(this@SettingsActivity) }
            })
            button("Системные настройки уведомлений") {
                startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName))
            }
            content.addView(Switch(this).apply {
                text = "Разрешить читать сообщения при блокировке"; setTextColor(palette.text)
                isChecked = prefs.getBoolean("home_read_when_locked", false)
                setOnCheckedChangeListener { _, enabled -> prefs.edit().putBoolean("home_read_when_locked", enabled).commit() }
            })
            label("Важные события могут всплывать поверх приложений, если Android разрешает. Все каналы создаются без звука и вибрации. Для озвучки: «прочитай сообщения от Гермеса» или «прочитай». Сообщение об обновлении не запускает установку. Источники Slack и почты подключаются на домашнем сервере.")
        }
        group("Dev OTA", "Отдельное подключение для автоматических обновлений. Версия, SHA-256 и подпись проверяются до установки.") {
            fun field(title: String, value: String, secret: Boolean = false): EditText {
                label(title)
                return EditText(this).apply {
                    setSingleLine(); setText(value); setTextColor(palette.text); setHintTextColor(palette.secondary)
                    inputType = InputType.TYPE_CLASS_TEXT or if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    content.addView(this, LinearLayout.LayoutParams(-1, -2))
                }
            }
            val endpoint = field("HTTPS-адрес dev OTA", OtaClient.endpoint(this)).apply { hint = "https://192.168.1.2:8779" }
            val token = field("Ключ доступа (пустое поле сохраняет текущий)", "", secret = true)
            val certificate = field("SHA-256 сертификата для локального IP", OtaClient.fingerprint(this))
            val automatic = Switch(this).apply {
                text = "Автоматически скачивать и устанавливать dev-версии"; setTextColor(palette.text)
                isChecked = OtaClient.enabled(this@SettingsActivity); content.addView(this)
            }
            button("Сохранить dev OTA") {
                runCatching { OtaClient.save(this, endpoint.text.toString(), token.text.toString(), certificate.text.toString(), automatic.isChecked) }
                    .onSuccess { token.text.clear(); status.text = if (automatic.isChecked) "Dev OTA включён. Проверю при свободном ассистенте и Wi-Fi." else "Подключение сохранено. Автообновления выключены." }
                    .onFailure { status.text = it.message ?: "Не удалось сохранить dev OTA." }
            }
            button("Импортировать настройки dev OTA") {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "application/json"; addCategory(Intent.CATEGORY_OPENABLE)
                }, 23)
            }
            label("Фоновая проверка — примерно раз в 15 минут при доступной безлимитной сети; Android может задерживать задания во сне. Установка ждёт окончания работы ассистента и звонков. Звуки и микрофон при обновлении не включаются. Один раз разрешите установку приложений в разделе «Доступы». Если Android требует подтверждения, появится тихое уведомление.")
            button("Статус dev OTA") { status.text = AutoUpdates.status(this) + "\n" + Updates.message(this) }
        }
        group("Обновления приложения", "Используется dev OTA, если он задан; иначе домашний сервер.") {
        button("Проверить и установить OTA") {
            VoiceConversation.stopActive(); conversation = null
            status.text = "Проверяю обновления на домашнем сервере…"
            files.execute {
                val result = runCatching { Updates.download(this) }
                runOnUiThread {
                    if (!isDestroyed && !isFinishing) result.onSuccess { apk ->
                        if (apk == null) status.text = "На сервере нет более новой версии."
                        else { Updates.enqueueInstall(this, apk); status.text = "Передаю APK системному установщику…" }
                    }.onFailure { status.text = it.message ?: "Сервер обновлений недоступен." }
                }
            }
        }
        button("Статус обновления") { status.text = Updates.message(this) }
            button("Доступ к установке обновлений") { openSection(SettingsSection.ACCESS) }
            button("Как обновляться голосом") {
                info("Обновления", "«Проверь обновления», затем «Подтверждаю» или «Отмена». Проверка: «Статус обновления».\n\nOTA проверяет версию, SHA-256 и подпись APK. Если Android требует подтверждение, появится тихое уведомление с действием для установки. Исходники и выпуск версии обрабатываются агентом на сервере.")
            }
        }
    }
    override fun onStart() { super.onStart(); refresh(); if (section == SettingsSection.TESTS) status.text = AssistantHealth.message }
    override fun onResume() { super.onResume(); refresh(); modelHandler.post(modelProgress) }
    override fun onPause() { modelHandler.removeCallbacks(modelProgress); super.onPause() }
    override fun onStop() { headsetProbeDialog?.dismiss(); headsetProbeDialog = null; headsetProbe?.close(); headsetProbe = null; conversation?.close(); conversation = null; previewDialog?.dismiss(); previewDialog = null; preview?.close(); preview = null; super.onStop() }
    override fun onDestroy() { files.shutdown(); super.onDestroy() }
    private fun openAppSettings() {
        for (intent in listOf(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")),
            Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )) {
            try {
                startActivity(intent)
                return
            } catch (_: android.content.ActivityNotFoundException) {
                // Vendor ROMs may omit a public settings entry point.
            } catch (_: SecurityException) {
                // Continue with another public entry point.
            }
        }
        status.text = "Откройте разрешения Ассистента в настройках телефона."
    }
    private fun openAssistantSettings() {
        // Some vendor role-request activities finish without showing a picker.
        // The public settings entry also lets an already selected user change assistants.
        for (action in listOf(Settings.ACTION_VOICE_INPUT_SETTINGS, Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, Settings.ACTION_SETTINGS)) {
            try {
                startActivity(Intent(action))
                return
            } catch (_: android.content.ActivityNotFoundException) {
                // Try the next public entry point when a ROM omits this screen.
            } catch (_: SecurityException) {
                // A vendor may restrict a specific entry point.
            }
        }
        status.text = "Откройте настройки телефона → Приложения по умолчанию → Цифровой помощник → Ассистент."
    }
    private fun showHeadsetProbe() {
        conversation?.close(); conversation = null
        headsetProbeDialog?.dismiss()
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(16)) }
        val live = TextView(this).apply { textSize = 18f; text = "Проверка длится 24 секунды. Говорите в микрофон гарнитуры всё это время.\nРечь не записывается; модели не загружаются."; panel.addView(this) }
        val meter = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; panel.addView(this) }
        val cue = Button(this).apply { text = "Проиграть ту-ду-ду"; isEnabled = false; panel.addView(this) }
        val start = Button(this).apply { text = "Начать проверку"; panel.addView(this) }
        val dialog = android.app.AlertDialog.Builder(this).setTitle("Звук гарнитуры")
            .setView(panel).setNegativeButton("Закрыть", null).create()
        headsetProbeDialog = dialog
        dialog.setOnDismissListener { headsetProbe?.close(); headsetProbe = null; headsetProbeDialog = null }
        cue.setOnClickListener { ReadyCue.play(this, false) { error -> if (error != null) live.text = error } }
        start.setOnClickListener {
            start.isEnabled = false
            headsetProbe = HeadsetAudioProbe(this, { text, level -> live.text = text; meter.progress = level; cue.isEnabled = true },
                { text -> live.text = text; meter.progress = 0; cue.isEnabled = false; start.isEnabled = true; headsetProbe = null })
                .also { it.start() }
        }
        dialog.show()
    }
    private fun showVoiceCheck() {
        conversation?.close(); conversation = null
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(12)) }
        val live = TextView(this).apply { textSize = 19f; text = "Запускаю…"; panel.addView(this) }
        val heard = TextView(this).apply { text = "Скажите фразу после сигнала"; setPadding(0, dp(8), 0, dp(8)); panel.addView(this) }
        val input = TextView(this).apply { text = "Микрофон телефона · ответ через динамик"; setPadding(0, dp(12), 0, dp(8)); panel.addView(this) }
        val meter = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; panel.addView(this) }
        val finish = Button(this).apply { text = "Закончить фразу"; isAllCaps = false; isEnabled = false; panel.addView(this) }
        val dialog = android.app.AlertDialog.Builder(this).setTitle("Проверка голоса")
            .setView(panel).setNegativeButton("Остановить", null).create()
        dialog.setOnDismissListener { conversation?.close(); conversation = null }
        dialog.show()
        finish.setOnClickListener { conversation?.finishUtterance() }
        conversation = VoiceConversation(this, { text ->
            status.text = text; live.text = text
            if (text == "Думаю локально…") input.text = "Речь распознана. Готовлю ответ…"
            finish.isEnabled = text == "Слушаю…" || conversation?.listening == true
        }, { conversation = null; finish.isEnabled = false }, requireHeadset = false,
            phoneMicrophone = true, microphoneLevel = { raw, silenced ->
                meter.progress = (raw * 100 / 1800).coerceIn(0, 100)
                input.text = if (silenced) "Android заглушил микрофон. Проверьте доступ к микрофону."
                    else if (raw < 15) "Микрофон: тишина · говорите после сигнала"
                    else "Микрофон: сигнал есть · уровень ${meter.progress}%"
            }, recognized = { text -> heard.text = "Распознано: $text" }).also { it.start() }
    }
    private fun stopForModelChange() {
        VoiceConversation.stopActive(); conversation = null; preview?.close(); preview = null
        status.text = "Модель выбрана. Начните новую сессию."
    }
    private fun voiceLabel(id: String): String = when (id) {
        "ruslan" -> "Piper · Ruslan"
        "custom" -> "Piper · импортированный голос"
        else -> "Silero · ${id.replaceFirstChar { it.uppercase() }}"
    }
    private fun showTtsPreview() {
        stopForModelChange()
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(12)) }
        val text = EditText(this).apply { setText("Привет! Я всё ещё жду тебя. Ёжик живёт у ёлки. Встретимся через десять минут?"); minLines = 3; panel.addView(this) }
        val live = TextView(this).apply { this.text = "Загружаю голос…"; panel.addView(this) }
        val play = Button(this).apply { this.text = "Прослушать"; isAllCaps = false; isEnabled = false; panel.addView(this) }
        val stop = Button(this).apply { this.text = "Стоп"; isAllCaps = false; panel.addView(this) }
        val dialog = android.app.AlertDialog.Builder(this).setTitle(voiceLabel(ModelCatalog.voice(this))).setView(panel).setNegativeButton("Закрыть", null).create()
        val voice = LocalVoice(this, phoneSpeaker = true); preview = voice; previewDialog = dialog
        dialog.setOnDismissListener { voice.close(); if (preview === voice) preview = null; if (previewDialog === dialog) previewDialog = null }
        dialog.show()
        voice.initialize { error -> live.text = error ?: "Готово. Ответ через динамик телефона."; play.isEnabled = error == null }
        play.setOnClickListener {
            if (text.text.isBlank()) { live.text = "Введите текст."; return@setOnClickListener }
            live.text = "Озвучиваю…"; play.isEnabled = false
            voice.speak(text.text.toString()) { error -> live.text = error ?: "Готово"; play.isEnabled = true }
        }
        stop.setOnClickListener { voice.stop(); live.text = "Остановлено"; play.isEnabled = true }
    }
    private fun showVoiceTuning() {
        val prefs = getSharedPreferences("settings", 0)
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(12)) }
        fun slider(key: String, title: String, lower: Int, upper: Int, default: Int) {
            val label = TextView(this).apply { textSize = 17f; panel.addView(this) }
            val seek = SeekBar(this).apply { max = (upper - lower) / 5; progress = (prefs.getInt(key, default) - lower) / 5; panel.addView(this) }
            fun update() { val value = lower + seek.progress * 5; label.text = "$title: $value%" }
            update()
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, user: Boolean) { update() }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) { prefs.edit().putInt(key, lower + seek.progress * 5).apply() }
            })
        }
        slider("tts_pitch", "Тональность", -30, 30, 0)
        slider("tts_rate", "Скорость", 70, 140, 100)
        android.app.AlertDialog.Builder(this).setTitle("Настройки Silero").setView(panel)
            .setPositiveButton("Готово", null).setNeutralButton("Сбросить") { _, _ -> prefs.edit().putInt("tts_pitch", 0).putInt("tts_rate", 100).apply() }.show()
    }
    private fun refresh() {
        val prefs = getSharedPreferences("settings", 0)
        if (::asrChoice.isInitialized) asrChoice.text = "Распознавание: " + if (prefs.getBoolean("tone_asr", false)) "T-one" else "Vosk"
        if (::ttsChoice.isInitialized) ttsChoice.text = "Голос: ${voiceLabel(ModelCatalog.voice(this))}"
        val role = getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT)
        val microphone = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val bluetooth = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        val devices = runCatching { AudioRoute.headsets(this) }.getOrDefault(emptyList())
        val saved = prefs.getString("headset_key", "").orEmpty()
        val chosen = devices.firstOrNull { AudioRoute.key(it) == saved }
        val deviceDescription = when {
            chosen != null -> "Выбрана ${chosen.productName}"
            devices.isNotEmpty() -> "Подключена ${devices.first().productName}"
            else -> "Микрофон и динамик телефона"
        }
        if (::capability.isInitialized) {
            capability.text = if (section == SettingsSection.AUDIO) "$deviceDescription\n${if (saved.isBlank()) "Автоматический выбор" else "Предпочтение сохранено; при отключении — текущее устройство"}"
            else "Ассистент: ${if (role) "выбран" else "не выбран"}\nМикрофон: ${if (microphone) "разрешён" else "не разрешён"}\nBluetooth: ${if (bluetooth) "разрешён" else "не разрешён"}\nЭкран: ${if (PhoneAccessibility.connected != null) "доступ включён" else "доступ выключен"}\nУведомления: ${if (PhoneNotifications.hasAccess(this)) "доступ включён" else "доступ выключен"}\nЗвонки: ${if (CallGuard.hasAccess(this)) "контроль состояния включён" else "через аудиофокус"}"
        }
        navigation.forEach { row ->
            val summary = when (row.section) {
                SettingsSection.ACCESS -> "${listOf(role, microphone, bluetooth).count { it }} из 3 основных доступов включены"
                SettingsSection.MODELS -> if (ModelDownload.busy || ModelDownload.importBusy) ModelDownload.message.ifBlank { "Подготавливаю модель…" }
                    else ModelCatalog.selectedBrain(this)?.name?.substringBefore("-Instruct")?.substringBefore("-Q")?.let { "Активна $it" } ?: "Выберите модель для ответов"
                SettingsSection.AUDIO -> deviceDescription
                SettingsSection.TESTS -> "Голос · канал · диагностика"
                SettingsSection.PERSONAL -> "Контакты, предпочтения и справка"
                SettingsSection.CLOCK -> "Таймеры · будильники · календарь"
                SettingsSection.SERVER -> if (BackendClient.endpoint(this).isBlank()) "Сервер пока не настроен" else "Сервер: ${android.net.Uri.parse(BackendClient.endpoint(this)).host.orEmpty()}"
                SettingsSection.HOME -> ""
            }
            row.update(summary)
        }
    }
    @Deprecated("Platform result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 23 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val result = runCatching {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readNBytes(4097) } ?: error("Не удалось открыть настройки.")
                require(bytes.size <= 4096) { "Файл настроек слишком большой." }
                val json = org.json.JSONObject(bytes.toString(Charsets.UTF_8))
                OtaClient.save(this, json.getString("endpoint"), json.getString("token"), json.optString("certificateSha256"), json.optBoolean("automatic", false))
            }
            result.onSuccess { recreate() }.onFailure { status.text = it.message ?: "Не удалось импортировать dev OTA." }
            return
        }
        if (requestCode == 22 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            status.text = "Сохраняю журнал…"
            files.execute {
                val result = runCatching {
                    contentResolver.openOutputStream(uri, "wt")?.use { InteractionJournal.export(this, it) }
                        ?: error("Не удалось открыть файл для записи.")
                }
                runOnUiThread { if (!isDestroyed) {
                    status.text = result.fold({ "Журнал сохранён: $it байт." }, { "Не удалось сохранить журнал: ${it.message}" })
                    journalStatus?.text = InteractionJournal.description(this)
                } }
            }
            return
        }
        if (requestCode == 21 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            importing = true; status.text = "Импортирую голос…"
            files.execute {
                val result = runCatching { contentResolver.openInputStream(uri)?.use { VoicePackage.import(this, it) } ?: error("Не удалось открыть пакет.") }
                runOnUiThread { importing = false; if (!isDestroyed) status.text = result.fold({ "Голос импортирован и выбран. Начните новую сессию." }, { it.message ?: "Ошибка импорта голоса" }) }
            }
            return
        }
        if (requestCode != 20 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (ModelDownload.busy || ModelDownload.importBusy) { status.text = "Дождитесь текущей загрузки или импорта."; return }
        ModelImporter.start(this, uri, { status.text = it }, { result ->
            if (!isDestroyed) { status.text = result.fold({ "Импортирована и активна ${it.name}." }, { it.message ?: "Ошибка импорта" }); refresh() }
        })
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults); refresh()
    }
}
