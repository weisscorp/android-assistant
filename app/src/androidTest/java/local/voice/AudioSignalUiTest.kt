package local.voice

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.media.AudioManager
import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class AudioSignalUiTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private fun bitmap(view:View):Bitmap {
        val image=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
        image.eraseColor(Color.BLACK);view.draw(Canvas(image));return image
    }
    private fun nonBlack(image:Bitmap):Int {
        val pixels=IntArray(image.width*image.height);image.getPixels(pixels,0,image.width,0,0,image.width,image.height)
        assertTrue("Colored pixel in monochrome signal",pixels.all {Color.red(it)==Color.green(it) && Color.green(it)==Color.blue(it)})
        return pixels.count {Color.red(it)>32}
    }
    @Test fun realFrequencyLineIsFlatInSilenceReactiveToAudioAndStopsWhenHidden() {
        assumeTrue(Build.HARDWARE in listOf("ranchu","goldfish"))
        val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        lateinit var signal:AudioSignalView
        val draws=AtomicInteger();val listener=ViewTreeObserver.OnDrawListener {draws.incrementAndGet()}
        try {
            instrumentation.runOnMainSync {
                signal=activity.findViewById(R.id.assistant_core)
                assertFalse(signal.isClickable)
                val mic=activity.findViewById<View>(R.id.assistant_mic)
                val notes=activity.findViewById<View>(R.id.assistant_audio_notes)
                assertSame("Audio notes belongs beside the voice button",mic.parent,notes.parent)
                assertTrue("Audio notes must be to the right",notes.left>=mic.right)
                val action=bitmap(mic)
                assertEquals("Square white button corner",Color.WHITE,action.getPixel(1,1))
                assertEquals("Black button fill",Color.BLACK,action.getPixel(action.width/2,action.height-12))
                action.recycle()
                assertTrue("Signal is hidden: attached=${signal.isAttachedToWindow}, shown=${signal.isShown}, height=${signal.height}",signal.isShown)
                assertTrue("System animations disabled",android.animation.ValueAnimator.areAnimatorsEnabled())
                signal.mode=AudioSignalView.Mode.LISTENING
                signal.spectrum={FloatArray(AudioSpectrum.BANDS)}
                signal.viewTreeObserver.addOnDrawListener(listener)
            }
            Thread.sleep(160)
            var quiet=0;var active=0
            instrumentation.runOnMainSync {val b=bitmap(signal);quiet=nonBlack(b);b.recycle();signal.spectrum={FloatArray(AudioSpectrum.BANDS){.9f}}}
            Thread.sleep(240)
            instrumentation.runOnMainSync {val b=bitmap(signal);active=nonBlack(b);b.recycle();signal.mode=AudioSignalView.Mode.SPEAKING}
            assertTrue("No signal response: $quiet vs $active",active>quiet*1.3)
            instrumentation.runOnMainSync {signal.visibility=View.INVISIBLE}
            Thread.sleep(100);val before=draws.get();Thread.sleep(160);assertEquals(before,draws.get())
            instrumentation.runOnMainSync {signal.visibility=View.VISIBLE;signal.mode=AudioSignalView.Mode.IDLE}
            assertTrue(context.getSystemService(AudioManager::class.java).activeRecordingConfigurations.isEmpty())
            instrumentation.runOnMainSync {
                val screenshot=instrumentation.uiAutomation.takeScreenshot()
                File(context.cacheDir,"audio-signal-home.png").outputStream().use {screenshot.compress(Bitmap.CompressFormat.PNG,100,it)};screenshot.recycle()
                val panel=AssistantStatusView(context)
                panel.measure(View.MeasureSpec.makeMeasureSpec(700,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1000,View.MeasureSpec.EXACTLY));panel.layout(0,0,700,1000)
                val image=bitmap(panel);assertEquals(Color.BLACK,image.getPixel(0,0));image.recycle()
            }
        } finally {instrumentation.runOnMainSync {signal.viewTreeObserver.removeOnDrawListener(listener);activity.finish()}}
    }
    @Test fun reducedMotionKeepsSignalStaticWithoutStartingAudio() {
        assumeTrue(Build.HARDWARE in listOf("ranchu","goldfish"))
        val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val old=android.provider.Settings.Global.getString(context.contentResolver,android.provider.Settings.Global.ANIMATOR_DURATION_SCALE)
        try {
            instrumentation.uiAutomation.executeShellCommand("settings put global animator_duration_scale 0").close()
            Thread.sleep(150)
            instrumentation.runOnMainSync {
                val signal=activity.findViewById<AudioSignalView>(R.id.assistant_core)
                signal.spectrum={FloatArray(AudioSpectrum.BANDS){1f}};signal.mode=AudioSignalView.Mode.SPEAKING
            }
            Thread.sleep(100)
            var first=0;var second=0
            instrumentation.runOnMainSync {val b=bitmap(activity.findViewById(R.id.assistant_core));first=nonBlack(b);b.recycle()}
            Thread.sleep(160)
            instrumentation.runOnMainSync {val b=bitmap(activity.findViewById(R.id.assistant_core));second=nonBlack(b);b.recycle()}
            assertEquals(first,second)
            assertTrue(context.getSystemService(AudioManager::class.java).activeRecordingConfigurations.isEmpty())
        } finally {
            instrumentation.uiAutomation.executeShellCommand(if(old==null)"settings delete global animator_duration_scale" else "settings put global animator_duration_scale $old").close()
            instrumentation.runOnMainSync {activity.finish()}
        }
    }
    @Test fun blackPalettePersistsInSettingsAndNotes() {
        assumeTrue(Build.HARDWARE in listOf("ranchu","goldfish"))
        val theme=AssistantTheme(context)
        assertEquals(Color.BLACK,theme.background);assertEquals(Color.BLACK,theme.surface);assertEquals(Color.WHITE,theme.accent)
        for(intent in listOf(Intent(context,SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_SECTION,"APPEARANCE"),Intent(context,AudioNotesActivity::class.java))) {
            val activity=instrumentation.startActivitySync(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));instrumentation.waitForIdleSync()
            try {instrumentation.runOnMainSync {
                val root=activity.window.decorView
                val image=bitmap(root);nonBlack(image);image.recycle()
                assertTrue(context.getSystemService(AudioManager::class.java).activeRecordingConfigurations.isEmpty())
            }} finally {instrumentation.runOnMainSync {activity.finish()}}
        }
    }
}
