package local.voice

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

/** Installed applications and user-editable spoken names are knowledge, not bundled recipes. */
class AppKnowledgeActivity : Activity() {
    private lateinit var catalog: AppKnowledge
    private lateinit var ui: SettingsUi
    private lateinit var palette: AssistantTheme
    private lateinit var list: LinearLayout
    private lateinit var summary: TextView
    private lateinit var filter: EditText
    private var apps = emptyList<AppKnowledge.App>()
    private var editorDialog: AlertDialog? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val observer: () -> Unit = { load() }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        palette = AssistantTheme(this); ui = SettingsUi(this, palette); catalog = AppKnowledge(this)
        val column = ui.column()
        ui.button(column, "← Параметры") { finish() }
        ui.heading(column, "Приложения и голосовые названия", 25f)
        ui.text(column, "Помощник сохраняет установленные приложения и имена, которыми их можно вызвать. Нажмите на приложение, чтобы задать русское имя или своё прозвище.")
        summary = ui.text(column, "Загружаю сохранённый каталог…")
        filter = EditText(this).apply {
            hint = "Найти приложение или голосовое имя"; setSingleLine(true)
            setTextColor(palette.text); setHintTextColor(palette.secondary); column.addView(this)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { render() }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        ui.button(column, "Обновить каталог") {
            summary.text = "Проверяю установленные приложения…"
            catalog.refreshAsync { failure -> if (!isDestroyed) { if (failure != null) summary.text = failure else load() } }
        }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; column.addView(this) }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(palette.background)
            addView(ScrollView(this@AppKnowledgeActivity).apply { addView(column) }, FrameLayout.LayoutParams(-1, -1))
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                val keyboard = insets.getInsets(android.view.WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom)); insets
            }
        })
    }
    override fun onStart() { super.onStart(); AppKnowledge.observe(observer); load() }
    override fun onStop() { AppKnowledge.unobserve(observer); super.onStop() }
    override fun onDestroy() { editorDialog?.dismiss(); worker.shutdownNow(); super.onDestroy() }
    private fun load() {
        if (isDestroyed || worker.isShutdown) return
        worker.execute {
            val result = runCatching { catalog.list() }
            runOnUiThread {
                if (!isDestroyed) result.onSuccess { apps = it; render() }.onFailure { summary.text = "Не удалось прочитать каталог." }
            }
        }
    }
    private fun render() {
        if (!::list.isInitialized) return
        list.removeAllViews()
        val query = filter.text.toString().trim()
        val visible = if (query.isBlank()) apps.take(61) else catalog.matching(query, 61)
        summary.text = "В базе: ${apps.size} приложений" + if (visible.size > 60) ". Уточните поиск для остальных." else "."
        if (visible.isEmpty()) ui.text(list, "Приложений с таким названием нет в сохранённом каталоге.")
        visible.take(60).forEach { app ->
            val row = ui.group(list, app.label, "Голосовое имя: ${app.russian}")
            ui.button(row, "Названия и прозвища") { editor(app) }
        }
    }
    private fun editor(app: AppKnowledge.App) {
        editorDialog?.dismiss()
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 12, 24, 12) }
        fun field(hint: String, value: String) = EditText(this).apply { this.hint = hint; setText(value); minLines = 1; column.addView(this) }
        val name = field("Русское имя", app.russian)
        val aliases = field("Другие названия, по одному в строке", catalog.aliases(app.pkg, userOnly = true).filter { it != app.russian }.joinToString("\n"))
        ui.text(column, "Автоматические варианты: ${catalog.aliases(app.pkg).joinToString(", ")}\n${app.pkg}", 13f)
        val error = ui.text(column, "")
        val dialog = AlertDialog.Builder(this).setTitle(app.label).setView(ScrollView(this).apply { addView(column) })
            .setPositiveButton("Сохранить", null).setNegativeButton("Отмена", null)
            .setNeutralButton("Автоматические имена") { _, _ -> catalog.resetNames(app.pkg); load() }.create()
        editorDialog = dialog
        dialog.setOnDismissListener { if (editorDialog === dialog) editorDialog = null }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                runCatching { catalog.saveNames(app.pkg, name.text.toString().trim(), aliases.text.toString().split('\n').map(String::trim).filter(String::isNotBlank)) }
                    .onSuccess { dialog.dismiss(); load() }.onFailure { error.text = it.message ?: "Не удалось сохранить имена." }
            }
        }
        dialog.show()
    }
}
