package local.voice

import android.graphics.*
import kotlin.math.*

/** Original goo character, drawn natively; no game or Coucou character assets are used. */
internal class AssistantMascotScene {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val rect = RectF()
    private val body = Path()
    private val detail = Path()
    private val sides = intArrayOf(-1, 1)
    private val ivory = LinearGradient(0f, -38f, 0f, 20f,
        Color.rgb(255, 253, 237), Color.rgb(207, 227, 221), Shader.TileMode.CLAMP)
    private var halo: Shader? = null
    private var gel: Shader? = null
    private var accentColor = 0
    private var colors: MascotColors? = null
    private val ink = Color.rgb(9, 25, 28)

    private fun fill(value: Int, shader: Shader? = null) {
        paint.shader = shader; paint.color = value; paint.style = Paint.Style.FILL
    }
    private fun stroke(value: Int, width: Float = 2f) {
        paint.shader = null; paint.color = value; paint.style = Paint.Style.STROKE
        paint.strokeWidth = width; paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
    }
    private fun alpha(value: Int, opacity: Int) = (value and 0x00ffffff) or (opacity.coerceIn(0, 255) shl 24)
    private fun lighten(value: Int, amount: Float): Int = Color.rgb(
        (Color.red(value) + (255 - Color.red(value)) * amount).toInt(),
        (Color.green(value) + (255 - Color.green(value)) * amount).toInt(),
        (Color.blue(value) + (255 - Color.blue(value)) * amount).toInt())
    private fun oval(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        rect.set(left, top, right, bottom); canvas.drawOval(rect, paint)
    }

    private fun eye(canvas: Canvas, x: Float, y: Float, radius: Float, openness: Float,
                    pupilX: Float, pupilY: Float) {
        val opening = ((openness - .03f) / .97f).coerceIn(0f, 1.15f)
        val halfHeight = radius * opening
        // Animate the opening, not the entire canvas: an eyelid keeps its stroke thickness.
        // Fade the last narrow aperture into a curved lid instead of a white sliver/pinched pupil.
        val eyeballOpacity = (opening / .28f).coerceIn(0f, 1f)
        canvas.save()
        canvas.translate(x, y)
        if (eyeballOpacity > 0f) {
            fill(alpha(ink, (100 * eyeballOpacity).toInt()))
            oval(canvas, -radius - 3, -halfHeight + 4 * opening, radius + 4, halfHeight + 7 * opening)
            fill(Color.WHITE, ivory); paint.alpha = (255 * eyeballOpacity).toInt()
            oval(canvas, -radius, -halfHeight, radius, halfHeight)
            stroke(alpha(ink, (255 * eyeballOpacity).toInt()), 2.5f)
            oval(canvas, -radius, -halfHeight, radius, halfHeight)
            canvas.save()
            detail.rewind(); rect.set(-radius + 1, -halfHeight, radius - 1, halfHeight)
            detail.addOval(rect, Path.Direction.CW); canvas.clipPath(detail)
            fill(alpha(ink, (255 * eyeballOpacity).toInt()))
            oval(canvas, pupilX - 9f, pupilY - 11f, pupilX + 9f, pupilY + 11f)
            fill(Color.argb((210 * eyeballOpacity).toInt(), 255, 255, 244))
            canvas.drawCircle(pupilX - 3f, pupilY - 5f, 2.6f, paint)
            canvas.restore()
        }
        if (eyeballOpacity < 1f) {
            detail.rewind(); detail.moveTo(-radius * .7f, -1f)
            detail.quadTo(0f, 8f, radius * .7f, -1f)
            stroke(alpha(ink, (255 * (1f - eyeballOpacity)).toInt()), 3.2f)
            canvas.drawPath(detail, paint)
        }
        canvas.restore()
    }

