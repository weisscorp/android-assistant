package local.voice

import android.content.ComponentName
import android.content.Intent
import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AudioFocusTest {
    @Test fun externalMusicFocusReturnsAfterStopExplicitPausePersistsAndCompetingFocusClosesMic() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val component = PhoneNotifications.component(context)
        val granted = PhoneNotifications.hasAccess(context)
        val audio = context.getSystemService(AudioManager::class.java)
        val initialMode = audio.mode
        var conversation: VoiceConversation? = null
        fun shell(command: String) { instrumentation.uiAutomation.executeShellCommand(command).use {
            java.io.FileInputStream(it.fileDescriptor).use { input -> input.readBytes() }
        } }
        fun launch(activity: String, mode: String) {
            context.startActivity(Intent().setComponent(ComponentName("local.voice.fixture", "local.voice.fixture.$activity"))
                .putExtra("mode", mode).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        fun waitFor(description: String, predicate: () -> Boolean) {
            val until = System.currentTimeMillis() + 10000
            while (System.currentTimeMillis() < until) { if (runCatching(predicate).getOrDefault(false)) return; Thread.sleep(100) }
            fail(description)
        }
        fun state() = context.getSystemService(MediaSessionManager::class.java).getActiveSessions(component)
            .single { it.packageName == "local.voice.fixture" }.playbackState!!.state
        fun start(): CountDownLatch {
            val listening = CountDownLatch(1)
            val finished = CountDownLatch(1)
            instrumentation.runOnMainSync {
                conversation = VoiceConversation(context, { if (it == "Слушаю…") listening.countDown() }, { finished.countDown() }, requireHeadset = false)
                    .also { it.start() }
            }
            assertTrue("Conversation never listened", listening.await(30, TimeUnit.SECONDS))
            waitFor("External player did not pause on transient focus loss") { state() == PlaybackState.STATE_PAUSED }
            // Phone fallback deliberately uses the speaker/MIC without owning call mode.
            assertEquals(initialMode, audio.mode)
            assertEquals(1, audio.activeRecordingConfigurations.size)
            assertEquals(AudioDeviceInfo.TYPE_BUILTIN_MIC, audio.activeRecordingConfigurations.single().audioDevice?.type)
            return finished
        }
        try {
            shell("cmd notification allow_listener ${component.flattenToString()}")
            launch("MediaActivity", "focus")
            waitFor("Player did not acquire focus") { state() == PlaybackState.STATE_PLAYING }
            val stopped = start()
            instrumentation.runOnMainSync { conversation!!.stop() }
            assertTrue(stopped.await(5, TimeUnit.SECONDS))
            waitFor("Player did not regain focus after assistant stopped") { state() == PlaybackState.STATE_PLAYING }
            waitFor("Assistant left a microphone or audio mode behind") { audio.activeRecordingConfigurations.isEmpty() && audio.mode == initialMode }
            start()
            val answered = CountDownLatch(1)
            instrumentation.runOnMainSync {
                DeviceTools(context).execute(VoiceCommand.Media(PhoneMedia.Action.PAUSE))!!.verify!!.invoke {
                    assertEquals("Музыка на паузе.", it); answered.countDown()
                }
            }
            assertTrue(answered.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { conversation!!.stop() }
            Thread.sleep(700)
            assertEquals("Explicit pause must survive return of focus", PlaybackState.STATE_PAUSED, state())
            launch("MediaActivity", "focus")
            waitFor("Fresh player did not acquire focus") { state() == PlaybackState.STATE_PLAYING }
            val interrupted = start()
            launch("FocusActivity", "acquire")
            assertTrue("Assistant did not end on competing focus", interrupted.await(5, TimeUnit.SECONDS))
            waitFor("Microphone remained active after competing focus") { audio.activeRecordingConfigurations.isEmpty() }
            launch("FocusActivity", "release")
            waitFor("Audio mode was not restored") { audio.mode == initialMode }
        } finally {
            instrumentation.runOnMainSync { conversation?.close() }
            launch("FocusActivity", "release"); Thread.sleep(200)
            launch("MediaActivity", "clear"); Thread.sleep(200)
            shell("cmd notification ${if (granted) "allow_listener" else "disallow_listener"} ${component.flattenToString()}")
        }
    }
}
