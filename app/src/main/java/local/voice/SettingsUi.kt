package local.voice

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

internal enum class SettingsSection(val title: String, val subtitle: String, val icon: Int) {
    HOME("Параметры", "Настройте помощника под себя", R.drawable.ic_settings),
    ACCESS("Доступы", "Разрешения и управление телефоном", R.drawable.ic_settings_access),
    MODELS("Модели", "Распознавание, ответы и голос", R.drawable.ic_settings_models),
    AUDIO("Аудиоустройства", "Гарнитура, микрофон и звучание", R.drawable.ic_settings_audio),
    APPEARANCE("Оформление", "Чёрный фон и линия аудиосигнала", R.drawable.ic_settings_appearance),
    TESTS("Проверки", "Подключение и работа помощника", R.drawable.ic_settings_tests),
    PERSONAL("Поведение и память", "Контакты, навигация и команды", R.drawable.ic_settings_personal),
    CLOCK("Время и напоминания", "Таймеры, секундомер и календарь", R.drawable.ic_settings_clock),
    SERVER("Hermes и обновления", "Домашний агент и новые версии", R.drawable.ic_settings_server)
}

/** Shared native settings controls; all content grows with Android's font scale. */
internal class SettingsUi(private val context: Context, private val palette: AssistantTheme) {
    data class NavigationRow(val view: View, val summary: TextView, val section: SettingsSection) {
        fun update(value: String) {
            if (summary.text != value) summary.text = value
            view.contentDescription = "${section.title}. ${section.subtitle}. $value"
        }
    }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun column() = object : LinearLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = minOf(MeasureSpec.getSize(widthMeasureSpec), dp(680))
            super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightMeasureSpec)
        }
    }.apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(28)) }
    fun text(parent: LinearLayout, value: String, size: Float = 15f, muted: Boolean = true): TextView = TextView(context).apply {
        text = value; textSize = size; setTextColor(if (muted) palette.secondary else palette.text)
        setLineSpacing(dp(3).toFloat(), 1f)
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6); bottomMargin = dp(8) })
    }
    fun heading(parent: LinearLayout, title: String, size: Float = 19f) = text(parent, title, size, false).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); isAccessibilityHeading = true
    }
    fun button(parent: LinearLayout, title: String, primary: Boolean = false, action: () -> Unit) = Button(context).apply {
        text = title; textSize = 16f; isAllCaps = false; minHeight = dp(52)
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        setTextColor(if (primary) palette.onAccent else palette.text)
        background = palette.ripple(if (primary) palette.accent else palette.user, dp(16).toFloat())
        setOnClickListener { action() }
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }
    fun group(parent: LinearLayout, title: String, description: String = ""): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(16))
        background = palette.shape(palette.surface, dp(24).toFloat(), true)
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        heading(this, title)
        if (description.isNotBlank()) text(this, description)
    }
    fun navigation(parent: LinearLayout, section: SettingsSection, compact: Boolean = false, action: () -> Unit): NavigationRow {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(if (compact) 12 else 18), dp(16), dp(if (compact) 12 else 18))
            minimumHeight = dp(if (compact) 76 else 108)
            background = palette.ripple(palette.surface, dp(24).toFloat())
            isClickable = true; isFocusable = true
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info); info.className = Button::class.java.name
                }
            }
            setOnClickListener { action() }
        }
        val icon = ImageView(context).apply {
            setImageResource(section.icon); imageTintList = android.content.res.ColorStateList.valueOf(palette.accent)
            background = palette.shape(palette.user, dp(16).toFloat()); setPadding(dp(10), dp(10), dp(10), dp(10))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        card.addView(icon, LinearLayout.LayoutParams(dp(48), dp(48)))
        val copy = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        card.addView(copy, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(16); rightMargin = dp(8) })
        heading(copy, section.title, if (compact) 17f else 20f).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) }
        }
        if (!compact) text(copy, section.subtitle, 14f).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) }
        }
        val summary = text(copy, "", 13f).apply {
            setTextColor(palette.accent); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        card.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_settings_next); imageTintList = android.content.res.ColorStateList.valueOf(palette.secondary)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(20), dp(20)))
        parent.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        return NavigationRow(card, summary, section)
    }
}
