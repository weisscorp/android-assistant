package local.voice

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
import android.graphics.Rect
import android.media.AudioManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Window dispatch and rendered pupil positions, using synthetic data on a disposable emulator. */
@RunWith(AndroidJUnit4::class)
@org.junit.Ignore("Legacy mascot UI replaced by AudioSignalUiTest")
class MascotScreenTouchTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun bounds(view: View): Rect {
        val rect = Rect()
        instrumentation.runOnMainSync { assertTrue(view.getGlobalVisibleRect(rect)) }
        return rect
    }
    private fun dispatch(activity: Activity, action: Int, vararg points: PointF) {
        instrumentation.runOnMainSync {
            val properties = points.indices.map { i -> MotionEvent.PointerProperties().apply {
                id = i; toolType = MotionEvent.TOOL_TYPE_FINGER
            } }.toTypedArray()
            val coordinates = points.map { p -> MotionEvent.PointerCoords().apply {
                x = p.x; y = p.y; pressure = 1f; size = 1f
            } }.toTypedArray()
            val now = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(now, now, action, points.size, properties, coordinates,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { activity.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        instrumentation.waitForIdleSync()
    }
    private fun pupil(core: AssistantCoreView): PointF {
        var result = PointF()
        instrumentation.runOnMainSync {
            val bitmap = Bitmap.createBitmap(core.width, core.height, Bitmap.Config.ARGB_8888)
            core.draw(Canvas(bitmap))
            val unit = minOf(core.width / 300f, core.height / 260f)
            val x0 = core.width / 2f
            val y0 = core.height / 2f - 16f * unit // Idle center minus lift.
            var count = 0; var sumX = 0f; var sumY = 0f
            for (y in (y0 - 36 * unit).toInt() until (y0 + 12 * unit).toInt()) {
                for (x in (x0 - 49 * unit).toInt() until (x0 - 4 * unit).toInt()) {
                    val dx = (x - x0) / unit + 26f
                    val dy = (y - y0) / unit + 10f
                    // Exclude the fixed dark outline: measure the moving pupil inside the eye.
                    if (dx * dx / (21f * 21f) + dy * dy / (18f * 18f) >= 1f) continue
                    val color = bitmap.getPixel(x, y)
                    if (Color.alpha(color) > 200 && Color.red(color) < 45 && Color.green(color) < 55 && Color.blue(color) < 55) {
                        sumX += x; sumY += y; count++
                    }
                }
            }
            assertTrue("No pupil found in the rendered eye", count > 5)
            result = PointF((sumX / count - x0) / unit, (sumY / count - y0) / unit)
            bitmap.recycle()
        }
        return result
    }
    private fun fixture(body: (MainActivity) -> Unit) {
        val previous = Settings.Global.getString(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE) ?: "1"
        fun scale(value: String) = instrumentation.uiAutomation.executeShellCommand("settings put global animator_duration_scale $value").use {
            ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
        }
        scale("0")
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            instrumentation.waitForIdleSync()
            body(activity)
            assertNull("Observing the screen started voice", VoiceConversation.activePhase)
            assertTrue(context.getSystemService(AudioManager::class.java).activeRecordingConfigurations.isEmpty())
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            scale(previous)
        }
    }
    private fun screenPoints(activity: Activity, core: AssistantCoreView): Pair<PointF, PointF> {
        val screen = bounds(activity.findViewById(android.R.id.content))
        val upper = PointF(screen.left + 8f, screen.top + 30f)
        val lower = PointF(screen.right - 8f, screen.bottom - 60f)
        val character = bounds(core)
        assertFalse(character.contains(upper.x.toInt(), upper.y.toInt()))
        assertFalse(character.contains(lower.x.toInt(), lower.y.toInt()))
        return upper to lower
    }
    @Test fun homeEyesFollowAcrossWindowAndHandOffToRemainingFingerWithoutCharacterClicks() = fixture { activity ->
        val core = activity.findViewById<AssistantCoreView>(R.id.assistant_core)
        val resting = pupil(core)
        val (upper, lower) = screenPoints(activity, core)
        dispatch(activity, MotionEvent.ACTION_DOWN, upper)
        val first = pupil(core)
        dispatch(activity, MotionEvent.ACTION_MOVE, lower)
        val second = pupil(core)
        assertTrue("Outside touch did not turn eyes right: $first -> $second", second.x - first.x > 12f)
        assertTrue("Outside touch did not turn eyes down: $first -> $second", second.y - first.y > 8f)
        assertFalse("Outside touch pressed the character", core.isPressed)
        dispatch(activity, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), lower, upper)
        dispatch(activity, MotionEvent.ACTION_POINTER_UP, lower, upper)
        val handedOff = pupil(core)
        assertEquals(first.x, handedOff.x, 1f); assertEquals(first.y, handedOff.y, 1f)
        dispatch(activity, MotionEvent.ACTION_CANCEL, upper)
        val returned = pupil(core)
        assertEquals(resting.x, returned.x, 1f); assertEquals(resting.y, returned.y, 1f)
    }
    @Test fun voicePanelFollowsOutsideCharacterAndStillDeliversNativeButtonClicks() = fixture { activity ->
        lateinit var panel: AssistantStatusView
        instrumentation.runOnMainSync {
            panel = AssistantStatusView(activity)
            activity.setContentView(panel)
        }
        instrumentation.waitForIdleSync()
        val core = descendants(panel).filterIsInstance<AssistantCoreView>().single()
        val (upper, lower) = screenPoints(activity, core)
        dispatch(activity, MotionEvent.ACTION_DOWN, upper)
        val first = pupil(core)
        dispatch(activity, MotionEvent.ACTION_MOVE, lower)
        val second = pupil(core)
        assertTrue("Voice panel pupil did not follow: $first -> $second", second.x - first.x > 12f && second.y - first.y > 8f)
        dispatch(activity, MotionEvent.ACTION_CANCEL, lower)
        val stop = descendants(panel).filterIsInstance<Button>().single { it.text == "Остановить помощника" }
        var clicks = 0
        instrumentation.runOnMainSync { stop.setOnClickListener { clicks++ } }
        val button = bounds(stop)
        val center = PointF(button.exactCenterX(), button.exactCenterY())
        dispatch(activity, MotionEvent.ACTION_DOWN, center)
        dispatch(activity, MotionEvent.ACTION_UP, center)
        assertEquals("Gaze observer consumed the button's click", 1, clicks)
        assertFalse(core.isPressed)
    }
    @Test fun appearancePreviewFollowsOutsideViewAndPaletteClickStillSaves() = fixture { _ ->
        val before = MascotColors.read(context).id
        val settings = instrumentation.startActivitySync(Intent(context, SettingsActivity::class.java)
            .putExtra(SettingsActivity.EXTRA_SECTION, SettingsSection.APPEARANCE.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SettingsActivity
        try {
            instrumentation.waitForIdleSync()
            val core = settings.findViewById<AssistantCoreView>(R.id.mascot_preview)
            val (upper, lower) = screenPoints(settings, core)
            dispatch(settings, MotionEvent.ACTION_DOWN, upper)
            val first = pupil(core)
            dispatch(settings, MotionEvent.ACTION_MOVE, lower)
            val second = pupil(core)
            assertTrue("Settings pupil did not follow: $first -> $second", second.x - first.x > 12f && second.y - first.y > 8f)
            dispatch(settings, MotionEvent.ACTION_CANCEL, lower)
            val card = descendants(settings.window.decorView).single { it.tag == "mascot_palette_candy" }
            instrumentation.runOnMainSync { card.requestRectangleOnScreen(Rect(0, 0, card.width, card.height), true) }
            instrumentation.waitForIdleSync()
            val button = bounds(card)
            val center = PointF(button.exactCenterX(), button.exactCenterY())
            dispatch(settings, MotionEvent.ACTION_DOWN, center)
            dispatch(settings, MotionEvent.ACTION_UP, center)
            assertEquals("Screen observer consumed the palette click", "candy", MascotColors.read(context).id)
        } finally {
            instrumentation.runOnMainSync { settings.finish(); MascotColors.select(context, before) }
        }
    }
}
