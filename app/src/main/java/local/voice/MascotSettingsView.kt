package local.voice

import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/** Native appearance controls. Selection is saved immediately and previewed by the shared renderer. */
internal class MascotSettingsView(context: Context) : LinearLayout(context) {
    private val theme = AssistantTheme(context, cinematic = true)
    private val ui = SettingsUi(context, theme)
    private val preferences = MascotColors.preferences(context)
    private val cards = mutableListOf<Pair<MascotColors, LinearLayout>>()
    private val choices = mutableListOf<TextView>()
    private val sliders = mutableListOf<Pair<String, SeekBar>>()
    private lateinit var selected: TextView
    private lateinit var custom: Button
    private val changes = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> updateSelection() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    init {
        orientation = VERTICAL
        val preview = ui.group(this, "Ваш маскот", "Коснитесь — он подпрыгнет или подмигнёт. Потяните — он потянется за пальцем.")
        preview.addView(AssistantCoreView(context).apply { id = R.id.mascot_preview }, LayoutParams(-1, dp(210)))
        selected = ui.text(preview, "", 16f, false).apply {
            id = R.id.mascot_palette_label
            gravity = Gravity.CENTER
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val presets = ui.group(this, "Палитры", "Цвета меняются сразу и сохраняются для всех экранов ассистента.")
        for (pair in MascotColors.presets.chunked(2)) {
            val row = LinearLayout(context).apply { orientation = HORIZONTAL }
            presets.addView(row, LayoutParams(-1, -2).apply { topMargin = dp(10) })
            pair.forEachIndexed { index, colors ->
                val card = LinearLayout(context).apply {
                    orientation = VERTICAL
                    minimumHeight = dp(96)
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    isClickable = true; isFocusable = true
                    tag = "mascot_palette_${colors.id}"
                    accessibilityDelegate = object : View.AccessibilityDelegate() {
                        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                            super.onInitializeAccessibilityNodeInfo(host, info)
                            info.className = Button::class.java.name
                            info.isCheckable = true; info.isChecked = host.isSelected
                        }
                    }
                    setOnClickListener { MascotColors.select(context, colors.id) }
                }
                card.addView(View(context).apply {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(colors.primary, colors.secondary, colors.accent)).apply {
                        cornerRadius = dp(12).toFloat()
                        setStroke(dp(1), Color.argb(70, 255, 255, 255))
                    }
                }, LayoutParams(-1, dp(32)))
                choices += TextView(context).apply {
                    textSize = 15f; setTextColor(theme.text)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    card.addView(this, LayoutParams(-1, -2).apply { topMargin = dp(10) })
                }
                cards += colors to card
                row.addView(card, LayoutParams(0, -1, 1f).apply { if (index == 0) rightMargin = dp(8) })
            }
        }
        val personal = ui.group(this, "Свои цвета", "Выберите отдельно верх, низ и цвет бликов. Каждый ползунок проходит через все цвета радуги.")
        custom = ui.button(personal, "Использовать свои цвета") { MascotColors.select(context, MascotColors.CUSTOM) }
            .apply { id = R.id.mascot_custom_palette }
        hueSlider(personal, "Верх", "primary_hue", 160, R.id.mascot_primary_hue)
        hueSlider(personal, "Низ", "secondary_hue", 265, R.id.mascot_secondary_hue)
        hueSlider(personal, "Блики и румянец", "accent_hue", 320, R.id.mascot_accent_hue)
        ui.text(this, "Нажатия на персонажа не запускают разговор. Анимации учитывают системное отключение движения.", 14f)
        updateSelection()
    }

    private fun hueSlider(parent: LinearLayout, title: String, key: String, default: Int, viewId: Int) {
        ui.text(parent, title, 16f, false).labelFor = viewId
        val slider = SeekBar(context).apply {
            id = viewId; min = 0; max = 359
            progress = preferences.getInt(key, default).coerceIn(0, 359)
            minHeight = dp(48)
            contentDescription = "$title: цвет маскота"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    seekBar.stateDescription = "$progress из 359"
                    if (fromUser) MascotColors.customHue(context, key, progress)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
            })
        }
        sliders += key to slider
        parent.addView(slider, LayoutParams(-1, dp(48)))
        // A static spectrum makes the slider's range legible without relying on hue names.
        parent.addView(View(context).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xffff4747.toInt(), 0xffffff47.toInt(), 0xff47ff47.toInt(), 0xff47ffff.toInt(), 0xff4747ff.toInt(), 0xffff47ff.toInt(), 0xffff4747.toInt()))
                .apply { cornerRadius = dp(3).toFloat() }
        }, LayoutParams(-1, dp(6)).apply { leftMargin = dp(16); rightMargin = dp(16); bottomMargin = dp(12) })
    }
    private fun updateSelection() {
        val colors = MascotColors.read(context)
        selected.text = "Палитра: ${colors.title}"
        cards.forEachIndexed { index, (option, card) ->
            val active = colors.id == option.id
            card.isSelected = active
            card.contentDescription = "${option.title}, ${if (active) "выбрано" else "выбрать палитру"}"
            card.background = theme.ripple(if (active) theme.user else theme.background, dp(16).toFloat())
                .apply { (getDrawable(0) as GradientDrawable).setStroke(dp(if (active) 2 else 1), if (active) option.primary else theme.stroke) }
            choices[index].text = if (active) "✓ ${option.title}" else option.title
        }
        custom.isSelected = colors.id == MascotColors.CUSTOM
        custom.text = if (custom.isSelected) "✓ Свои цвета выбраны" else "Использовать свои цвета"
        sliders.forEachIndexed { index, (key, slider) ->
            val defaults = intArrayOf(160, 265, 320)
            val hue = preferences.getInt(key, defaults[index]).coerceIn(0, 359)
            slider.progress = hue
            slider.stateDescription = "$hue из 359"
            val color = Color.HSVToColor(floatArrayOf(hue.toFloat(), .72f, 1f))
            slider.progressTintList = ColorStateList.valueOf(color)
            slider.thumbTintList = ColorStateList.valueOf(color)
        }
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        preferences.registerOnSharedPreferenceChangeListener(changes)
        updateSelection()
    }
    override fun onDetachedFromWindow() {
        preferences.unregisterOnSharedPreferenceChangeListener(changes)
        super.onDetachedFromWindow()
    }
}
