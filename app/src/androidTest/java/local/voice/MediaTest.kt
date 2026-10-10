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
class MediaTest {
    @Test fun externalSessionControlsAreVerifiedAndFailuresAreNotReportedAsSuccess() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = context.getSystemService(MediaSessionManager::class.java)
        val component = PhoneNotifications.component(context)
        val previous = PhoneNotifications.hasAccess(context)
        val audio = context.getSystemService(AudioManager::class.java)
        val volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        fun shell(command: String) { instrumentation.uiAutomation.executeShellCommand(command).use {
            java.io.FileInputStream(it.fileDescriptor).use { input -> input.readBytes() }
        } }
        fun launch(mode: String) {
            context.startActivity(Intent().setComponent(ComponentName("local.voice.fixture", "local.voice.fixture.MediaActivity"))
                .putExtra("mode", mode).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Thread.sleep(400)
        }
        fun invoke(action: PhoneMedia.Action): String {
            val done = CountDownLatch(1); var answer = ""
            instrumentation.runOnMainSync {
                val reply = DeviceTools(context).execute(VoiceCommand.Media(action))!!
                if (reply.verify == null) { answer = reply.text; done.countDown() }
                else reply.verify.invoke { answer = it; done.countDown() }
            }
            assertTrue("Media verification timed out", done.await(5, TimeUnit.SECONDS))
            return answer
        }
        fun current() = manager.getActiveSessions(component).single { it.packageName == "local.voice.fixture" }
        try {
            shell("cmd notification disallow_listener ${component.flattenToString()}")
            assertTrue(invoke(PhoneMedia.Action.PAUSE).contains("разрешите"))
            shell("cmd notification allow_listener ${component.flattenToString()}")
            launch("normal")
            assertEquals("Музыка на паузе.", invoke(PhoneMedia.Action.PAUSE))
            assertEquals(PlaybackState.STATE_PAUSED, current().playbackState!!.state)
            assertEquals("Проигрыватель включил музыку.", invoke(PhoneMedia.Action.PLAY))
            assertEquals(PlaybackState.STATE_PLAYING, current().playbackState!!.state)
            assertEquals("Проигрыватель сменил трек.", invoke(PhoneMedia.Action.NEXT))
            assertEquals("fixture-2", current().metadata!!.getString(android.media.MediaMetadata.METADATA_KEY_MEDIA_ID))
            assertEquals("Проигрыватель сменил трек.", invoke(PhoneMedia.Action.PREVIOUS))
            assertEquals("fixture-1", current().metadata!!.getString(android.media.MediaMetadata.METADATA_KEY_MEDIA_ID))
            val first = CountDownLatch(1)
            val resumed = CountDownLatch(1)
            val statuses = java.util.concurrent.CopyOnWriteArrayList<String>()
            var conversation: VoiceConversation? = null
            var listens = 0
            instrumentation.runOnMainSync {
                conversation = VoiceConversation(context, { status ->
                    statuses.add(status)
                    if (status == "Слушаю…") { listens++; if (listens == 1) first.countDown() else resumed.countDown() }
                }, {}, requireHeadset = false).also { it.start() }
            }
            try {
                assertTrue("Voice did not start", first.await(30, TimeUnit.SECONDS))
                val began = android.os.SystemClock.elapsedRealtime()
                instrumentation.runOnMainSync { conversation!!.respond("Приостанови воспроизведение музыки.") }
                val received = resumed.await(30, TimeUnit.SECONDS)
                java.io.File(context.cacheDir, "media-model-metrics.json").writeText(org.json.JSONObject()
                    .put("elapsedMs", android.os.SystemClock.elapsedRealtime() - began)
                    .put("received", received).put("computing", LocalBrain.computing)
                    .put("threads", LocalBrain.lastStats?.threads)
                    .put("prefillMicros", LocalBrain.lastStats?.prefillMicros)
                    .put("totalMicros", LocalBrain.lastStats?.totalMicros).toString())
                assertTrue("Voice did not resume after media verification: $statuses; computing=${LocalBrain.computing}; stats=${LocalBrain.lastStats}; failure=${LocalBrain.lastFailure}", received)
                assertEquals("Active voice capture must retain its CPU budget", 2L, LocalBrain.lastStats!!.threads)
                assertTrue(statuses.toString(), statuses.contains("Музыка на паузе."))
                assertEquals(PlaybackState.STATE_PAUSED, current().playbackState!!.state)
            } finally { instrumentation.runOnMainSync { conversation?.close() } }
            launch("unsupported")
            val unsupported = invoke(PhoneMedia.Action.NEXT)
            assertTrue(unsupported, unsupported.contains("не поддерживает"))
            launch("ignore")
            assertTrue(invoke(PhoneMedia.Action.PAUSE).contains("не подтвердил"))
            assertEquals(PlaybackState.STATE_PLAYING, current().playbackState!!.state)
            launch("multiple")
            assertTrue(invoke(PhoneMedia.Action.NEXT).contains("несколько"))
            launch("clear")
            assertTrue(invoke(PhoneMedia.Action.PLAY).contains("проигрывателя нет"))
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 2, 0)
            val before = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            assertTrue(invoke(PhoneMedia.Action.QUIETER).contains("Громкость музыки"))
            assertTrue(audio.getStreamVolume(AudioManager.STREAM_MUSIC) < before)
        } finally {
            launch("clear")
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
            shell("cmd notification ${if (previous) "allow_listener" else "disallow_listener"} ${component.flattenToString()}")
        }
    }
}
