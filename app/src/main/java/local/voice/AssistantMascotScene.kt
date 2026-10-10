package local.voice

import android.graphics.*
import kotlin.math.*

/** Original goo character, drawn natively; no game or Coucou character assets are used. */
internal class AssistantMascotScene {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val body = Path()
    private val detail = Path()
    private val sides = intArrayOf(-1, 1)
    private val ivory = LinearGradient(0f, -38f, 0f, 20f,
        Color.rgb(255, 253, 237), Color.rgb(207, 227, 221), Shader.TileMode.CLAMP)
    private var halo: Shader? = null
    private var gel: Shader? = null
    private var accentColor = 0
    private val ink = Color.rgb(9, 25, 28)

    private fun fill(value: Int, shader: Shader? = null) {
        paint.shader = shader; paint.color = value; paint.style = Paint.Style.FILL
    }
    private fun stroke(value: Int, width: Float = 2f) {
        paint.shader = null; paint.color = value; paint.style = Paint.Style.STROKE
        paint.strokeWidth = width; paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
    }
    private fun alpha(value: Int, opacity: Int) = (value and 0x00ffffff) or (opacity.coerceIn(0, 255) shl 24)
    private fun tint(value: Int, amount: Float): Int = Color.rgb(
        (18 + (Color.red(value) - 18) * amount).toInt(),
        (40 + (Color.green(value) - 40) * amount).toInt(),
        (44 + (Color.blue(value) - 44) * amount).toInt())
    private fun oval(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        rect.set(left, top, right, bottom); canvas.drawOval(rect, paint)
    }

