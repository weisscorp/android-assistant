package local.voice

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*

/** Catalog controls keep large downloads and native evaluation outside the UI thread. */
class ModelCatalogActivity : Activity() {
    private val palette by lazy { AssistantTheme(this) }
    private val main = Handler(Looper.getMainLooper())
    private lateinit var rows: LinearLayout
    private lateinit var status: TextView
    private var structure = ""
    private var ownsOperation = false
    private data class Row(val entry: ModelMetadata?, val id: String, val state: TextView, val detail: TextView,
        val download: Button?, val select: Button, val benchmark: Button, val delete: Button)
    private val controls = mutableListOf<Row>()
    private val update = object : Runnable {
        override fun run() { if (!isDestroyed) { refresh(); main.postDelayed(this, 500) } }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun text(parent: LinearLayout, value: String, size: Float = 15f) = TextView(this).apply {
        text = value; textSize = size; setTextColor(palette.text); setPadding(0, dp(6), 0, dp(6)); parent.addView(this)
    }
    private fun button(parent: LinearLayout, value: String, action: () -> Unit) = Button(this).apply {
        text = value; isAllCaps = false; minHeight = dp(44); setTextColor(palette.text)
        background = palette.ripple(palette.surface, dp(12).toFloat()); setOnClickListener { action() }
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(24)); setBackgroundColor(palette.background) }
        button(layout, "← Настройки") { finish() }
        text(layout, "Локальные модели", 28f)
        text(layout, "Скачайте модель, выберите активную и сравните скорость на этом телефоне. Каталог: до 2 ГиБ на файл.")
        status = text(layout, "")
        button(layout, "Импорт своего GGUF") {
            if (!busy()) startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE) }, 20)
            else status.text = "Дождитесь текущей операции."
        }
        button(layout, "Отменить загрузку / benchmark") {
            if (ModelDownload.busy) ModelDownload.cancel(this)
            if (LocalBrain.maintenanceBusy) LocalBrain.cancel()
        }
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; layout.addView(rows)
        setContentView(ScrollView(this).apply { addView(layout) })
        refresh(); main.post(update); identifyLegacyModel()
    }
    private fun busy() = ModelDownload.busy || ModelDownload.importBusy || LocalBrain.maintenanceBusy
    private fun refresh() {
        val installed = ModelCatalog.brains(this)
        val catalog = BuiltinModels.available(this)
        val key = installed.joinToString { "${it.id}:${it.metadata?.sha256}" } + catalog.joinToString { it.id }
        if (structure != key) {
            structure = key; rows.removeAllViews(); controls.clear()
            catalog.forEach { addRow(it, it.id, it.name) }
            installed.filter { brain -> catalog.none { it.sha256 == brain.metadata?.sha256 } }.forEach { addRow(null, it.id, it.name) }
            if (catalog.isEmpty()) text(rows, "В этой сборке нет проверенных моделей для текущего runtime. Можно импортировать свой GGUF.")
        }
        val active = ModelCatalog.selectedBrain(this)?.id
        controls.forEach { row ->
            val brain = if (row.entry != null) ModelCatalog.installed(this, row.entry) else installed.firstOrNull { it.id == row.id }
            val downloading = ModelDownload.busy && row.entry?.id == ModelDownload.modelId
            val state = when { brain?.id == active && brain != null -> "active · активна"; downloading -> "downloading · скачивается"; brain != null -> "downloaded · скачана"; else -> "Не скачана" }
            val metadata = row.entry ?: brain?.metadata
            val size = metadata?.size ?: brain?.file?.length() ?: 0
            row.state.text = "$state · ${size / 1024 / 1024} МиБ"
            row.detail.text = buildString {
                metadata?.let { append("${it.family} · ${it.quantization} · контекст ${it.contextSize} · ${it.recommendedThreads} потока\n") }
                metadata?.languageNote?.takeIf { it.isNotBlank() }?.let { append(it); append('\n') }
                if (downloading) append(ModelDownload.message)
                if (brain != null) {
                    ModelBenchmark.read(this@ModelCatalogActivity, brain)?.let { append(it.message()) }
                        ?: append("Benchmark ещё не выполнен.")
                }
            }
            row.download?.isEnabled = brain == null && !busy()
            row.select.isEnabled = brain != null && brain.id != active && !busy()
            row.benchmark.isEnabled = brain != null && !busy()
            row.delete.isEnabled = brain != null && !busy()
        }
        if (ModelDownload.busy) status.text = ModelDownload.message
    }
    private fun addRow(entry: ModelMetadata?, id: String, name: String) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16))
            background = palette.ripple(palette.surface, dp(16).toFloat())
        }
        rows.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        text(card, name, 19f)
        val state = text(card, "")
        val detail = text(card, "")
        fun installed(): ModelCatalog.Brain? = if (entry != null) ModelCatalog.installed(this, entry) else ModelCatalog.brains(this).firstOrNull { it.id == id }
        val download = entry?.let { model -> button(card, "Скачать") {
            AlertDialog.Builder(this).setTitle("Скачать ${model.name}?")
                .setMessage("${model.size / 1024 / 1024} МиБ через текущее соединение. Источник: ${model.repo}. Лицензия: ${model.license}.")
                .setPositiveButton("Скачать") { _, _ -> ModelDownload.start(this, model.id); refresh() }
                .setNegativeButton("Отмена", null).show()
        } }
        val select = button(card, "Выбрать активной") {
            installed()?.let { brain ->
                VoiceConversation.stopActive(); ownsOperation = true; status.text = "Проверяю ${brain.name}…"
                LocalBrain.validate(brain.file, brain.metadata?.contextSize ?: 2048) { result ->
                    ownsOperation = false
                    if (isDestroyed) return@validate
                    result.onSuccess { info ->
                        if (brain.metadata == null) {
                            // Legacy/custom files acquire their actual GGUF metadata on first selection.
                            // SHA hashing is done outside the UI below, not assumed from the filename.
                            legacyMetadata(brain, info)
                        } else { ModelCatalog.selectBrain(this, brain); status.text = "Активна ${brain.name}."; refresh() }
                    }.onFailure { status.text = it.message ?: "Модель не прошла проверку." }
                }
            }
        }
        val benchmark = button(card, "Benchmark") {
            installed()?.let { brain ->
                VoiceConversation.stopActive(); ownsOperation = true; status.text = "Benchmark ${brain.name}: холодная загрузка, новый контекст, до 32 токенов ответа…"
                LocalBrain.runBenchmark(this, brain, brain.metadata?.recommendedThreads ?: 4) { result ->
                    ownsOperation = false
                    if (!isDestroyed) { status.text = result.fold({ "Benchmark сохранён для ${brain.name}." }, { it.message ?: "Benchmark не завершён." }); refresh() }
                }
            }
        }
        val delete = button(card, "Удалить") {
            installed()?.let { brain -> AlertDialog.Builder(this).setTitle("Удалить ${brain.name}?")
                .setMessage("Освободится ${brain.file.length() / 1024 / 1024} МиБ. Файл и его benchmark будут удалены.")
                .setPositiveButton("Удалить") { _, _ -> VoiceConversation.stopActive(); LocalBrain.cancel(); LocalBrain.trimIdle()
                    runCatching { ModelCatalog.deleteBrain(this, brain) }.onFailure { status.text = it.message }
                    refresh()
                }.setNegativeButton("Отмена", null).show() }
        }
        controls += Row(entry, id, state, detail, download, select, benchmark, delete)
    }
    private fun legacyMetadata(brain: ModelCatalog.Brain, info: GgufMetadata) {
        ownsOperation = true
        Thread {
            val result = runCatching { ModelMetadata.digest(brain.file) }.map { digest ->
                BuiltinModels.available(this).firstOrNull { it.sha256 == digest } ?: ModelMetadata.imported(info, brain.file, brain.name, digest)
            }
            main.post {
                ownsOperation = false
                if (isDestroyed) return@post
                result.onSuccess { ModelCatalog.saveMetadata(this, brain.id, it); ModelCatalog.selectBrain(this, brain) }
                    .onFailure { status.text = it.message }
                refresh()
            }
        }.start()
    }
    private fun identifyLegacyModel() {
        val legacy = ModelCatalog.brains(this).firstOrNull { it.id == "legacy" && it.metadata == null } ?: return
        Thread {
            val known = runCatching {
                val candidates = BuiltinModels.available(this).filter { it.size == legacy.file.length() }
                if (candidates.isEmpty()) null else {
                    val digest = ModelMetadata.digest(legacy.file)
                    candidates.firstOrNull { it.sha256 == digest }
                }
            }.getOrNull()
            main.post {
                if (!isDestroyed && legacy.file.isFile && known != null) {
                    ModelCatalog.saveMetadata(this, legacy.id, known); refresh()
                }
            }
        }.start()
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 20 && resultCode == RESULT_OK) data?.data?.let { uri ->
            ModelImporter.start(this, uri, { status.text = it }, { result ->
                if (!isDestroyed) { status.text = result.fold({ "Импортирована и активна ${it.name}." }, { it.message ?: "Импорт не выполнен." }); refresh() }
            })
        }
    }
    override fun onDestroy() { main.removeCallbacks(update); if (ownsOperation) LocalBrain.cancel(); super.onDestroy() }
}
