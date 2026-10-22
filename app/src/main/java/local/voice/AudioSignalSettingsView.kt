package local.voice

import android.content.Context
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** A silent visual preview; no microphone, player, palettes or network. */
internal class AudioSignalSettingsView(context: Context) : LinearLayout(context) {
    private val palette=AssistantTheme(context)
    private val preview=AudioSignalView(context).apply {
        spectrum={FloatArray(AudioSpectrum.BANDS) { i -> .15f+.6f*kotlin.math.exp(-kotlin.math.abs(i-10)/10f) }}
    }
    private val reset=Runnable { preview.mode=AudioSignalView.Mode.IDLE }
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    init {
        orientation=VERTICAL
        addView(TextView(context).apply { text="Чёрный фон. Белая линия.";textSize=22f;setTextColor(palette.text) })
        addView(preview,LayoutParams(-1,dp(140)))
        addView(TextView(context).apply {
            text="Линия показывает частоты вашего голоса во время записи и голоса ассистента во время ответа. В тишине она выпрямляется.\n\nУменьшение движения учитывает системную настройку анимаций."
            textSize=16f;setTextColor(palette.secondary)
        })
        addView(Button(context).apply {
            text="Показать пример без звука";isAllCaps=false;setTextColor(palette.text)
            background=palette.ripple(palette.surface,dp(18).toFloat());minHeight=dp(48)
            setOnClickListener {removeCallbacks(reset);preview.mode=AudioSignalView.Mode.LISTENING;postDelayed(reset,1800)}
        },LayoutParams(-1,-2).apply {topMargin=dp(20)})
    }
    override fun onDetachedFromWindow() {removeCallbacks(reset);preview.mode=AudioSignalView.Mode.IDLE;super.onDetachedFromWindow()}
}