    fun draw(canvas: Canvas, mode: AssistantCoreView.Mode, motion: MascotTween, time: Float,
             energy: Float, animated: Boolean) {
        val accent = when (mode) {
            AssistantCoreView.Mode.THINKING -> Color.rgb(188, 174, 247)
            AssistantCoreView.Mode.WORKING, AssistantCoreView.Mode.LOADING -> Color.rgb(241, 206, 144)
            AssistantCoreView.Mode.ERROR -> Color.rgb(246, 162, 157)
            else -> Color.rgb(132, 225, 205)
        }
        if (accent != accentColor) {
            accentColor = accent
            halo = RadialGradient(0f, 5f, 124f,
                intArrayOf(alpha(accent, 27), alpha(accent, 6), Color.TRANSPARENT),
                floatArrayOf(0f, .65f, 1f), Shader.TileMode.CLAMP)
            gel = LinearGradient(-50f, -73f, 48f, 76f,
                intArrayOf(tint(accent, .52f), tint(accent, .19f), Color.rgb(19, 34, 42)),
                floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
        }
        fill(Color.WHITE, halo); canvas.drawCircle(0f, 5f, 124f, paint)
        val breath = if (animated) sin(time * 1.8f) else 0f
        val working = mode == AssistantCoreView.Mode.WORKING
        val loading = mode == AssistantCoreView.Mode.LOADING
        val hop = if (animated && working) max(0f, sin(time * 3.8f)) else 0f
        val lift = motion[MascotTween.Channel.LIFT] + breath * 2f + hop * 9f
        fill(Color.argb(65, 0, 0, 0))
        oval(canvas, -54f + lift * .45f, 82f, 54f - lift * .45f, 96f - lift * .1f)

        canvas.save()
        canvas.translate(0f, -lift)
        val sway = if (animated) sin(time * 1.3f) * 1.5f else 0f
        canvas.rotate(motion[MascotTween.Channel.TILT] + sway)
        // Opposing scale changes preserve the jelly's volume. Only real audio drives energy.
        val pulse = energy * if (mode == AssistantCoreView.Mode.LISTENING) .065f else .035f
        val spring = breath * .012f + hop * .045f
        canvas.scale(motion[MascotTween.Channel.SCALE_X] * (1f - spring - pulse),
            motion[MascotTween.Channel.SCALE_Y] * (1f + spring + pulse))
        val wobble = motion[MascotTween.Channel.WOBBLE] + if (animated) sin(time * 2.2f) * 2.2f else 0f

        // An asymmetric, continuous silhouette with soft lobes and a slightly slumped base.
        // Reuse the same Path every frame rather than allocating bitmap/sprite frames.
        body.rewind()
        body.moveTo(-66f, 38f)
        body.cubicTo(-82f, 12f, -82f + wobble, -15f, -60f + wobble, -40f)
        body.cubicTo(-38f + wobble, -65f, -34f + wobble, -76f, -7f + wobble, -72f)
        body.cubicTo(20f + wobble, -68f, 33f, -58f, 50f, -46f)
        body.cubicTo(67f, -34f, 84f, -6f, 76f, 20f)
        body.cubicTo(68f, 46f, 42f, 74f + wobble, 13f, 70f + wobble)
        body.cubicTo(-16f, 66f + wobble, -50f, 64f, -66f, 38f)
        body.close()
        fill(Color.WHITE, gel); canvas.drawPath(body, paint)
        stroke(ink, 3.5f); canvas.drawPath(body, paint)
        stroke(alpha(accent, 54), 1.1f); canvas.drawPath(body, paint)

        // Broad, soft reflections keep the body organic instead of polished metal.
        detail.rewind(); detail.moveTo(-62f, -27f)
        detail.cubicTo(-52f, -45f, -42f, -50f, -35f, -52f)
        stroke(alpha(accent, 74), 5f); canvas.drawPath(detail, paint)
        detail.rewind(); detail.moveTo(-42f, 57f)
        detail.cubicTo(-25f, 67f, 4f, 68f, 18f, 63f)
        stroke(alpha(accent, 19), 3f); canvas.drawPath(detail, paint)

        val thinking = mode == AssistantCoreView.Mode.THINKING
        val worried = mode == AssistantCoreView.Mode.ERROR
        val lookX = when {
            thinking -> -7f
            worried -> 2f
            working && animated -> sin(time * 2.8f) * 5f
            loading && animated -> sin(time * 1.7f) * 3f
            animated && mode == AssistantCoreView.Mode.IDLE -> sin(time * .7f) * 1.5f
            else -> 0f
        }
        val lookY = if (thinking || loading) -5f else if (worried) 2f else 1f
        val eyeOpen = motion[MascotTween.Channel.EYES].coerceIn(.03f, 1.15f)
        for (side in sides) {
            val x = if (side < 0) -26f else 23f
            val y = if (side < 0) -10f else -17f
            val radius = if (side < 0) 23f else 25f
            fill(alpha(ink, 100)); oval(canvas, x - radius - 3, y - radius + 4, x + radius + 4, y + radius + 7)
            canvas.save()
            canvas.translate(x, y)
            canvas.scale(1f, eyeOpen)
            fill(Color.WHITE, ivory); oval(canvas, -radius, -radius, radius, radius)
            stroke(ink, 2.5f); oval(canvas, -radius, -radius, radius, radius)
            // Clip keeps a moving pupil inside the eyeball even during a blink.
            detail.rewind(); rect.set(-radius + 1, -radius + 1, radius - 1, radius - 1)
            detail.addOval(rect, Path.Direction.CW); canvas.clipPath(detail)
            val pupilX = lookX + side * if (worried) -2f else 1f
            fill(ink); oval(canvas, pupilX - 9f, lookY - 11f, pupilX + 9f, lookY + 11f)
            fill(Color.argb(210, 255, 255, 244)); canvas.drawCircle(pupilX - 3f, lookY - 5f, 2.6f, paint)
            canvas.restore()
            if (worried) {
                detail.rewind(); detail.moveTo(x - 13, y - radius - 3)
                detail.quadTo(x, y - radius - 8, x + 13, y - radius - 2)
                stroke(alpha(accent, 220), 3f); canvas.drawPath(detail, paint)
            }
        }

        // A tiny mouth gives the eyes room; it opens only with synthesized PCM amplitude.
        if (mode == AssistantCoreView.Mode.SPEAKING && energy > .02f) {
            val height = 3f + energy * 13f
            fill(ink); oval(canvas, -9f - energy * 2f, 33f - height / 2, 9f + energy * 2f, 33f + height / 2)
            fill(alpha(accent, 150)); oval(canvas, -4f, 33f + height * .12f, 4f, 33f + height * .34f)
        } else {
            detail.rewind(); detail.moveTo(-10f, if (worried) 37f else 31f)
            detail.quadTo(0f, if (worried) 29f else if (thinking) 33f else 39f,
                10f, if (worried) 37f else 31f)
            stroke(ink, 3f); canvas.drawPath(detail, paint)
        }
        canvas.restore()

        // Small motes communicate processing; adjacent accessible text names the state.
        if (thinking || loading || working) {
            for (i in -1..1) {
                val pulseLight = if (animated) (sin(time * 4.5f - i * .9f) + 1f) / 2f else .65f
                fill(alpha(accent, (85 + pulseLight * 150).toInt()))
                canvas.drawCircle(i * 13f, 111f, 2.3f + pulseLight, paint)
            }
        }
    }
}
