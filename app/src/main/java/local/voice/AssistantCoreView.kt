package local.voice

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.View
import kotlin.math.*

/** A small hardware-accelerated scene. Only this view redraws, at 30 fps while visible. */
internal class AssistantCoreView(context: Context) : View(context) {
    enum class Mode { IDLE, LOADING, LISTENING, THINKING, SPEAKING, WORKING, ERROR }
    var mode = Mode.IDLE
        set(value) {
            if (field == value) return
            field = value
            rebuildShaders(); invalidate(); schedule()
        }
    var speechLevel: (() -> Int)? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val bounds = RectF()
    private val density = resources.displayMetrics.density
    private var microphone = 0f
    private var energy = 0f
    private var motion = 0.22f
    private var time = 0f
    private var lastFrame = 0L
    private var visible = false
    private var halo: Shader? = null
    private var sphere: Shader? = null
    private var orbit: Shader? = null
    private var cx = 0f
    private var cy = 0f
    private var radius = 0f
    private val angles = FloatArray(97) { (it * PI * 2 / 96).toFloat() }
    private val sine = FloatArray(97) { sin(angles[it]) }
    private val cosine = FloatArray(97) { cos(angles[it]) }
    private val frame = object : Runnable {
        override fun run() {
            if (visible && isAttachedToWindow && isShown && ValueAnimator.areAnimatorsEnabled()) {
                invalidate(); postDelayed(this, 33)
            }
        }
    }
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    fun microphoneLevel(rms: Int, silenced: Boolean) {
        microphone = if (silenced) 0f else (ln(1f + rms.coerceAtLeast(0) / 100f) / 4f).coerceIn(0f, 1f)
        invalidate()
    }
    private fun schedule() {
        removeCallbacks(frame)
        if (visible && isAttachedToWindow && isShown && ValueAnimator.areAnimatorsEnabled()) postDelayed(frame, 33)
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); lastFrame = 0; schedule() }
    override fun onDetachedFromWindow() { visible = false; removeCallbacks(frame); super.onDetachedFromWindow() }
    override fun onVisibilityAggregated(isVisible: Boolean) { super.onVisibilityAggregated(isVisible); visible = isVisible; lastFrame = 0; schedule() }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cx = w / 2f; cy = h / 2f; radius = min(w, h) * 0.32f
        rebuildShaders()
    }
    private fun accent() = when (mode) {
        Mode.THINKING -> Color.rgb(175, 159, 255)
        Mode.LOADING, Mode.WORKING -> Color.rgb(255, 203, 139)
        Mode.ERROR -> Color.rgb(255, 158, 151)
        else -> Color.rgb(126, 238, 251)
    }
    private fun rebuildShaders() {
        if (radius <= 0) return
        val color = accent()
        halo = RadialGradient(cx, cy, radius * 1.8f,
            intArrayOf(alpha(color, 45), alpha(color, 12), Color.TRANSPARENT), floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
        sphere = RadialGradient(cx - radius * .28f, cy - radius * .32f, radius * 1.5f,
            intArrayOf(Color.rgb(166, 246, 255), alpha(color, 150), Color.rgb(11, 38, 63), Color.rgb(5, 15, 28)),
            floatArrayOf(0f, .28f, .65f, 1f), Shader.TileMode.CLAMP)
        orbit = SweepGradient(cx, cy, intArrayOf(Color.TRANSPARENT, alpha(color, 12), alpha(color, 220), Color.TRANSPARENT), floatArrayOf(0f, .55f, .87f, 1f))
    }
    private fun alpha(color: Int, value: Int) = (color and 0x00ffffff) or (value.coerceIn(0, 255) shl 24)
    private fun stroke(color: Int, width: Float) {
        paint.shader = null; paint.color = color; paint.style = Paint.Style.STROKE; paint.strokeWidth = density * width; paint.strokeCap = Paint.Cap.ROUND
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (radius <= 0) return
        val now = SystemClock.uptimeMillis()
        val animated = ValueAnimator.areAnimatorsEnabled()
        val dt = if (lastFrame == 0L) 0f else ((now - lastFrame) / 1000f).coerceAtMost(.1f)
        lastFrame = now
        val speed = when (mode) { Mode.THINKING -> 1.8f; Mode.LOADING, Mode.WORKING -> 1.1f; Mode.SPEAKING -> .65f; Mode.LISTENING -> .4f; else -> .22f }
        motion += (speed - motion) * .1f
        if (animated) time += dt * motion
        val target = when (mode) {
            Mode.LISTENING -> microphone
            Mode.SPEAKING -> (ln(1f + (speechLevel?.invoke() ?: 0) / 100f) / 4f).coerceIn(0f, 1f)
            else -> 0f
        }
        energy += (target - energy) * if (animated) .16f else 1f
        val color = accent()
        val r = radius * (1f + if (animated) .014f * sin(time * 2f) + energy * .055f else 0f)
        paint.style = Paint.Style.FILL; paint.shader = halo; paint.alpha = 255
        canvas.drawCircle(cx, cy, radius * 1.8f, paint)
        paint.shader = null
        // Small navigation marks anchor the motion without decorative text or fake metrics.
        stroke(alpha(color, 38), .75f)
        for (side in intArrayOf(-1, 1)) {
            val x = cx + side * radius * 1.48f
            canvas.drawLine(x, cy - radius * .22f, x, cy + radius * .22f, paint)
            canvas.drawLine(x, cy - radius * .22f, x - side * density * 5, cy - radius * .22f, paint)
            canvas.drawLine(x, cy + radius * .22f, x - side * density * 5, cy + radius * .22f, paint)
        }
        // Amplitude drives the outer perimeter. It is not presented as a frequency spectrum.
        for (i in 0 until 64) {
            val a = i * PI * 2 / 64
            val voice = energy * (.35f + .65f * abs(sin(i * .63f + time * 2f)))
            val start = r * 1.23f
            val end = start + density * (2.5f + voice * 14f)
            stroke(alpha(color, if (mode == Mode.LISTENING || mode == Mode.SPEAKING) 100 + (voice * 140).toInt() else 50), 1f)
            canvas.drawLine(cx + cos(a).toFloat() * start, cy + sin(a).toFloat() * start,
                cx + cos(a).toFloat() * end, cy + sin(a).toFloat() * end, paint)
        }
        canvas.save(); canvas.rotate(time * 28f, cx, cy)
        bounds.set(cx - r * 1.13f, cy - r * 1.13f, cx + r * 1.13f, cy + r * 1.13f)
        stroke(alpha(color, 50), .7f); canvas.drawOval(bounds, paint)
        paint.shader = orbit; paint.strokeWidth = density * 2f
        canvas.drawOval(bounds, paint); paint.shader = null
        canvas.restore()
        paint.style = Paint.Style.FILL; paint.shader = sphere; paint.alpha = 255
        canvas.drawCircle(cx, cy, r * .91f, paint); paint.shader = null
        // Project a moving wire sphere. Rear segments disappear behind its luminous surface.
        val yaw = time * .65f
        val cosYaw = cos(yaw); val sinYaw = sin(yaw)
        val tilt = .35f; val cosTilt = cos(tilt); val sinTilt = sin(tilt)
        fun point(x: Float, y: Float, z: Float, first: Boolean): Boolean {
            val xx = x * cosYaw + z * sinYaw
            val zz = z * cosYaw - x * sinYaw
            val yy = y * cosTilt - zz * sinTilt
            val depth = y * sinTilt + zz * cosTilt
            if (depth < -.08f) return false
            val px = cx + xx * r * .9f; val py = cy + yy * r * .9f
            if (first) path.moveTo(px, py) else path.lineTo(px, py)
            return true
        }
        for (line in 0 until 15) {
            path.rewind(); var connected = false
            val latitude = (line - 3) * .36f
            for (i in angles.indices) {
                val x: Float; val y: Float; val z: Float
                if (line < 7) {
                    val wave = if (animated) energy * .05f * sine[(i + line * 7) % 96] else 0f
                    x = cos(latitude) * cosine[i]; y = sin(latitude) + wave; z = cos(latitude) * sine[i]
                } else {
                    val meridian = (line - 7) * PI.toFloat() / 8
                    x = sine[i] * cos(meridian); y = cosine[i]; z = sine[i] * sin(meridian)
                }
                connected = point(x, y, z, !connected)
            }
            stroke(alpha(color, 20), 3.5f); canvas.drawPath(path, paint)
            stroke(alpha(if (line % 3 == 0) Color.WHITE else color, 130), .65f); canvas.drawPath(path, paint)
        }
        // Three orbital ellipses, each on a different plane.
        for (i in 0 until 3) {
            canvas.save(); canvas.rotate(-32f + i * 59f + time * (if (i % 2 == 0) 9f else -7f), cx, cy)
            bounds.set(cx - r * 1.07f, cy - r * .39f, cx + r * 1.07f, cy + r * .39f)
            stroke(alpha(color, 27), .7f); canvas.drawOval(bounds, paint)
            stroke(alpha(color, 170), 1f); canvas.drawArc(bounds, 190f + i * 47, 58f, false, paint)
            paint.style = Paint.Style.FILL; paint.color = alpha(Color.WHITE, 210)
            val a = (time * 24f + i * 117) * PI / 180
            canvas.drawCircle(cx + cos(a).toFloat() * r * 1.07f, cy + sin(a).toFloat() * r * .39f, density * 1.6f, paint)
            canvas.restore()
        }
        stroke(alpha(color, 80), .8f); canvas.drawCircle(cx, cy, r * .92f, paint)
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
