package local.voice

import android.graphics.*
import kotlin.math.*

/** Original Android robot artwork. No Coucou/Mochi character geometry or assets are used. */
internal class AssistantMascotScene {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sides = intArrayOf(-1, 1)
    private val rect = RectF()
    private val shell = Path().apply {
        moveTo(-49f, -58f); lineTo(49f, -58f); lineTo(72f, -35f); lineTo(72f, 33f)
        lineTo(47f, 58f); lineTo(-47f, 58f); lineTo(-72f, 33f); lineTo(-72f, -35f); close()
    }
    private val visor = Path().apply {
        moveTo(-43f, -33f); lineTo(43f, -33f); lineTo(57f, -19f); lineTo(57f, 20f)
        lineTo(44f, 33f); lineTo(-44f, 33f); lineTo(-57f, 20f); lineTo(-57f, -19f); close()
    }
    private val ceramic = LinearGradient(-54f, -58f, 55f, 68f,
        intArrayOf(Color.rgb(244, 252, 255), Color.rgb(164, 192, 211), Color.rgb(98, 136, 165)),
        floatArrayOf(0f, .6f, 1f), Shader.TileMode.CLAMP)
    private val glass = LinearGradient(0f, -33f, 0f, 33f,
        Color.rgb(23, 42, 61), Color.rgb(5, 16, 28), Shader.TileMode.CLAMP)
    private var halo: Shader? = null
    private var color = 0

    private fun fill(value: Int, shader: Shader? = null) {
        paint.shader = shader; paint.color = value; paint.style = Paint.Style.FILL
    }
    private fun stroke(value: Int, width: Float = 2f) {
        paint.shader = null; paint.color = value; paint.style = Paint.Style.STROKE
        paint.strokeWidth = width; paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
    }
    private fun alpha(value: Int, opacity: Int) = (value and 0x00ffffff) or (opacity.coerceIn(0, 255) shl 24)
    private fun capsule(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, radius: Float) {
        rect.set(left, top, right, bottom); canvas.drawRoundRect(rect, radius, radius, paint)
    }

