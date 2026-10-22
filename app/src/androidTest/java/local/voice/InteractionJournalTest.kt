package local.voice

import android.content.ComponentName
import android.content.Intent
import android.media.AudioManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class InteractionJournalTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var previousEnabled = true
    private var previousMode: String? = null
    private var conversation: VoiceConversation? = null
    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use {
        java.io.FileInputStream(it.fileDescriptor).bufferedReader().use { input -> input.readText().trim() }
    }
    @Before fun prepare() {
        assertEquals("Journal tests must never touch a physical phone", "1", shell("getprop ro.kernel.qemu"))
        previousEnabled = InteractionJournal.isEnabled(context)
        previousMode = context.getSharedPreferences("settings", 0).getString("brain_mode", null)
        instrumentation.runOnMainSync { VoiceConversation.stopActive() }
        InteractionJournal.setEnabled(context, true)
        context.getSharedPreferences("settings", 0).edit().putString("brain_mode", "LOCAL").commit()
        InteractionJournal.clear(context)
    }
    @After fun cleanup() {
        instrumentation.runOnMainSync { conversation?.close("test_finished") }
        InteractionJournal.current?.let { InteractionJournal.end(it, "test_finished") }
        InteractionJournal.flush()
        InteractionJournal.clear(context)
        InteractionJournal.setEnabled(context, previousEnabled)
        val edit = context.getSharedPreferences("settings", 0).edit()
        if (previousMode == null) edit.remove("brain_mode") else edit.putString("brain_mode", previousMode)
        edit.commit()
    }
    private fun events(): List<JSONObject> {
        InteractionJournal.flush()
        return File(context.filesDir, InteractionJournal.FILE_NAME).readLines().map(::JSONObject)
    }
    private fun ask(text: String): String {
        val latch = CountDownLatch(1)
        var answer = ""
        instrumentation.runOnMainSync {
            conversation = VoiceConversation(context, {}, {}, textOnly = true, replyObserver = { answer = it; latch.countDown() })
                .also { it.start(); it.respond(text) }
        }
        assertTrue("No answer for $text: ${LocalBrain.lastFailure}", latch.await(90, TimeUnit.SECONDS))
        instrumentation.runOnMainSync { conversation?.close("test_finished") }
        assertTrue(context.getSystemService(AudioManager::class.java).activeRecordingConfigurations.isEmpty())
        assertEquals(0L, conversation!!.speechFrames)
        return answer
    }
    @Test fun directCommandsAndMissingKnowledgeAreRecordedWithoutLlm() {
        assertTrue(ask("который час").startsWith("Сейчас"))
        assertTrue(ask("где сейчас мой ребенок находится").isNotBlank())
        val records = events()
        val requests = records.filter { it.getString("type") == "user_request" }
        assertEquals(listOf("который час", "где сейчас мой ребенок находится"), requests.map { it.getJSONObject("data").getString("text") })
        assertEquals(2, requests.map { it.getString("session_id") }.distinct().size)
        assertEquals(2, requests.map { it.getString("turn_id") }.distinct().size)
        val call = records.single { it.getString("type") == "tool_request" && it.getJSONObject("data").optString("name") == "Time" }
        val result = records.single { it.getString("type") == "tool_result" && it.optString("call_id") == call.getString("call_id") }
        assertTrue(result.getJSONObject("data").getString("text").startsWith("Сейчас"))
        assertEquals(requests.first().getString("turn_id"), call.getString("turn_id"))
        assertEquals(2, records.count { it.getString("type") == "assistant_response" })
        assertEquals(2, records.count { it.getString("type") == "session_end" })
        assertFalse(records.any { it.getString("type").startsWith("llm_") })
    }
    @Test fun timeParaphrasesAnswerSynchronouslyWithToolReceiptsAndNoInference() {
        val questions = listOf("сколько время", "сколько время сейчас", "сколько сейчас времени", "какое сейчас время", "подскажи который час",
            "скажи, пожалуйста, сколько сейчас времени?")
        for (mode in listOf("LOCAL", "HERMES")) {
            context.getSharedPreferences("settings", 0).edit().putString("brain_mode", mode).commit()
            for (question in questions) {
                instrumentation.runOnMainSync {
                    var answer: String? = null
                    conversation = VoiceConversation(context, {}, {}, textOnly = true, replyObserver = { answer = it })
                        .also { it.start(); it.respond(question) }
                    assertTrue("$mode / $question waited for inference: $answer", answer?.matches(Regex("Сейчас \\d{2}:\\d{2}\\.")) == true)
                    assertEquals(0L, conversation!!.speechFrames)
                    conversation!!.close("test_finished")
                }
            }
        }
        val records = events()
        val requests = records.filter { it.getString("type") == "tool_request" }
        assertEquals(questions.size * 2, requests.size)
        assertTrue(requests.all { it.getJSONObject("data").getString("name") == "Time" })
        assertFalse(records.any { it.getString("type").startsWith("llm_") })
        assertTrue(context.getSystemService(AudioManager::class.java).activeRecordingConfigurations.isEmpty())
    }
    @Test fun realNativeModelInputRawOutputAndMediaToolResultRemainLinked() {
        val component = PhoneNotifications.component(context)
        val hadAccess = PhoneNotifications.hasAccess(context)
        fun launch(mode: String) {
            context.startActivity(Intent().setComponent(ComponentName("local.voice.fixture", "local.voice.fixture.MediaActivity"))
                .putExtra("mode", mode).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Thread.sleep(400)
        }
        try {
            shell("cmd notification allow_listener ${component.flattenToString()}")
            launch("normal")
            val reply = ask("что щас играет за песня")
            assertTrue(reply, reply.contains("Тестовый трек 1"))
            val records = events()
            val user = records.single { it.getString("type") == "user_request" }
            val input = records.filter { it.getString("type") == "llm_input" }
            assertEquals("Player facts need one tool-selection inference", 1, input.size)
            input.forEach { record ->
                assertEquals(user.getString("session_id"), record.getString("session_id"))
                assertEquals(user.getString("turn_id"), record.getString("turn_id"))
                val data = record.getJSONObject("data")
                val messages = data.getJSONArray("messages")
                assertEquals("system", messages.getJSONObject(0).getString("role"))
                assertTrue(data.getString("prompt").contains(messages.getJSONObject(0).getString("content")))
                assertTrue(data.getString("prompt").contains("что щас играет за песня"))
                assertTrue(data.getInt("prompt_tokens") > 0)
                assertEquals(1, records.count { it.getString("type") == "llm_request" && it.optString("call_id") == record.getString("call_id") })
                assertEquals(1, records.count { it.getString("type") == "llm_raw_output" && it.optString("call_id") == record.getString("call_id") })
                assertEquals(1, records.count { it.getString("type") == "llm_result" && it.optString("call_id") == record.getString("call_id") })
            }
            assertFalse(input.first().getJSONObject("data").getString("prompt").contains("Тестовый трек 1"))
            assertTrue(records.any { it.getString("type") == "tool_answer_formatted" && it.getJSONObject("data").getString("tool") == "media" })
            assertTrue(records.any { it.getString("type") == "tool_result" && it.getJSONObject("data").optJSONObject("observation")?.getJSONObject("data")?.optString("title") == "Тестовый трек 1" })
            assertEquals(reply, records.single { it.getString("type") == "assistant_response" }.getJSONObject("data").getString("text"))
        } finally {
            launch("clear")
            shell("cmd notification ${if (hadAccess) "allow_listener" else "disallow_listener"} ${component.flattenToString()}")
        }
    }
    @Test fun exportIsConsistentOptOutStopsNewEventsAndClearRemovesContent() {
        val session = InteractionJournal.start(context, true, false)
        val scope = session.nextTurn()
        scope.event("user_request", InteractionJournal.data("text" to "Точная фраза\nс новой строкой 🎧"))
        val exported = ByteArrayOutputStream()
        val bytes = InteractionJournal.export(context, exported)
        val file = File(context.filesDir, InteractionJournal.FILE_NAME)
        assertEquals(file.length(), bytes)
        assertArrayEquals(file.readBytes(), exported.toByteArray())
        val before = file.readBytes()
        InteractionJournal.setEnabled(context, false)
        scope.event("assistant_response", InteractionJournal.data("text" to "Не сохранять"))
        InteractionJournal.flush()
        assertArrayEquals(before, file.readBytes())
        InteractionJournal.clear(context)
        assertEquals(0L, file.length())
        assertEquals(0L, InteractionJournal.export(context, ByteArrayOutputStream()))
    }
    @Test fun actualFiftyMegabyteLimitPreservesLatestUtf8Records() {
        val session = InteractionJournal.start(context, true, false)
        val scope = session.nextTurn()
        val payload = "А".repeat(400_000)
        repeat(70) { scope.event("large_fixture", InteractionJournal.data("index" to it, "text" to payload)) }
        InteractionJournal.flush()
        val file = File(context.filesDir, InteractionJournal.FILE_NAME)
        assertTrue(file.length().toString(), file.length() <= 50_000_000)
        var previous = -1
        var count = 0
        file.bufferedReader().useLines { lines -> lines.forEach { line ->
            val data = JSONObject(line).getJSONObject("data")
            val index = data.getInt("index")
            if (previous >= 0) assertEquals(previous + 1, index)
            assertEquals(payload, data.getString("text"))
            previous = index; count++
        } }
        assertEquals(69, previous)
        assertTrue(count in 1..69)
        assertFalse(File(file.path + ".tmp").exists())
    }
}
