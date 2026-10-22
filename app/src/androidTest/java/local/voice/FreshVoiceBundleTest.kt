package local.voice

import android.media.AudioManager
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.abs

/** Verify a private full-voice build on an emulator without recording or playing audio. */
class FreshVoiceBundleTest {
    @Test fun defaultBayaVoiceUnpacksFromApkAndSynthesizesWithoutPreviousAppData() {
        assumeTrue("Disposable emulator only", Build.HARDWARE in listOf("ranchu", "goldfish"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val available = runCatching { context.assets.open("voice-silero.zip").use { it.read() }; true }.getOrDefault(false)
        assumeTrue("The source-validation build excludes private voice weights", available)
        val directory = java.io.File(context.filesDir, "voice-silero")
        if (directory.exists()) check(directory.deleteRecursively())
        assertEquals("baya", ModelCatalog.voice(context))
        val voice = SileroVoice(context, "baya")
        var frames = 0
        var peak = 0f
        try {
            voice.generate("Привет. Голос готов.", { true }) { pcm ->
                assertTrue("Voice contains nonfinite samples", pcm.all { it.isFinite() })
                frames += pcm.size
                peak = maxOf(peak, pcm.maxOfOrNull { abs(it) } ?: 0f)
                true
            }
            assertTrue("Bundled voice did not synthesize speech", frames > 2400 && peak > 0.001f)
            assertEquals("silero-v5.5-android-v2", java.io.File(directory, ".complete").readText())
            assertTrue(context.getSystemService(AudioManager::class.java).activeRecordingConfigurations.isEmpty())
            java.io.File(context.cacheDir, "fresh-voice-bundle.json").writeText(org.json.JSONObject()
                .put("androidApi", Build.VERSION.SDK_INT).put("source", "APK asset")
                .put("voice", "baya").put("frames", frames).put("peak", peak)
                .put("microphone", false).put("audioPlayback", false).toString(2))
        } finally { voice.close() }
    }
}
