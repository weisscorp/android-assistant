package local.voice

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList

internal class AssistantTheme(context: Context, cinematic: Boolean = false) {
    private val dark = cinematic || context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val background = Color.parseColor(if (cinematic) "#060D16" else if (dark) "#111218" else "#F8F7FC")
    val surface = Color.parseColor(if (cinematic) "#101F2C" else if (dark) "#1E202A" else "#FFFFFF")
    val text = Color.parseColor(if (cinematic) "#EAF6FF" else if (dark) "#F2F1F8" else "#20202B")
    val secondary = Color.parseColor(if (cinematic) "#9BB3C8" else if (dark) "#ABAABB" else "#686778")
    val accent = Color.parseColor(if (cinematic) "#83EDFA" else if (dark) "#B5A0FF" else "#6850C9")
    val onAccent = Color.parseColor(if (cinematic) "#06202C" else if (dark) "#201738" else "#FFFFFF")
    val user = Color.parseColor(if (cinematic) "#123440" else if (dark) "#342A50" else "#EEE8FF")
    val stroke = Color.parseColor(if (cinematic) "#2A4154" else if (dark) "#363844" else "#E1DFEA")
    fun shape(color: Int, radius: Float, border: Boolean = false) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius
        if (border) setStroke(1, stroke)
    }
    fun ripple(color: Int, radius: Float) = RippleDrawable(ColorStateList.valueOf(Color.argb(40, Color.red(accent), Color.green(accent), Color.blue(accent))), shape(color, radius), shape(Color.WHITE, radius))
}
