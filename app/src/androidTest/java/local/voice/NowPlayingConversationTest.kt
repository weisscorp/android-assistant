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

/** Explicit emulator-only runner; the external fixture publishes metadata without playing audio. */
@RunWith(AndroidJUnit4::class)
class NowPlayingConversationTest {
    @Test fun modelCallsReaderAndUsesFormattedResultWithoutControllingPlayback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val component = PhoneNotifications.component(context)
        val hadAccess = PhoneNotifications.hasAccess(context)
        val prefs = context.getSharedPreferences("settings", 0)
        val oldMode = prefs.getString("brain_mode", null)
        assertTrue(prefs.edit().putString("brain_mode", "LOCAL").commit())
        val audio = context.getSystemService(AudioManager::class.java)
        fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
            java.io.FileInputStream(it.fileDescriptor).use { input -> input.readBytes() }
        }
        fun launch(mode: String) {
            context.startActivity(Intent().setComponent(ComponentName("local.voice.fixture", "local.voice.fixture.MediaActivity"))
                .putExtra("mode", mode).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Thread.sleep(400)
        }
        fun ask(): String {
            val stats = LocalBrain.lastStats
            val answers = java.util.concurrent.CopyOnWriteArrayList<String>()
            val statuses = java.util.concurrent.CopyOnWriteArrayList<String>()
            val received = java.util.concurrent.CountDownLatch(1)
            var conversation: VoiceConversation? = null
            try {
                instrumentation.runOnMainSync {
                    conversation = VoiceConversation(context, { statuses.add(it) }, {}, textOnly = true,
                        replyObserver = { answers.add(it); received.countDown() }).also { it.start(); it.respond("что щас играет за песня") }
                }
                assertTrue("Model/tool loop did not answer; statuses=$statuses, failure=${LocalBrain.lastFailure}", received.await(90, java.util.concurrent.TimeUnit.SECONDS))
                assertEquals(1, answers.size)
                assertNotSame("The model must select the reader", stats, LocalBrain.lastStats)
                assertTrue(statuses.toString(), statuses.contains("Читаю данные проигрывателя…"))
                assertFalse("Metadata must not cause a second inference", statuses.contains("Готовлю ответ по данным проигрывателя…"))
                assertTrue(audio.activeRecordingConfigurations.isEmpty())
                assertEquals(0L, conversation!!.speechFrames)
                instrumentation.runOnMainSync { conversation?.close() }
                val events = org.json.JSONObject(SessionDiagnostics.last(context)).getJSONArray("events")
                val toolStates = (0 until events.length()).map { events.getJSONObject(it) }
                    .filter { it.optString("tool") == "media" }.map { it.optString("state") }
                assertEquals(toolStates.toString(), "requested_by_model", toolStates.first())
                assertTrue(toolStates.toString(), toolStates[1].startsWith("returned_"))
                assertEquals(toolStates.toString(), "answered_after_result", toolStates.last())
                return answers.single()
            } finally { instrumentation.runOnMainSync { conversation?.close() } }
        }
        try {
            shell("cmd notification disallow_listener ${component.flattenToString()}")
            assertTrue(ask().contains("разрешите доступ"))
            shell("cmd notification allow_listener ${component.flattenToString()}")
            launch("normal")
            assertTrue(ask().contains("Тестовый трек 1"))
            val controller = context.getSystemService(MediaSessionManager::class.java).getActiveSessions(component)
                .single { it.packageName == "local.voice.fixture" }
            assertEquals(PlaybackState.STATE_PLAYING, controller.playbackState!!.state)
            controller.transportControls.pause()
            Thread.sleep(300)
            assertTrue(ask().contains("Тестовый трек 1"))
            assertEquals(PlaybackState.STATE_PAUSED, controller.playbackState!!.state)
            launch("unsupported")
            assertTrue("Reading metadata requires no transport actions", ask().contains("Тестовый трек 1"))
            launch("multiple")
            assertTrue(ask().contains("несколько проигрывателей"))
            launch("clear")
            assertTrue(ask().contains("Активного проигрывателя нет"))
        } finally {
            launch("clear")
            shell("cmd notification ${if (hadAccess) "allow_listener" else "disallow_listener"} ${component.flattenToString()}")
            assertTrue(if (oldMode == null) prefs.edit().remove("brain_mode").commit() else prefs.edit().putString("brain_mode", oldMode).commit())
        }
    }
    @Test fun realNativePlannerSelectsReadOnlyToolForDifferentWordings() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        for (question in listOf("что щас играет за песня", "Как называется эта песня?", "Какая композиция сейчас играет?", "Подскажи название композиции в проигрывателе.")) {
            assertTrue(VoiceCommands.parse(question) is VoiceCommand.Chat)
            val done = java.util.concurrent.CountDownLatch(1)
            var result: LocalBrain.Proposal? = null
            var raw = ""
            instrumentation.runOnMainSync { LocalBrain.plan(context, emptyList(), question, rawObserver = { raw = it }) { result = it; done.countDown() } }
            assertTrue(question, done.await(90, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals("question=$question; raw=$raw; failure=${LocalBrain.lastFailure}", VoiceCommand.Media(PhoneMedia.Action.INFO), result!!.command)
            val json = org.json.JSONObject(raw)
            assertEquals("media", json.getString("tool"))
            assertEquals("info", json.getString("argument"))
        }
    }
}
