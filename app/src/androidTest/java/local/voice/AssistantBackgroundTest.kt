package local.voice

import android.Manifest
import android.app.role.RoleManager
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.media.AudioManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Transparent system windows must not expose the previous app through the console. */
class AssistantBackgroundTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    @Test fun repeatedFramesStayOpaqueAndCoverAnyPreviousScreen() {
        val background = AssistantBackdrop().apply { setBounds(0, 0, 120, 240) }
        val bitmap = Bitmap.createBitmap(120, 240, Bitmap.Config.ARGB_8888)
        try {
            var first: Int? = null
            for (frame in 0..3) {
                bitmap.eraseColor(Color.TRANSPARENT)
                background.draw(Canvas(bitmap))
                val color = bitmap.getPixel(7, 101)
                assertEquals("Background became transparent on frame $frame", 255, Color.alpha(color))
                if (first == null) first = color else assertEquals("Background changed after drawing decorations", first, color)
                bitmap.eraseColor(Color.MAGENTA)
                background.draw(Canvas(bitmap))
                assertEquals("Previous app color leaked through on frame $frame", color, bitmap.getPixel(7, 101))
            }
        } finally { bitmap.recycle() }
    }

    @Test fun systemAssistAndVoiceKeysShowOpaqueConsoleWithoutRecording() {
        assumeTrue("Disposable emulator only", Build.HARDWARE in listOf("ranchu", "goldfish"))
        assertTrue("Configure the assistant role on the emulator first", context.getSystemService(RoleManager::class.java)
            .isRoleHeld(RoleManager.ROLE_ASSISTANT))
        assertEquals("This rendering test must not capture audio", PackageManager.PERMISSION_DENIED,
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        try {
            for (key in listOf("KEYCODE_ASSIST", "KEYCODE_VOICE_ASSIST")) {
                shell("input keyevent KEYCODE_HOME")
                instrumentation.runOnMainSync { AssistantHealth.message = "background-test-pending" }
                shell("input keyevent $key")
                val deadline = SystemClock.uptimeMillis() + 10_000
                while (AssistantHealth.message == "background-test-pending" && SystemClock.uptimeMillis() < deadline) Thread.sleep(20)
                assertEquals("System key $key did not reach the voice console",
                    "Откройте приложение и разрешите микрофон.", AssistantHealth.message)
                instrumentation.waitForIdleSync()
                Thread.sleep(180) // The compositor presents the newly attached window after onShow.
                for (frame in 0..1) {
                    val screenshot = instrumentation.uiAutomation.takeScreenshot()
                    assertNotNull("No screenshot for $key", screenshot)
                    screenshot!!
                    try {
                        // Empty console margins; exclude status/navigation bars and controls.
                        for (x in listOf(4, screenshot.width - 5)) for (fraction in listOf(.25f, .5f, .75f)) {
                            val color = screenshot.getPixel(x, (screenshot.height * fraction).toInt())
                            assertTrue("Underlying screen visible for $key, frame $frame at $x/$fraction: ${Integer.toHexString(color)}",
                                Color.red(color) < 30 && Color.green(color) < 55 && Color.blue(color) < 75)
                        }
                        if (frame == 1) java.io.File(context.cacheDir, "background-$key.png").outputStream().use {
                            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                    } finally { screenshot.recycle() }
                    Thread.sleep(200)
                }
                assertTrue(context.getSystemService(AudioManager::class.java).activeRecordingConfigurations.isEmpty())
                Thread.sleep(3100) // Let the permission-error window finish before the next key.
            }
        } finally {
            instrumentation.runOnMainSync { VoiceConversation.stopActive() }
            shell("input keyevent KEYCODE_HOME")
        }
    }
}