    fun draw(canvas: Canvas, mode: AssistantCoreView.Mode, motion: MascotTween, reaction: MascotTween,
             appearance: MascotColors, time: Float, energy: Float, animated: Boolean,
             pressed: Boolean, focused: Boolean, fingerLookX: Float? = null, fingerLookY: Float? = null) {
        val accent = when (mode) {
            AssistantCoreView.Mode.THINKING, AssistantCoreView.Mode.WORKING, AssistantCoreView.Mode.LOADING -> appearance.accent
            AssistantCoreView.Mode.ERROR -> Color.rgb(255, 129, 133)
            else -> appearance.primary
        }
        if (accent != accentColor) {
            accentColor = accent
            halo = RadialGradient(0f, 5f, 124f,
                intArrayOf(alpha(accent, 38), alpha(accent, 8), Color.TRANSPARENT),
                floatArrayOf(0f, .65f, 1f), Shader.TileMode.CLAMP)
        }
        if (appearance != colors) {
            colors = appearance
            gel = LinearGradient(-50f, -73f, 48f, 76f,
                intArrayOf(lighten(appearance.primary, .25f), appearance.primary, appearance.secondary),
                floatArrayOf(0f, .38f, 1f), Shader.TileMode.CLAMP)
        }
        fill(Color.WHITE, halo); canvas.drawCircle(0f, 5f, 124f, paint)
        if (focused) { stroke(alpha(appearance.accent, 230), 2f); canvas.drawCircle(0f, 0f, 105f, paint) }
        val breath = if (animated) sin(time * 1.8f) else 0f
        val working = mode == AssistantCoreView.Mode.WORKING
        val loading = mode == AssistantCoreView.Mode.LOADING
        val hop = if (animated && working) max(0f, sin(time * 3.8f)) else 0f
        val lift = motion[MascotTween.Channel.LIFT] + reaction[MascotTween.Channel.LIFT] + breath * 2f + hop * 9f
        fill(Color.argb(65, 0, 0, 0))
        oval(canvas, -54f + lift * .45f, 82f, 54f - lift * .45f, 96f - lift * .1f)

        canvas.save()
        canvas.translate(reaction[MascotTween.Channel.OFFSET_X], -lift + reaction[MascotTween.Channel.OFFSET_Y])
        val sway = if (animated) sin(time * 1.3f) * 1.5f else 0f
        canvas.rotate(motion[MascotTween.Channel.TILT] + reaction[MascotTween.Channel.TILT] + sway)
        // Opposing scale changes preserve the jelly's volume. Only real audio drives energy.
        val pulse = energy * if (mode == AssistantCoreView.Mode.LISTENING) .065f else .035f
        val spring = breath * .012f + hop * .045f
        canvas.scale(motion[MascotTween.Channel.SCALE_X] * reaction[MascotTween.Channel.SCALE_X] * (1f - spring - pulse),
            motion[MascotTween.Channel.SCALE_Y] * reaction[MascotTween.Channel.SCALE_Y] * (1f + spring + pulse))
        val wobble = motion[MascotTween.Channel.WOBBLE] + reaction[MascotTween.Channel.WOBBLE] + if (animated) sin(time * 2.2f) * 2.2f else 0f
        val joy = reaction[MascotTween.Channel.JOY].coerceIn(0f, 1f)

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
        stroke(alpha(appearance.secondary, 220), 2.8f); canvas.drawPath(body, paint)
        stroke(Color.argb(if (pressed) 210 else 90, 255, 255, 255), 1.1f); canvas.drawPath(body, paint)

        // Broad, soft reflections keep the body organic instead of polished metal.
        detail.rewind(); detail.moveTo(-62f, -27f)
        detail.cubicTo(-52f, -45f, -42f, -50f, -35f, -52f)
        stroke(Color.argb(170, 255, 255, 255), 5f); canvas.drawPath(detail, paint)
        fill(Color.argb(115, 255, 255, 255)); oval(canvas, -24f, -62f, -10f, -57f)
        detail.rewind(); detail.moveTo(-42f, 57f)
        detail.cubicTo(-25f, 67f, 4f, 68f, 18f, 63f)
        stroke(alpha(appearance.accent, 185), 3f); canvas.drawPath(detail, paint)

        // Blushing and a widening smile acknowledge touch without imitating synthesized speech.
        fill(alpha(appearance.accent, (45 + joy * 95).toInt()))
        oval(canvas, -51f, 16f, -32f, 27f)
        oval(canvas, 35f, 10f, 57f, 22f)

        val thinking = mode == AssistantCoreView.Mode.THINKING
        val worried = mode == AssistantCoreView.Mode.ERROR
        val lookX = fingerLookX ?: (reaction[MascotTween.Channel.LOOK_X] + when {
            pressed -> 0f
            thinking -> -7f
            worried -> 2f
            working && animated -> sin(time * 2.8f) * 5f
            loading && animated -> sin(time * 1.7f) * 3f
            animated && mode == AssistantCoreView.Mode.IDLE -> sin(time * .7f) * 1.5f
            else -> 0f
        })
        val lookY = fingerLookY ?: (reaction[MascotTween.Channel.LOOK_Y] + if (pressed) 0f else if (thinking || loading) -5f else if (worried) 2f else 1f)
        val eyeOpen = motion[MascotTween.Channel.EYES].coerceIn(.03f, 1.15f)
        for (side in sides) {
            val x = if (side < 0) -26f else 23f
            val y = if (side < 0) -10f else -17f
            val radius = if (side < 0) 23f else 25f
            val wink = if (side > 0) reaction[MascotTween.Channel.WINK].coerceIn(0f, 1f) else 0f
            val pupilX = lookX + side * if (worried) -2f else 1f
            eye(canvas, x, y, radius, eyeOpen * (1f - wink), pupilX, lookY)
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
            val width = 10f + joy * 6f
            val sad = worried && joy < .2f
            detail.rewind(); detail.moveTo(-width, if (sad) 37f else 31f)
            detail.quadTo(0f, if (sad) 29f else if (thinking && joy < .2f) 33f else 39f + joy * 9f,
                width, if (sad) 37f else 31f)
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
