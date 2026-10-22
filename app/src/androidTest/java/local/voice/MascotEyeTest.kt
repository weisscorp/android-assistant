package local.voice

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import local.voice.MascotTween.Channel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Fixed-time renderer checks on a selected disposable emulator; no audio, model or phone access. */
@RunWith(AndroidJUnit4::class)
class MascotEyeTest {
    private val scene = AssistantMascotScene()
    private val pose = MascotTween()
    private val reaction = MascotTween()
    private val left = Rect(110, 127, 158, 174)
    private val right = Rect(157, 112, 209, 173)
    private val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "mascot-eyes")
    private fun frame(name: String): Bitmap {
        val bitmap = Bitmap.createBitmap(320, 280, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(16, 31, 44))
        canvas.translate(160f, 160f)
        scene.draw(canvas, AssistantCoreView.Mode.IDLE, pose, reaction, MascotColors.presets.first(),
            0f, 0f, false, false, false)
        directory.mkdirs()
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }
    private fun whites(bitmap: Bitmap, area: Rect): Int {
        var count = 0
        for (y in area.top until area.bottom) for (x in area.left until area.right) {
            val color = bitmap.getPixel(x, y)
            if (Color.red(color) > 185 && Color.green(color) > 185 && Color.blue(color) > 185) count++
        }
        return count
    }
    @Test fun winkAndBlinkUseCurvedLidsWithoutWhiteSliversAndReopenIdentically() {
        val open = frame("open")
        val leftWhites = whites(open, left)
        val rightWhites = whites(open, right)
        assertTrue(leftWhites > 400 && rightWhites > 400)
        reaction.set(Channel.WINK, 1f)
        val wink = frame("wink")
        assertEquals("Wink changed the other eye", leftWhites, whites(wink, left))
        assertEquals("Closed wink retained a white eye sliver", 0, whites(wink, right))
        val lid = Rect()
        val lidArea = Rect(163, 134, 204, 156)
        var dark = 0
        for (y in lidArea.top until lidArea.bottom) for (x in lidArea.left until lidArea.right) {
            val color = wink.getPixel(x, y)
            if (Color.red(color) < 40 && Color.green(color) < 50 && Color.blue(color) < 50) {
                if (dark == 0) lid.set(x, y, x + 1, y + 1) else lid.union(x, y, x + 1, y + 1)
                dark++
            }
        }
        assertTrue("Lid collapsed to a hairline", dark > 70 && lid.height() >= 6 && lid.width() >= 30)
        reaction.reset()
        pose.set(Channel.EYES, .03f)
        val blink = frame("blink")
        assertEquals(0, whites(blink, left)); assertEquals(0, whites(blink, right))
        pose.reset()
        val reopened = frame("reopened")
        assertTrue("Eye did not fully recover", open.sameAs(reopened))
        for (bitmap in listOf(open, wink, blink, reopened)) bitmap.recycle()
    }
}