    fun draw(canvas: Canvas, mode: AssistantCoreView.Mode, motion: MascotTween, time: Float,
             energy: Float, animated: Boolean) {
        val accent = when (mode) {
            AssistantCoreView.Mode.THINKING -> Color.rgb(186, 167, 255)
            AssistantCoreView.Mode.WORKING, AssistantCoreView.Mode.LOADING -> Color.rgb(255, 208, 137)
            AssistantCoreView.Mode.ERROR -> Color.rgb(255, 161, 151)
            else -> Color.rgb(131, 237, 250)
        }
        if (accent != color) {
            color = accent
            halo = RadialGradient(0f, 3f, 127f, intArrayOf(alpha(accent, 38), alpha(accent, 8), Color.TRANSPARENT),
                floatArrayOf(0f, .65f, 1f), Shader.TileMode.CLAMP)
        }
        fill(Color.WHITE, halo); canvas.drawCircle(0f, 3f, 127f, paint)
        val breathing = if (animated) sin(time * 1.9f) else 0f
        val lift = motion[MascotTween.Channel.LIFT] + breathing * 2f
        fill(Color.argb(52, 0, 0, 0))
        capsule(canvas, -49f + lift * .3f, 85f, 49f - lift * .3f, 93f, 4f)
        stroke(alpha(accent, 35), 1f); canvas.drawLine(-77f, 89f, -58f, 89f, paint); canvas.drawLine(58f, 89f, 77f, 89f, paint)

        canvas.save()
        canvas.translate(0f, -lift)
        canvas.rotate(motion[MascotTween.Channel.TILT])
        canvas.scale(motion[MascotTween.Channel.SCALE_X], motion[MascotTween.Channel.SCALE_Y])
        // Antenna and two separate sensor pods make a mechanical silhouette, rather than a blob.
        stroke(Color.rgb(89, 126, 154), 4f)
        val antennaX = motion[MascotTween.Channel.ANTENNA]
        canvas.drawLine(0f, -57f, antennaX, -80f, paint)
        val loading = mode == AssistantCoreView.Mode.LOADING
        fill(alpha(accent, if (loading && animated) (175 + sin(time * 6f) * 80).toInt() else 255))
        canvas.drawCircle(antennaX, -83f, 6f, paint)
        stroke(alpha(accent, 70), 1f); canvas.drawCircle(antennaX, -83f, 10f, paint)
        for (side in sides) {
            val extension = if (mode == AssistantCoreView.Mode.LISTENING) 7f else 0f
            val x = side * (85f + extension)
            stroke(Color.rgb(83, 123, 153), 4f); canvas.drawLine(side * 65f, 0f, x, 0f, paint)
            fill(Color.rgb(103, 143, 173)); capsule(canvas, x - 9f, -24f, x + 9f, 24f, 6f)
            fill(Color.rgb(22, 45, 65)); capsule(canvas, x - 5f, -18f, x + 5f, 18f, 3f)
            fill(accent)
            for (i in 0..3) {
                val height = if (mode == AssistantCoreView.Mode.LISTENING) 2f + energy * (3f + i % 2 * 2f) else 2f
                capsule(canvas, x - 2f, -12f + i * 7 - height / 2, x + 2f, -12f + i * 7 + height / 2, 1f)
            }
        }
        fill(Color.WHITE, ceramic); canvas.drawPath(shell, paint)
        stroke(Color.rgb(173, 215, 235), 1.2f); canvas.drawPath(shell, paint)
        // Upper panel seam, bolts and a recessed dark visor are original Android artwork.
        stroke(Color.argb(100, 245, 255, 255), 2f); canvas.drawLine(-38f, -47f, 38f, -47f, paint)
        fill(Color.rgb(69, 106, 135)); canvas.drawCircle(-51f, 43f, 2.5f, paint); canvas.drawCircle(51f, 43f, 2.5f, paint)
        fill(Color.WHITE, glass); canvas.drawPath(visor, paint)
        stroke(Color.rgb(78, 117, 147), 1.8f); canvas.drawPath(visor, paint)
        stroke(Color.argb(34, 240, 255, 255), 1.2f); canvas.drawLine(-38f, -26f, 32f, -26f, paint)

        val thinking = mode == AssistantCoreView.Mode.THINKING
        val lookX = if (thinking) 5f else if (mode == AssistantCoreView.Mode.WORKING && animated) sin(time * 2.8f) * 4f else 0f
        val lookY = if (thinking) -3f else 0f
        val eyeOpen = motion[MascotTween.Channel.EYES].coerceIn(0f, 1.2f)
        for (side in sides) {
            val eyeX = side * 23f + lookX
            val eyeY = -7f + lookY
            stroke(alpha(accent, 36), 1.1f); capsule(canvas, eyeX - 9f, eyeY - 12f, eyeX + 9f, eyeY + 12f, 4f)
            if (mode == AssistantCoreView.Mode.ERROR) {
                stroke(accent, 3f); canvas.drawLine(eyeX - 4f, eyeY + 2f, eyeX + 4f, eyeY - 3f, paint)
            } else {
                fill(accent)
                val height = (15f * eyeOpen).coerceAtLeast(2f)
                capsule(canvas, eyeX - 4f, eyeY - height / 2f, eyeX + 4f, eyeY + height / 2f, 2f)
            }
        }
        // Only live synthesized PCM drives the speaking mouth; idle/thinking never fake speech.
        if (mode == AssistantCoreView.Mode.SPEAKING) {
            fill(accent)
            for (i in -3..3) {
                val height = 2f + energy * (6f + (3 - abs(i)) * 2f)
                capsule(canvas, i * 5f - 1.2f, 18f - height / 2f, i * 5f + 1.2f, 18f + height / 2f, 1.2f)
            }
        } else {
            stroke(alpha(accent, if (mode == AssistantCoreView.Mode.ERROR) 210 else 140), 2f)
            canvas.drawLine(-9f, 19f, 9f, 19f, paint)
        }
        fill(Color.rgb(49, 86, 114)); capsule(canvas, -14f, 44f, 14f, 49f, 2.5f)
        fill(accent); canvas.drawCircle(-7f, 46.5f, 1.5f, paint)
        canvas.restore()

        when (mode) {
            AssistantCoreView.Mode.THINKING, AssistantCoreView.Mode.LOADING, AssistantCoreView.Mode.WORKING -> {
                for (i in -1..1) {
                    val pulse = if (animated) (sin(time * 5f - i * .8f) + 1f) / 2f else .65f
                    fill(alpha(accent, (85 + pulse * 170).toInt()))
                    canvas.drawCircle(i * 13f, 111f, 2.4f + pulse, paint)
                }
            }
            AssistantCoreView.Mode.ERROR -> {
                fill(accent); canvas.drawCircle(77f, -63f, 12f, paint)
                stroke(Color.rgb(27, 32, 42), 2.5f); canvas.drawLine(77f, -69f, 77f, -63f, paint)
                fill(Color.rgb(27, 32, 42)); canvas.drawCircle(77f, -58f, 1.5f, paint)
            }
            else -> Unit
        }
    }
}
