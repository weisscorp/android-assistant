package local.voice

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.*
import android.text.InputType
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/** Share target always asks what to remember. Shared text is data, never an assistant command. */
class SourcesActivity : Activity() {
    private val palette by lazy { AssistantTheme(this, cinematic = true) }
    private val ui by lazy { SettingsUi(this, palette) }
    private val store by lazy { SavedSources(this) }
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private var editorId = ""
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.statusBarColor = palette.background; window.navigationBarColor = palette.background
        editorId = (state?.getString("editorId") ?: intent.getStringExtra("source_id"))?.takeIf { it.matches(Regex("[a-f0-9-]{36}")) } ?: UUID.randomUUID().toString()
        if (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_VIEW || intent.hasExtra("source_id") || state?.getBoolean("editing") == true) editor()
        else menu()
    }
    private fun page(title: String, subtitle: String) {
        content = ui.column()
        ui.button(content, "← Назад") { finish() }
        ui.heading(content, title, 30f)
        ui.text(content, subtitle)
        status = ui.text(content, "").apply { accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(palette.background); addView(content)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                val ime = insets.getInsets(android.view.WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom)); insets
            }
        })
    }
    private fun menu() {
        page("Знания и источники", "Ваши названия, связи и правила для помощника")
        ui.button(content, "Добавить знание", primary = true) { editorId = UUID.randomUUID().toString(); editor() }
        val items = store.list()
        if (items.isEmpty()) ui.text(content, "Например: «Рабочий чат» → чат Telegram, «Документация» → веб-страница. Можно вставить адрес вручную или отправить ссылку, текст, место либо картинку через «Поделиться» → «Ассистент».")
        for (source in items) {
            val group = ui.group(content, source.name, source.kind.title)
            ui.text(group, if (source.kind == SourceKind.TELEGRAM) source.value else if (source.kind == SourceKind.WEB) Uri.parse(source.value).host.orEmpty() else "Сохранено на телефоне")
            if (source.instructions.isNotBlank()) ui.text(group, source.instructions.take(200))
            if (source.participant.isNotBlank()) ui.text(group, "Участник: ${source.participant}")
            ui.button(group, "Изменить") { startActivityForResult(Intent(this, SourcesActivity::class.java).putExtra("source_id", source.id), 44) }
            ui.button(group, "Удалить") {
                android.app.AlertDialog.Builder(this).setTitle("Удалить ${source.name}?").setMessage("Будет удалена сохранённая связь и её снимок. Сам чат не изменится.")
                    .setPositiveButton("Удалить") { _, _ -> store.delete(source.id); menu() }.setNegativeButton("Отмена", null).show()
            }
        }
        val reverse = ui.group(content, "Названия улиц", "Адрес и перекрёсток берутся из источника. Для точек без адреса можно подключить свой сервис карты.")
        val endpoint = field(reverse, "HTTPS-адрес своего reverse-geocoder (необязательно)", getSharedPreferences("settings", 0).getString("presence_geocoder", "").orEmpty(), R.id.source_geocoder)
        ui.text(reverse, "По запросу ему передаются координаты точки. Совместим с reverse API собственного Nominatim. Пустое поле отключает запросы карты.")
        ui.button(reverse, "Сохранить сервис карты") {
            val value = endpoint.text.toString().trim()
            if (value.isNotBlank() && (!PresenceHttp.validUrl(value) || Uri.parse(value).host.equals("nominatim.openstreetmap.org", true))) status.text = "Укажите HTTPS-адрес своего сервиса карты. Публичный Nominatim не используется для отслеживания людей."
            else { getSharedPreferences("settings", 0).edit().putString("presence_geocoder", value).apply(); status.text = "Сервис карты сохранён." }
        }
        ui.text(content, "Скажите «Прочитай источник Рабочий чат», используя сохранённое название. Для геопозиций сообщаются источник и время обновления. Telegram: карточка геопозиции и видимые сообщения выбранного чата. Чтение ссылки из сообщений включается отдельно у знания; несколько ссылок требуют выбора. Расстояние считается по прямой; направление определяется по свежим обновлениям. Картинка остаётся снимком. Чтение Telegram требует разблокировки и доступа к экрану.")
    }
    private fun field(parent: LinearLayout, hint: String, value: String, fieldId: Int): EditText = EditText(this).apply {
        id = fieldId; this.hint = hint; setText(value); setTextColor(palette.text); setHintTextColor(palette.secondary); textSize = 16f
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; minHeight = (56 * resources.displayMetrics.density).toInt()
        setPadding(12, 16, 12, 16); parent.addView(this, LinearLayout.LayoutParams(-1, -2))
    }
    private fun sharedUri(): Uri? = if (intent.action == Intent.ACTION_SEND && intent.type?.startsWith("image/") == true)
        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) ?: intent.clipData?.getItemAt(0)?.uri else null
    private fun editor() {
        val existing = store.get(editorId)
        val incoming = PresenceFacts.sharedReference((if (intent.action == Intent.ACTION_VIEW && intent.data?.scheme == "geo") intent.data.toString()
            else intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()).take(16000))
        val uri = sharedUri()
        val initial = existing?.kind ?: when {
            uri != null -> SourceKind.IMAGE
            PresenceFacts.pointFromLink(incoming) != null -> SourceKind.PLACE
            TelegramPresence.isTelegramLink(incoming.trim()) -> SourceKind.TELEGRAM
            PresenceHttp.validUrl(incoming.trim()) -> SourceKind.WEB
            else -> SourceKind.NOTE
        }
        page("Что запомнить?", "Назовите знание и объясните, для чего оно нужно")
        val group = ui.group(content, "Связь с источником")
        val name = field(group, "Название знания или источника", existing?.name.orEmpty(), R.id.source_name)
        val kind = Spinner(this).apply {
            id = R.id.source_kind
            adapter = ArrayAdapter(this@SourcesActivity, android.R.layout.simple_spinner_dropdown_item, SourceKind.entries.map { it.title })
            setSelection(initial.ordinal); group.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        val value = field(group, "Имя чата, HTTPS-ссылка, geo:точка или текст", existing?.value ?: incoming, R.id.source_value)
        val instructions = field(group, "Что это и когда использовать? Например: здесь новости проекта", existing?.instructions.orEmpty(), R.id.source_instructions)
        val participant = field(group, "Точное имя участника / машины (если их несколько)", existing?.participant.orEmpty(), R.id.source_participant)
        val aliases = field(group, "Другие названия через ; (необязательно)", existing?.aliases?.joinToString("; ").orEmpty(), R.id.source_aliases)
        val follow = CheckBox(this).apply {
            id = R.id.source_follow_links; text = "В Telegram читать единственную HTTPS-ссылку из видимых сообщений"
            setTextColor(palette.text); isChecked = existing?.followLinks == true; group.addView(this)
        }
        val advanced = ui.group(group, "Правило чтения данных", "Необязательно. Хранится у этого знания; приложение не требует обновления для нового сайта.")
        val recipe = field(advanced, "JSON-правило для пути и полей данных", existing?.readerRecipe.orEmpty(), R.id.source_recipe)
        recipe.typeface = android.graphics.Typeface.MONOSPACE
        ui.text(advanced, "Обычный JSON и текст страницы читаются без правила. Для отдельного адреса данных можно указать path на том же сервере, inheritQuery и fields с JSON Pointer путями. Правило только читает; код и команды не выполняются.")
        ui.text(advanced, "Пример: {\"path\":\"\",\"fields\":{\"latitude\":\"/location/latitude\",\"longitude\":\"/location/longitude\",\"updated_at\":\"/location/updated_at\"}}")
        advanced.visibility = android.view.View.GONE
        ui.button(group, "Правило чтения данных ▾") { advanced.visibility = if (advanced.visibility == android.view.View.VISIBLE) android.view.View.GONE else android.view.View.VISIBLE }
        if (initial == SourceKind.IMAGE) {
            ui.text(group, "Картинка будет скопирована в личное хранилище после сохранения. Она не становится трансляцией геопозиции.")
            val image = ImageView(this).apply { adjustViewBounds = true; contentDescription = "Полученное изображение"; group.addView(this, LinearLayout.LayoutParams(-1, (180 * resources.displayMetrics.density).toInt())) }
            worker.execute {
                val bitmap = runCatching {
                    val bytes = (if (existing != null) store.image(existing).inputStream() else uri?.takeIf { it.scheme == "content" }?.let { contentResolver.openInputStream(it) })
                        ?.use { stream -> val bytes = stream.readNBytes(10 * 1024 * 1024 + 1); require(bytes.size <= 10 * 1024 * 1024); bytes } ?: return@runCatching null
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    require(bounds.outWidth in 1..20000 && bounds.outHeight in 1..20000)
                    val options = BitmapFactory.Options().apply { inSampleSize = 1; while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 800) inSampleSize *= 2 }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                }.getOrNull()
                runOnUiThread { if (isDestroyed || image.parent == null) bitmap?.recycle() else image.setImageBitmap(bitmap) }
            }
        }
        ui.text(group, "Telegram: имя чата или ссылка t.me на чат/сообщение. Для сайта укажите страницу или адрес данных. Особенности источника сохраняются в знании. Место: geo:41.65,41.64 или ссылка на точку карты. Ничего не открывается и не скачивается до вашего запроса.")
        ui.button(group, "Сохранить", primary = true) {
            val button = content.findViewById<Button>(R.id.source_save)
            val chosen = SourceKind.entries[kind.selectedItemPosition]
            val draft = SavedSource(editorId, name.text.toString().trim(), chosen, value.text.toString().trim(), participant.text.toString().trim(),
                aliases.text.toString().split(';').map(String::trim).filter(String::isNotBlank), if (chosen == SourceKind.IMAGE) "$editorId.image" else "", instructions = instructions.text.toString(), followLinks = chosen == SourceKind.TELEGRAM && follow.isChecked, readerRecipe = if (chosen == SourceKind.WEB) recipe.text.toString().trim() else "")
            if (!PresenceFacts.validName(draft.name)) { status.text = "Введите название знания или источника."; return@button }
            button.isEnabled = false; status.text = "Сохраняю…"
            worker.execute {
                val result = runCatching {
                    var copied = false
                    try {
                        if (chosen == SourceKind.IMAGE && !store.image(draft).isFile) { copyImage(uri ?: error("Поделитесь изображением заново."), store.image(draft)); copied = true }
                        store.save(draft)
                    } catch (error: Throwable) { if (copied) store.image(draft).delete(); throw error }
                }
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    button.isEnabled = true
                    result.onSuccess { status.text = "Сохранено: ${draft.name}. Скажите «прочитай источник ${draft.name}»."; button.isEnabled = false }
                        .onFailure { status.text = it.message ?: "Не удалось сохранить источник." }
                }
            }
        }.apply { id = R.id.source_save }
        ui.button(group, "К списку знаний") { editorId = UUID.randomUUID().toString(); menu() }
    }
    private fun copyImage(uri: Uri, target: File) {
        require(uri.scheme == "content") { "Изображение должно быть передано через «Поделиться»." }
        val directory = target.parentFile!!.apply { mkdirs() }
        require(directory.listFiles().orEmpty().sumOf { it.length() } < 100 * 1024 * 1024) { "Хранилище изображений заполнено; удалите ненужные источники." }
        val temp = File(directory, "${target.name}.tmp")
        try {
            contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use { out ->
                var size = 0; val buffer = ByteArray(8192)
                while (true) { val count = input.read(buffer); if (count < 0) break; size += count; require(size <= 10 * 1024 * 1024) { "Изображение больше10МБ." }; out.write(buffer, 0, count) }
            } } ?: error("Приложение не дало доступ к изображению.")
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(temp.path, options)
            require(options.outWidth in 1..20000 && options.outHeight in 1..20000 && options.outWidth.toLong() * options.outHeight <= 100_000_000) { "Не удалось прочитать изображение или оно слишком большое." }
            check(temp.renameTo(target)) { "Не удалось сохранить изображение." }
        } finally { temp.delete() }
    }
    override fun onSaveInstanceState(state: Bundle) { state.putString("editorId", editorId); state.putBoolean("editing", findViewById<EditText>(R.id.source_name) != null); super.onSaveInstanceState(state) }
    @Deprecated("Platform result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) { super.onActivityResult(requestCode, resultCode, data); if (requestCode == 44) menu() }
    override fun onDestroy() { worker.shutdown(); super.onDestroy() }
}
