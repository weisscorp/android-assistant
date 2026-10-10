package local.voice

import android.animation.ValueAnimator
import android.content.Context
import android.database.ContentObserver
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import local.voice.MascotTween.Channel
import local.voice.MascotTween.Ease
import local.voice.MascotTween.Key
import kotlin.math.*

/** Shared native mascot for the home screen and Android's voice-assistant session. */
internal class AssistantCoreView(context: Context) : View(context) {
    enum class Mode { IDLE, LOADING, LISTENING, THINKING, SPEAKING, WORKING, ERROR }
    private val motion = MascotTween()
    private val scene = AssistantMascotScene()
    private var visible = false
    private var framePending = false
    private var lastFrame = 0L
    private var time = 0f
    private var blinkAt = 3.2f
    private var microphone = 0f
    private var energy = 0f
    private var unit = 0f
    private var centerX = 0f
    private var centerY = 0f
    var speechLevel: (() -> Int)? = null
    var mode = Mode.IDLE
        set(value) {
            if (field == value) return
            field = value
            pose()
            invalidate()
        }
    private val frame = Runnable {
        framePending = false
        if (canAnimate()) invalidate()
    }
    private val animationSettings = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            if (!ValueAnimator.areAnimatorsEnabled()) {
                // A blink may have been interrupted halfway; restore the actual state's open eyes.
                motion.settle()
                motion.set(Channel.EYES, eyes())
                energy = 0f
            }
            lastFrame = 0L
            cancelFrame()
            invalidate()
            schedule()
        }
    }
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        // The adjacent live text announces state. This decorative view never requests audio,
        // speech, network, permissions or private content, including on the lock screen.
        pose()
        motion.settle()
    }
    private fun eyes() = when (mode) {
        Mode.LISTENING -> 1.08f
        Mode.THINKING -> .82f
        Mode.IDLE -> .95f
        else -> 1f
    }
    private fun pose() {
        fun target(channel: Channel, value: Float) = motion.animate(channel, Key(value, 240f, Ease.OUT))
        target(Channel.SCALE_X, when (mode) { Mode.LISTENING -> .94f; Mode.ERROR -> 1.06f; else -> 1f })
        target(Channel.SCALE_Y, when (mode) { Mode.LISTENING -> 1.06f; Mode.ERROR -> .94f; else -> 1f })
        target(Channel.LIFT, when (mode) { Mode.LISTENING -> 10f; Mode.THINKING -> 8f; Mode.ERROR -> 0f; else -> 4f })
        target(Channel.TILT, when (mode) { Mode.THINKING -> -5f; Mode.ERROR -> 5f; else -> 0f })
        target(Channel.WOBBLE, when (mode) { Mode.THINKING -> 4f; Mode.ERROR -> -4f; else -> 0f })
        target(Channel.EYES, eyes())
        blinkAt = time + 3.2f
        if (!ValueAnimator.areAnimatorsEnabled()) motion.settle()
    }
    fun microphoneLevel(rms: Int, silenced: Boolean) {
        microphone = if (silenced) 0f else level(rms)
        // The frame loop samples this value at 30 fps. Audio callbacks must not drive redraws.
    }
    private fun level(rms: Int) = (ln(1f + rms.coerceAtLeast(0) / 100f) / 4f).coerceIn(0f, 1f)
    private fun canAnimate() = visible && isAttachedToWindow && isShown && ValueAnimator.areAnimatorsEnabled()
    private fun schedule() {
        if (!framePending && canAnimate()) {
            framePending = true
            postDelayed(frame, if (mode == Mode.IDLE) 66L else 33L)
        }
    }
    private fun cancelFrame() { removeCallbacks(frame); framePending = false }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, animationSettings)
        lastFrame = 0L
        schedule()
    }
    override fun onDetachedFromWindow() {
        cancelFrame()
        context.contentResolver.unregisterContentObserver(animationSettings)
        lastFrame = 0L
        super.onDetachedFromWindow()
    }
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        visible = isVisible
        lastFrame = 0L
        cancelFrame()
        if (isVisible) { invalidate(); schedule() }
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // Keep the jelly silhouette, shadow and progress motes inside the compact layout.
        unit = min(w / 300f, h / 260f)
        centerX = w / 2f; centerY = h / 2f - 12f * unit
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (unit <= 0f) return
        val animated = canAnimate()
        if (animated) {
            val now = SystemClock.uptimeMillis()
            val dt = if (lastFrame == 0L) 0f else (now - lastFrame).coerceAtMost(100L) / 1000f
            lastFrame = now
            time += dt
            motion.advance(dt * 1000f)
            if (time >= blinkAt) {
                motion.animate(Channel.EYES, Key(.03f, 75f, Ease.OUT), Key(eyes(), 125f, Ease.OUT))
                blinkAt = time + 3.7f + if (mode == Mode.THINKING) .8f else 0f
            }
            val target = when (mode) {
                Mode.LISTENING -> microphone
                Mode.SPEAKING -> level(speechLevel?.invoke() ?: 0)
                else -> 0f
            }
            energy += (target - energy) * (1f - exp(-dt * 13f))
        } else if (!ValueAnimator.areAnimatorsEnabled()) {
            motion.settle()
            motion.set(Channel.EYES, eyes())
            energy = 0f
        }
        canvas.save()
        canvas.translate(centerX, centerY)
        canvas.scale(unit, unit)
        scene.draw(canvas, mode, motion, time, energy, animated)
        canvas.restore()
        schedule()
    }
}

/** Static ambient light: no full-screen blur, bitmap, web view or per-frame redraw. */
internal class AssistantBackdrop : android.graphics.drawable.Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var glow: Shader? = null
    override fun onBoundsChange(bounds: Rect) {
        glow = RadialGradient(bounds.width() * .5f, bounds.height() * .25f, maxOf(1, bounds.width()).toFloat(),
            intArrayOf(Color.rgb(12, 34, 52), Color.rgb(6, 13, 22)), null, Shader.TileMode.CLAMP)
    }
    override fun draw(canvas: Canvas) {
        paint.shader = glow; paint.style = Paint.Style.FILL; canvas.drawRect(bounds, paint); paint.shader = null
        paint.color = Color.argb(18, 136, 201, 226)
        val step = (bounds.width() / 12f).coerceAtLeast(24f)
        var x = step / 2
        while (x < bounds.right) {
            var y = step / 2
            while (y < bounds.bottom) { canvas.drawCircle(x, y, .8f, paint); y += step }
            x += step
        }
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.OPAQUE
}
