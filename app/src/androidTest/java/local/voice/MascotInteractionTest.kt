package local.voice

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.media.AudioManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.SeekBar
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Real touch dispatch, persistence and reduced-motion checks; selected disposable emulator only. */
@RunWith(AndroidJUnit4::class)
@org.junit.Ignore("Legacy mascot UI replaced by AudioSignalUiTest")
class MascotInteractionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val images get() = File(context.getExternalFilesDir(null), "mascot-previews").apply { mkdirs() }
    private fun waitFor(message: String, predicate: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < end) {
            if (predicate()) return
            Thread.sleep(30)
        }
        fail(message)
    }
    private fun main() = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    private fun touch(view: View, action: Int, x: Float, y: Float): Boolean {
        var handled = false
        instrumentation.runOnMainSync {
            val now = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(now, now, action, x, y, 0)
            try { handled = view.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        return handled
    }
    private fun render(view: View, name: String): Pair<Int, Rect> {
        var result = 0 to Rect()
        instrumentation.runOnMainSync {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val pixels = IntArray(view.width * view.height)
            bitmap.getPixels(pixels, 0, view.width, 0, 0, view.width, view.height)
            val bounds = Rect(view.width, view.height, 0, 0)
            for (y in 0 until view.height) for (x in 0 until view.width) {
                val color = pixels[y * view.width + x]
                // Bright, opaque gel. Excludes the transparent halo, white eyes and dark ink.
                val high = maxOf(Color.red(color), Color.green(color), Color.blue(color))
                val low = minOf(Color.red(color), Color.green(color), Color.blue(color))
                if (Color.alpha(color) > 220 && high > 140 && high - low > 65) {
                    bounds.left = minOf(bounds.left, x); bounds.top = minOf(bounds.top, y)
                    bounds.right = maxOf(bounds.right, x); bounds.bottom = maxOf(bounds.bottom, y)
                }
            }
            File(images, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            result = pixels.contentHashCode() to bounds
            bitmap.recycle()
        }
        return result
    }
    private fun noAudio() {
        assertNull("Playing with mascot started a conversation", VoiceConversation.activePhase)
        assertTrue(context.getSystemService(AudioManager::class.java).activeRecordingConfigurations.isEmpty())
    }
    @Test fun pressingSquashesDraggingTracksAndReleaseSpringsWithoutStartingVoice() {
        val activity = main()
        val core = activity.findViewById<AssistantCoreView>(R.id.assistant_core)
        try {
            Thread.sleep(350)
            val rest = render(core, "touch-rest").second
            assertFalse("Empty margin stole the scroll gesture", touch(core, MotionEvent.ACTION_DOWN, 0f, 0f))
            val x = core.width / 2f; val y = core.height / 2f
            assertTrue(touch(core, MotionEvent.ACTION_DOWN, x, y))
            Thread.sleep(100)
            val pressed = render(core, "touch-pressed").second
            assertTrue("Touch did not squash the gel", pressed.height() < rest.height() * .95f)
            val unit = minOf(core.width / 300f, core.height / 260f)
            assertTrue(touch(core, MotionEvent.ACTION_MOVE, x + 70f * unit, y - 30f * unit))
            val dragged = render(core, "touch-dragged").second
            assertTrue("Gel did not follow the finger", dragged.exactCenterX() > rest.exactCenterX() + 10 * unit)
            assertTrue(touch(core, MotionEvent.ACTION_UP, x + 70f * unit, y - 30f * unit))
            Thread.sleep(550)
            val returned = render(core, "touch-returned").second
            assertEquals(rest.exactCenterX(), returned.exactCenterX(), 8 * unit)
            assertEquals(AssistantCoreView.Mode.IDLE, core.mode)
            repeat(3) { index ->
                touch(core, MotionEvent.ACTION_DOWN, x, y)
                Thread.sleep(70)
                touch(core, MotionEvent.ACTION_UP, x, y)
                Thread.sleep(140)
                render(core, "tap-$index")
                Thread.sleep(650)
            }
            instrumentation.runOnMainSync { core.mode = AssistantCoreView.Mode.THINKING }
            touch(core, MotionEvent.ACTION_DOWN, x, y)
            touch(core, MotionEvent.ACTION_UP, x, y)
            Thread.sleep(150)
            assertEquals("Touch overwrote the conversation state", AssistantCoreView.Mode.THINKING, core.mode)
            noAudio()
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
    @Test fun reducedMotionTapGivesStaticFeedbackWithoutFrameLoopAndCancelReleasesGesture() {
        val previous = Settings.Global.getString(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE) ?: "1"
        val activity = main()
        val core = activity.findViewById<AssistantCoreView>(R.id.assistant_core)
        val draws = AtomicInteger()
        val listener = ViewTreeObserver.OnDrawListener { draws.incrementAndGet() }
        try {
            instrumentation.uiAutomation.executeShellCommand("settings put global animator_duration_scale 0").use {
                ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
            }
            Thread.sleep(350)
            instrumentation.runOnMainSync { core.viewTreeObserver.addOnDrawListener(listener) }
            val rest = render(core, "reduced-rest").first
            val x = core.width / 2f; val y = core.height / 2f
            touch(core, MotionEvent.ACTION_DOWN, x, y)
            touch(core, MotionEvent.ACTION_CANCEL, x, y)
            assertFalse(core.isPressed)
            assertFalse(touch(core, MotionEvent.ACTION_UP, x, y))
            instrumentation.runOnMainSync { assertTrue(core.performClick()) }
            assertNotEquals("Reduced-motion click had no visible acknowledgement", rest, render(core, "reduced-tap").first)
            Thread.sleep(100)
            val start = draws.get()
            Thread.sleep(250)
            assertTrue("Tap restarted frames with motion disabled", draws.get() - start <= 1)
            Thread.sleep(400)
            assertEquals("Static reaction failed to settle", rest, render(core, "reduced-returned").first)
            noAudio()
        } finally {
            instrumentation.uiAutomation.executeShellCommand("settings put global animator_duration_scale $previous").close()
            instrumentation.runOnMainSync { core.viewTreeObserver.removeOnDrawListener(listener); activity.finish() }
        }
    }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun currentAppearance(): SettingsActivity? {
        var result: SettingsActivity? = null
        instrumentation.runOnMainSync {
            result = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<SettingsActivity>().singleOrNull { it.intent.getStringExtra(SettingsActivity.EXTRA_SECTION) == SettingsSection.APPEARANCE.name }
        }
        return result
    }
    @Test fun presetAndCustomSliderPreviewPersistAcrossRecreationAndHomeScreen() {
        val preferences = MascotColors.preferences(context)
        val before = preferences.all.toMap()
        preferences.edit().clear().commit()
        val home = main()
        var settings: SettingsActivity? = null
        try {
            settings = instrumentation.startActivitySync(Intent(context, SettingsActivity::class.java)
                .putExtra(SettingsActivity.EXTRA_SECTION, SettingsSection.APPEARANCE.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SettingsActivity
            instrumentation.runOnMainSync {
                descendants(settings!!.window.decorView).single { it.tag == "mascot_palette_candy" }.performClick()
            }
            waitFor("Preset was not saved") { MascotColors.read(context).id == "candy" }
            instrumentation.runOnMainSync {
                assertEquals("candy", settings!!.findViewById<AssistantCoreView>(R.id.mascot_preview).colors.id)
                assertEquals("candy", home.findViewById<AssistantCoreView>(R.id.assistant_core).colors.id)
            }
            render(settings!!.window.decorView, "settings-page")
            instrumentation.runOnMainSync {
                val option = descendants(settings!!.window.decorView).single { it.tag == "mascot_palette_candy" }
                option.requestRectangleOnScreen(Rect(0, 0, option.width, option.height), true)
            }
            instrumentation.waitForIdleSync()
            render(settings!!.window.decorView, "settings-palettes")
            instrumentation.runOnMainSync {
                val slider = settings!!.findViewById<SeekBar>(R.id.mascot_primary_hue)
                slider.requestRectangleOnScreen(Rect(0, 0, slider.width, slider.height), true)
            }
            instrumentation.waitForIdleSync()
            val slider = settings!!.findViewById<SeekBar>(R.id.mascot_primary_hue)
            touch(slider, MotionEvent.ACTION_DOWN, slider.width * .3f, slider.height / 2f)
            touch(slider, MotionEvent.ACTION_UP, slider.width * .3f, slider.height / 2f)
            waitFor("Real slider touch did not select custom colors") { MascotColors.read(context).id == MascotColors.CUSTOM }
            val custom = MascotColors.read(context)
            val hue = preferences.getInt("primary_hue", -1)
            assertTrue("Slider hue did not change", hue in 1..150)
            render(settings!!.findViewById(R.id.mascot_preview), "custom-preview")
            val old = settings!!
            instrumentation.runOnMainSync { old.recreate() }
            waitFor("Appearance screen did not recreate") { currentAppearance()?.let { it !== old } == true }
            settings = currentAppearance()!!
            instrumentation.runOnMainSync {
                assertEquals(custom, settings!!.findViewById<AssistantCoreView>(R.id.mascot_preview).colors)
                assertEquals(hue, settings!!.findViewById<SeekBar>(R.id.mascot_primary_hue).progress)
                assertEquals(custom, home.findViewById<AssistantCoreView>(R.id.assistant_core).colors)
            }
            noAudio()
        } finally {
            instrumentation.runOnMainSync { settings?.finish(); home.finish() }
            val edit = preferences.edit().clear()
            before.forEach { (key, value) -> when (value) { is String -> edit.putString(key, value); is Int -> edit.putInt(key, value) } }
            edit.commit()
        }
    }
    @Test fun appearanceControlsRemainReachableWithLargeFontAndLandscape() {
        val previous = Settings.System.getString(context.contentResolver, Settings.System.FONT_SCALE) ?: "1"
        var settings: SettingsActivity? = null
        try {
            instrumentation.uiAutomation.executeShellCommand("settings put system font_scale 2.0").use {
                ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
            }
            settings = instrumentation.startActivitySync(Intent(context, SettingsActivity::class.java)
                .putExtra(SettingsActivity.EXTRA_SECTION, SettingsSection.APPEARANCE.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SettingsActivity
            waitFor("Large font was not applied") { currentAppearance()?.resources?.configuration?.fontScale?.let { it >= 1.9f } == true }
            settings = currentAppearance()!!
            render(settings!!.window.decorView, "settings-large-font")
            instrumentation.runOnMainSync { settings!!.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            waitFor("Landscape layout was not applied") {
                currentAppearance()?.resources?.configuration?.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            }
            settings = currentAppearance()!!
            Thread.sleep(250)
            render(settings!!.window.decorView, "settings-landscape")
            for (id in listOf(R.id.mascot_primary_hue, R.id.mascot_secondary_hue, R.id.mascot_accent_hue, R.id.settings_back)) {
                instrumentation.runOnMainSync {
                    val view = settings!!.findViewById<View>(id)
                    view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height), true)
                }
                instrumentation.waitForIdleSync()
                render(settings!!.window.decorView, "reachable-$id")
                instrumentation.runOnMainSync {
                    val view = settings!!.findViewById<View>(id)
                    val rect = Rect()
                    assertTrue("Control cannot be scrolled into view", view.getGlobalVisibleRect(rect))
                    assertTrue("${context.resources.getResourceEntryName(id)} lacks a 48dp visible touch target: visible=$rect measured=${view.width}x${view.height} density=${context.resources.displayMetrics.density}",
                        rect.height() >= (48 * context.resources.displayMetrics.density).toInt())
                    assertTrue(rect.width() > 0)
                }
            }
            noAudio()
        } finally {
            val current = currentAppearance() ?: settings
            instrumentation.runOnMainSync { current?.finish() }
            instrumentation.uiAutomation.executeShellCommand("settings put system font_scale $previous").close()
        }
    }
}
