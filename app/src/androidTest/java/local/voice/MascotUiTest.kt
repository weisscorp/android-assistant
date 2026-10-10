package local.voice

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic visual/lifecycle checks. Run on an explicitly selected disposable emulator only. */
@RunWith(AndroidJUnit4::class)
class MascotUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun launch() = instrumentation.startActivitySync(
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    private fun animationScale(value: String) {
        instrumentation.uiAutomation.executeShellCommand("settings put global animator_duration_scale $value").use { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).readBytes()
        }
    }
    private fun waitForFrames(counter: AtomicInteger, start: Int) {
        val end = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < end && counter.get() < start + 3) Thread.sleep(30)
        assertTrue("Visible mascot did not animate", counter.get() >= start + 3)
    }
    @Test fun framesPauseWhileHiddenAndWithDisabledAnimationsAndResume() {
        val previous = Settings.Global.getString(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE) ?: "1"
        val activity = launch()
        val draws = AtomicInteger()
        val listener = ViewTreeObserver.OnDrawListener { draws.incrementAndGet() }
        lateinit var core: AssistantCoreView
        try {
            animationScale("1")
            instrumentation.runOnMainSync {
                core = activity.findViewById(R.id.assistant_core)
                core.viewTreeObserver.addOnDrawListener(listener)
                core.mode = AssistantCoreView.Mode.THINKING
            }
            waitForFrames(draws, draws.get())
            instrumentation.runOnMainSync { core.visibility = View.INVISIBLE }
            Thread.sleep(250)
            var start = draws.get()
            Thread.sleep(500)
            assertTrue("Hidden mascot kept invalidating its window", draws.get() - start <= 1)
            instrumentation.runOnMainSync { core.visibility = View.VISIBLE }
            waitForFrames(draws, draws.get())
            animationScale("0")
            Thread.sleep(300)
            start = draws.get()
            Thread.sleep(500)
            assertTrue("Disabled animation kept rendering frames", draws.get() - start <= 1)
            animationScale("1")
            waitForFrames(draws, draws.get())
        } finally {
            animationScale(previous)
            instrumentation.runOnMainSync {
                activity.findViewById<AssistantCoreView>(R.id.assistant_core).viewTreeObserver.removeOnDrawListener(listener)
                activity.finish()
            }
        }
    }
    @Test fun rendersAllStatesAndCompactLayoutWithoutOpeningAudio() {
        val activity = launch()
        val directory = File(context.getExternalFilesDir(null), "mascot-previews").apply { mkdirs() }
        lateinit var core: AssistantCoreView
        val fingerprints = mutableSetOf<Int>()
        try {
            instrumentation.runOnMainSync {
                core = activity.findViewById(R.id.assistant_core)
                core.speechLevel = { 4000 }
                core.microphoneLevel(4000, false)
            }
            for (mode in AssistantCoreView.Mode.entries) {
                instrumentation.runOnMainSync { core.mode = mode }
                Thread.sleep(400)
                instrumentation.runOnMainSync {
                    val bitmap = Bitmap.createBitmap(core.width, core.height, Bitmap.Config.ARGB_8888)
                    core.draw(Canvas(bitmap))
                    val pixels = IntArray(core.width * core.height)
                    bitmap.getPixels(pixels, 0, core.width, 0, 0, core.width, core.height)
                    assertTrue("Empty mascot: $mode", pixels.count { it ushr 24 > 128 } > pixels.size / 20)
                    fingerprints += pixels.contentHashCode()
                    File(directory, "${mode.name.lowercase()}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            }
            assertTrue("States were visually indistinguishable", fingerprints.size >= 5)
            val compactHeight = (72 * context.resources.displayMetrics.density).toInt()
            instrumentation.runOnMainSync {
                // MainActivity owns its renderer's height through a global layout listener.
                // A separate instance prevents that listener from resizing the compact fixture.
                core = AssistantCoreView(activity)
                val host = FrameLayout(activity)
                host.addView(core, FrameLayout.LayoutParams(-1, compactHeight, android.view.Gravity.CENTER))
                activity.setContentView(host)
            }
            Thread.sleep(250)
            instrumentation.runOnMainSync {
                assertEquals("Compact fixture did not actually resize the mascot", compactHeight, core.height)
                val bitmap = Bitmap.createBitmap(core.width, core.height, Bitmap.Config.ARGB_8888)
                core.draw(Canvas(bitmap))
                File(directory, "compact.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            assertTrue(context.getSystemService(android.media.AudioManager::class.java).activeRecordingConfigurations.isEmpty())
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
