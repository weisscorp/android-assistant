package local.voice

import android.content.Context
import android.graphics.Color

/** User appearance, independent of conversation state. No transcript or device data is stored here. */
internal data class MascotColors(val id: String, val title: String, val primary: Int, val secondary: Int, val accent: Int) {
    companion object {
        const val PREFERENCES = "mascot_appearance"
        const val CUSTOM = "custom"
        val presets = listOf(
            MascotColors("aurora", "Аврора", 0xff38f5c7.toInt(), 0xff7955ff.toInt(), 0xffff79db.toInt()),
            MascotColors("candy", "Конфета", 0xffff87cc.toInt(), 0xff975cff.toInt(), 0xff75f3ff.toInt()),
            MascotColors("sunset", "Закат", 0xffffd65c.toInt(), 0xffff596f.toInt(), 0xffffb6eb.toInt()),
            MascotColors("ocean", "Океан", 0xff57f3ff.toInt(), 0xff4261ff.toInt(), 0xffb097ff.toInt()),
            MascotColors("lime", "Лайм", 0xffd1ff52.toInt(), 0xff16dca2.toInt(), 0xffffffad.toInt()),
            MascotColors("cosmos", "Космос", 0xffb985ff.toInt(), 0xffe94cbd.toInt(), 0xff77eeff.toInt())
        )
        fun preferences(context: Context) = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        fun read(context: Context): MascotColors {
            val prefs = preferences(context)
            val id = prefs.getString("palette", presets.first().id)
            if (id != CUSTOM) return presets.firstOrNull { it.id == id } ?: presets.first()
            return MascotColors(CUSTOM, "Свои цвета", hue(prefs.getInt("primary_hue", 160)),
                hue(prefs.getInt("secondary_hue", 265)), hue(prefs.getInt("accent_hue", 320)))
        }
        fun select(context: Context, id: String) {
            require(id == CUSTOM || presets.any { it.id == id })
            preferences(context).edit().putString("palette", id).apply()
        }
        fun customHue(context: Context, key: String, value: Int) {
            require(key in setOf("primary_hue", "secondary_hue", "accent_hue"))
            preferences(context).edit().putString("palette", CUSTOM).putInt(key, value.coerceIn(0, 359)).apply()
        }
        private fun hue(degrees: Int) = Color.HSVToColor(floatArrayOf(degrees.coerceIn(0, 359).toFloat(), .72f, 1f))
    }
}
