package local.voice

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList

/** Black surfaces and square black buttons with a 3dp white outline. */
internal class AssistantTheme(context: Context, cinematic: Boolean = false) {
    private val buttonStroke = (3 * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
    val background = Color.BLACK
    val surface = Color.BLACK
    val text = Color.WHITE
    val secondary = Color.rgb(170,170,170)
    val accent = Color.WHITE
    val onAccent = Color.WHITE
    val user = Color.BLACK
    val stroke = Color.rgb(65,65,65)
    fun shape(color: Int, radius: Float, border: Boolean = false) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius
        if (border) setStroke(1, stroke)
    }
    fun ripple(color: Int, radius: Float) = RippleDrawable(
        ColorStateList.valueOf(Color.argb(45,255,255,255)),
        GradientDrawable().apply {
            setColor(Color.BLACK); cornerRadius = 0f; setStroke(buttonStroke, Color.WHITE)
        }, shape(Color.WHITE,0f))
}
