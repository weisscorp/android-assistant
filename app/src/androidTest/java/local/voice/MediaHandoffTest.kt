package local.voice

import android.content.ComponentName
import android.content.Intent
import android.media.AudioManager
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
class MediaHandoffTest {
    @Test fun voicePlayReleasesAudioBeforeDispatchAndUnconfirmedPlayReturnsToVoice() {
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
        fun launch(mode: String) {
            context.startActivity(Intent().setComponent(ComponentName("local.voice.fixture", "local.voice.fixture.MediaActivity"))
                .putExtra("mode", mode).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Thread.sleep(500)
        }
        fun current() = context.getSystemService(MediaSessionManager::class.java).getActiveSessions(component)
            .single { it.packageName == "local.voice.fixture" }
        fun waitFor(description: String, predicate: () -> Boolean) {
            val until = System.currentTimeMillis() + 10000
            while (System.currentTimeMillis() < until) { if (runCatching(predicate).getOrDefault(false)) return; Thread.sleep(100) }
            fail(description)
        }
        fun start(status: (String) -> Unit, finished: () -> Unit) {
            instrumentation.runOnMainSync { conversation = VoiceConversation(context, status, finished, requireHeadset = false).also { it.start() } }
        }
        try {
            shell("cmd notification allow_listener ${component.flattenToString()}")
            launch("focus")
            waitFor("Focused external player did not start") { current().playbackState!!.state == PlaybackState.STATE_PLAYING }
            val listening = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val statuses = java.util.concurrent.CopyOnWriteArrayList<String>()
            start({ statuses.add(it); if (it == "Слушаю…") listening.countDown() }, { finished.countDown() })
            assertTrue(listening.await(30, TimeUnit.SECONDS))
            assertEquals(PlaybackState.STATE_PAUSED, current().playbackState!!.state)
            instrumentation.runOnMainSync { conversation!!.respond("продолжи музыку") }
            val handedOff = finished.await(20, TimeUnit.SECONDS)
            assertTrue("Voice did not hand off to music: $statuses; player=${current().playbackState}; extras=${current().extras}; mode=${audio.mode}", handedOff)
            assertTrue(statuses.toString(), statuses.contains("Завершаю разговор и передаю музыку проигрывателю."))
            assertEquals("Confirmed result missing", "Проигрыватель включил музыку.", statuses.last())
            assertEquals(PlaybackState.STATE_PLAYING, current().playbackState!!.state)
            assertEquals("Play callback ran before voice route was released", initialMode, current().extras!!.getInt("playAudioMode", -1))
            waitFor("Microphone remained active after music handoff") { audio.activeRecordingConfigurations.isEmpty() }
            assertEquals(initialMode, audio.mode)
            launch("paused-ignore")
            val ready = CountDownLatch(1)
            val resumed = CountDownLatch(1)
            val failures = java.util.concurrent.CopyOnWriteArrayList<String>()
            var listens = 0
            start({ failures.add(it); if (it == "Слушаю…") { listens++; if (listens == 1) ready.countDown() else resumed.countDown() } }, {})
            assertTrue(ready.await(30, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { conversation!!.respond("продолжи музыку") }
            assertTrue("Failed music handoff did not recover voice", resumed.await(30, TimeUnit.SECONDS))
            assertTrue(failures.toString(), failures.any { it.contains("не подтвердил продолжение музыки") })
            assertFalse(failures.contains("Проигрыватель включил музыку."))
            assertEquals(1, audio.activeRecordingConfigurations.size)
            assertEquals(initialMode, audio.mode)
            assertEquals(PlaybackState.STATE_PAUSED, current().playbackState!!.state)
            instrumentation.runOnMainSync { conversation!!.close() }
            launch("paused-ignore")
            val cancelledPlayer = current()
            val cancelReady = CountDownLatch(1)
            val cancelled = CountDownLatch(1)
            start({ if (it == "Слушаю…") cancelReady.countDown() }, { cancelled.countDown() })
            assertTrue(cancelReady.await(30, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { conversation!!.respond("продолжи музыку"); conversation!!.stop() }
            assertTrue(cancelled.await(5, TimeUnit.SECONDS))
            Thread.sleep(5000)
            assertNull("Play was dispatched after cancellation during announcement", cancelledPlayer.extras)
            waitFor("Cancellation left the microphone active") { audio.activeRecordingConfigurations.isEmpty() }
            launch("paused-ignore")
            val revokedPlayer = current()
            val revokeReady = CountDownLatch(1)
            val revokeResumed = CountDownLatch(1)
            val revokeStatuses = java.util.concurrent.CopyOnWriteArrayList<String>()
            var revokeListens = 0
            start({ revokeStatuses.add(it); if (it == "Слушаю…") {
                revokeListens++; if (revokeListens == 1) revokeReady.countDown() else revokeResumed.countDown()
            } }, {})
            assertTrue(revokeReady.await(30, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { conversation!!.respond("продолжи музыку") }
            shell("cmd notification disallow_listener ${component.flattenToString()}")
            assertTrue("Revoked permission did not return to voice", revokeResumed.await(30, TimeUnit.SECONDS))
            assertTrue(revokeStatuses.toString(), revokeStatuses.any { it.contains("разрешение изменились") })
            assertNull("Play was dispatched after permission revocation", revokedPlayer.extras)
        } finally {
            instrumentation.runOnMainSync { conversation?.close() }
            launch("clear")
            shell("cmd notification ${if (granted) "allow_listener" else "disallow_listener"} ${component.flattenToString()}")
        }
    }
}
