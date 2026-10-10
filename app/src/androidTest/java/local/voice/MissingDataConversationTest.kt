package local.voice

import android.media.AudioManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MissingDataConversationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun unknownChildLocationAnswersImmediatelyWithoutGuessingOrLoadingLlm() {
        val question = "где сейчас мой ребенок находится"
        assertTrue("This fixture needs no matching knowledge", SavedSources(context).relevant(question).isEmpty())
        val prefs = context.getSharedPreferences("settings", 0)
        val oldMode = prefs.getString("brain_mode", null)
        val oldBackend = prefs.getString("backend", null)
        assertTrue(prefs.edit().putString("brain_mode", "LOCAL").remove("backend").commit())
        val replies = CopyOnWriteArrayList<String>()
        val statuses = CopyOnWriteArrayList<String>()
        val stats = LocalBrain.lastStats
        var conversation: VoiceConversation? = null
        try {
            instrumentation.runOnMainSync {
                conversation = VoiceConversation(context, { statuses.add(it) }, {}, textOnly = true,
                    replyObserver = { replies.add(it) }).also { it.start(); it.respond(question) }
            }
            assertEquals("Unknown personal location must get one immediate reply; statuses=$statuses, replies=$replies", 1, replies.size)
            assertTrue(replies.single(), replies.single().contains("источник", true))
            assertTrue(replies.single(), replies.single().contains("не знаю", true))
            assertSame("Missing data must not invoke LLM", stats, LocalBrain.lastStats)
            assertTrue(context.getSystemService(AudioManager::class.java).activeRecordingConfigurations.isEmpty())
            assertEquals(0L, conversation!!.speechFrames)
        } finally {
            instrumentation.runOnMainSync { conversation?.close() }
            val edit = prefs.edit()
            if (oldMode == null) edit.remove("brain_mode") else edit.putString("brain_mode", oldMode)
            if (oldBackend == null) edit.remove("backend") else edit.putString("backend", oldBackend)
            assertTrue(edit.commit())
        }
    }

    @Test fun multipleChildrenAskWhichSourceWithoutReadingOneAtRandom() {
        val store = SavedSources(context)
        val suffix = UUID.randomUUID().toString().take(8)
        val sources = listOf("Один", "Два").map { SavedSource(name = "$it $suffix", kind = SourceKind.NOTE,
            value = "Скрытое содержимое $it", instructions = "Геолокация ребенка") }
        val replies = CopyOnWriteArrayList<String>()
        var conversation: VoiceConversation? = null
        val stats = LocalBrain.lastStats
        try {
            sources.forEach(store::save)
            instrumentation.runOnMainSync {
                conversation = VoiceConversation(context, {}, {}, textOnly = true, replyObserver = { replies.add(it) })
                    .also { it.start(); it.respond("где сейчас мой ребенок находится") }
            }
            assertEquals(1, replies.size)
            assertTrue(replies.single(), replies.single().startsWith("Какой источник"))
            sources.forEach { assertTrue(replies.single(), replies.single().contains(it.name)) }
            assertFalse(replies.single().contains("Скрытое содержимое"))
            assertSame(stats, LocalBrain.lastStats)
        } finally { instrumentation.runOnMainSync { conversation?.close() }; sources.forEach { store.delete(it.id) } }
    }

    @Test fun locationWordOrderStillReadsUserLinkedAlias() {
        val store = SavedSources(context)
        val source = SavedSource(name = "Проверка " + UUID.randomUUID().toString().take(8), kind = SourceKind.NOTE,
            aliases = listOf("мой ребенок"), value = "Текущее положение неизвестно: трансляция выключена.")
        val replies = CopyOnWriteArrayList<String>()
        var conversation: VoiceConversation? = null
        try {
            store.save(source)
            instrumentation.runOnMainSync {
                conversation = VoiceConversation(context, {}, {}, textOnly = true, replyObserver = { replies.add(it) })
                    .also { it.start(); it.respond("где сейчас мой ребёнок находится?") }
            }
            val until = System.currentTimeMillis() + 5000
            while (replies.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(25)
            assertEquals(1, replies.size)
            assertTrue(replies.single(), replies.single().contains(source.name) && replies.single().contains(source.value))
            assertFalse(replies.single().contains("ещё не подключён"))
        } finally { instrumentation.runOnMainSync { conversation?.close() }; store.delete(source.id) }
    }

    @Test fun serverOrOldMemoryCannotInventMissingPersonalPosition() {
        val settings = context.getSharedPreferences("settings", 0)
        val memory = context.getSharedPreferences("conversation_memory", 0)
        val mode = settings.getString("brain_mode", null)
        val backend = settings.getString("backend", null)
        val oldNote = memory.getString("note", null)
        val replies = CopyOnWriteArrayList<String>()
        var conversation: VoiceConversation? = null
        try {
            assertTrue(settings.edit().putString("brain_mode", "HERMES").putString("backend", "https://unused.example.org/").commit())
            ConversationMemory.save(context, listOf("Семья"), listOf("Ребенок был в школе вчера"), "прежний вопрос")
            instrumentation.runOnMainSync {
                conversation = VoiceConversation(context, {}, {}, textOnly = true, replyObserver = { replies.add(it) })
                    .also { it.start(); it.respond("где сейчас мой ребенок находится") }
            }
            assertEquals(listOf(LocationQuestion.MISSING), replies.toList())
            assertFalse(replies.single().contains("школ"))
        } finally {
            instrumentation.runOnMainSync { conversation?.close() }
            val edit = settings.edit()
            if (mode == null) edit.remove("brain_mode") else edit.putString("brain_mode", mode)
            if (backend == null) edit.remove("backend") else edit.putString("backend", backend)
            assertTrue(edit.commit())
            assertTrue(if (oldNote == null) memory.edit().remove("note").commit() else memory.edit().putString("note", oldNote).commit())
        }
    }

    @Test fun missingDataReplyActuallyPlaysAndReturnsToListening() {
        // Only run by our explicit emulator verifier: real OfflineSpeech and LocalVoice, no mock TTS.
        val first = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val spoken = CopyOnWriteArrayList<String>()
        val replies = CopyOnWriteArrayList<String>()
        val statuses = CopyOnWriteArrayList<String>()
        var listening = 0
        var conversation: VoiceConversation? = null
        var frames = 0L
        instrumentation.runOnMainSync {
            conversation = VoiceConversation(context, { text ->
                statuses.add(text)
                if (text == "Слушаю…") {
                    if (++listening == 1) first.countDown()
                    else returned.countDown()
                }
            }, {}, phoneMicrophone = true, speechObserver = { spoken.add(it) }, replyObserver = { replies.add(it) })
                .also { it.start() }
        }
        try {
            assertTrue("Recorder did not start: $statuses", first.await(30, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { conversation!!.respond("где сейчас мой ребенок находится") }
            val until = System.currentTimeMillis() + 45000
            while (returned.count > 0 && System.currentTimeMillis() < until) {
                frames = maxOf(frames, conversation!!.speechFrames)
                Thread.sleep(25)
            }
            assertEquals("Missing-data reply did not finish playback: $statuses; spoken=$spoken", 0L, returned.count)
            assertEquals(listOf(LocationQuestion.MISSING), replies.toList())
            assertEquals(listOf(LocationQuestion.MISSING), spoken.toList())
            assertTrue("No real synthesized PCM was played", frames > 16000)
        } finally { instrumentation.runOnMainSync { conversation?.close() } }
        val until = System.currentTimeMillis() + 3000
        val audio = context.getSystemService(AudioManager::class.java)
        while (audio.activeRecordingConfigurations.isNotEmpty() && System.currentTimeMillis() < until) Thread.sleep(25)
        assertTrue("Capture was not released", audio.activeRecordingConfigurations.isEmpty())
        java.io.File(context.cacheDir, "missing-data-voice-proof.json").writeText(org.json.JSONObject()
            .put("peakPlaybackFrames", frames).put("spoken", org.json.JSONArray(spoken))
            .put("reply", org.json.JSONArray(replies)).put("listeningCount", listening)
            .put("microphoneReleased", audio.activeRecordingConfigurations.isEmpty()).toString())
    }
}
