package local.voice

import android.animation.ValueAnimator
import android.content.Context
import android.database.ContentObserver
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import kotlin.math.*

/** One monochrome line, driven by actual microphone or playback FFT bands. */
internal class AudioSignalView(context: Context) : View(context) {
    enum class Mode { IDLE, LOADING, LISTENING, THINKING, SPEAKING, WORKING, ERROR }
    var spectrum: () -> FloatArray = { VoiceConversation.activeAudioSpectrum }
    var mode = Mode.IDLE
        set(value) { field=value; contentDescription=when(value) {
            Mode.LISTENING -> "Аудиосигнал · ваш голос"; Mode.SPEAKING -> "Аудиосигнал · голос ассистента"
            Mode.ERROR -> "Аудиосигнал · требуется помощь"; else -> "Аудиосигнал · ожидание"
        }; invalidate(); schedule() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.WHITE; style=Paint.Style.STROKE; strokeCap=Paint.Cap.ROUND; strokeJoin=Paint.Join.ROUND }
    private val path=Path()
    private val bands=FloatArray(AudioSpectrum.BANDS)
    private var visible=false
    private var pending=false
    private var previous=0L
    private var phase=0f
    private val frame=Runnable { pending=false; if(canAnimate())invalidate() }
    private val observer=object:ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange:Boolean) { removeCallbacks(frame);pending=false;previous=0;bands.fill(0f);invalidate();schedule() }
    }
    init { isClickable=false; isFocusable=false; isSoundEffectsEnabled=false; contentDescription="Аудиосигнал · ожидание" }
    private fun canAnimate()=visible && isAttachedToWindow && isShown && ValueAnimator.areAnimatorsEnabled() && (mode==Mode.LISTENING || mode==Mode.SPEAKING)
    private fun schedule() { if(!pending && canAnimate()) {pending=true;postDelayed(frame,33)} }
    override fun onAttachedToWindow() {super.onAttachedToWindow();context.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),false,observer);schedule()}
    override fun onDetachedFromWindow() {removeCallbacks(frame);pending=false;context.contentResolver.unregisterContentObserver(observer);super.onDetachedFromWindow()}
    override fun onVisibilityAggregated(isVisible:Boolean) {super.onVisibilityAggregated(isVisible);visible=isVisible;previous=0;removeCallbacks(frame);pending=false;if(isVisible){invalidate();schedule()}}
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        val animated=canAnimate()
        val now=SystemClock.uptimeMillis()
        val dt=if(previous==0L)1f/30 else (now-previous).coerceIn(1,100)/1000f
        previous=now
        val audio=if(animated)spectrum() else FloatArray(AudioSpectrum.BANDS)
        for(i in bands.indices) {
            val target=audio.getOrElse(i){0f}.takeIf {it.isFinite()}?.coerceIn(0f,1f) ?: 0f
            bands[i]=if(animated)bands[i]+(target-bands[i])*(1-exp(-dt*if(target>bands[i])28f else 12f)) else 0f
        }
        if(animated)phase+=dt*22f
        val density=resources.displayMetrics.density
        val left=16*density;val span=(width-2*left).coerceAtLeast(0f);val center=height/2f
        val amplitude=min(height*.36f,58*density)
        paint.strokeWidth=1.8f*density
        path.rewind()
        for(i in 0..160) {
            val t=i/160f
            val f=(abs(t-.5f)*2*(bands.size-1)).coerceIn(0f,(bands.size-1).toFloat())
            val low=f.toInt();val high=min(low+1,bands.lastIndex)
            val energy=bands[low]+(bands[high]-bands[low])*(f-low)
            val envelope=sin(PI*t).toFloat().coerceAtLeast(0f).pow(.6f)
            val y=center+sin(i*.56f+phase)*energy*amplitude*envelope
            if(i==0)path.moveTo(left,y) else path.lineTo(left+t*span,y)
        }
        canvas.drawPath(path,paint);schedule()
    }
}
