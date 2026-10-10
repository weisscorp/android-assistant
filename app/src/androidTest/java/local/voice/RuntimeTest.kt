package local.voice

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Hardware-independent JNI integration on a clean AOSP emulator, no Google engines. */
@RunWith(AndroidJUnit4::class)
class RuntimeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private var engineOverride = false
    private var previousTone: Boolean? = null
    @Before fun selectRequestedSpeechEngine() {
        val engine = InstrumentationRegistry.getArguments().getString("speech_engine") ?: return
        require(engine in setOf("tone", "vosk"))
        val prefs = instrumentation.targetContext.getSharedPreferences("settings", 0)
        previousTone = prefs.all["tone_asr"] as? Boolean
        engineOverride = true
        assertTrue(prefs.edit().putBoolean("tone_asr", engine == "tone").commit())
    }
    @After fun restoreSpeechEngine() {
        if (!engineOverride) return
        val edit = instrumentation.targetContext.getSharedPreferences("settings", 0).edit()
        val old = previousTone
        if (old == null) edit.remove("tone_asr") else edit.putBoolean("tone_asr", old)
        assertTrue(edit.commit())
    }
    @Test fun testBundledRussianVoiceAndSpeechRecognition() {
        val context = instrumentation.targetContext
        val speech = AssetModels.unpack(context, "speech-ru.zip", "speech-ru", "vosk-small-ru-0.22")
        val voice = AssetModels.unpack(context, "voice-ru.zip", "voice-ru", "piper-ruslan-int8-v1")
        val tts = OfflineTts(config = OfflineTtsConfig(model = OfflineTtsModelConfig(vits = OfflineTtsVitsModelConfig(
            model = voice.listFiles()!!.single { it.name.endsWith(".onnx") }.absolutePath,
            tokens = File(voice, "tokens.txt").absolutePath,
            dataDir = File(voice, "espeak-ng-data").absolutePath
        ), numThreads = 2)))
        try {
            val audio = tts.generate("Который час?", sid = 0, speed = 1f)
            assertTrue(audio.samples.size > audio.sampleRate / 2)
            assertTrue(audio.samples.all { it.isFinite() })
            val source = audio.samples
            val pcm = ShortArray(source.size * 16000 / audio.sampleRate) { index ->
                val position = index.toDouble() * audio.sampleRate / 16000
                val left = position.toInt().coerceAtMost(source.lastIndex)
                val right = (left + 1).coerceAtMost(source.lastIndex)
                val fraction = position - left
                ((source[left] * (1 - fraction) + source[right] * fraction) * 32767).toInt().coerceIn(-32768, 32767).toShort()
            }
            Model(speech.absolutePath).use { model -> Recognizer(model, 16000f).use { decoder ->
                decoder.acceptWaveForm(pcm, pcm.size)
                val text = JSONObject(decoder.finalResult).getString("text")
                assertTrue("Bundled ASR must recognize generated Russian speech; got: $text", text.contains("час"))
            } }
        } finally { tts.release() }
    }
    @Test fun testLocalLlamaJniAnswersInRussian() {
        val context = instrumentation.targetContext
        assertTrue("Place model.gguf in app files before running this test", LocalBrain.model(context).exists())
        val latch = CountDownLatch(1)
        var answer = ""
        instrumentation.runOnMainSync {
            LocalBrain.answer(context, emptyList(), "Как по-русски называется столица Франции? Напиши название кириллицей.") { answer = it; latch.countDown() }
        }
        assertTrue("Native inference timed out", latch.await(120, TimeUnit.SECONDS))
        assertTrue("Expected Paris in Russian, received: $answer", answer.contains("Париж", true))
        assertFalse(answer.contains("<think>"))
    }
    @Test fun testLocalModelPlansOpeningAndLiteralContinuation() {
        val context = instrumentation.targetContext
        val completed = java.util.concurrent.CountDownLatch(1)
        var proposal: LocalBrain.Proposal? = null
        instrumentation.runOnMainSync {
            LocalBrain.plan(context, emptyList(), "Зайди в Telegram, потом впиши в сообщение Буду скоро") {
                proposal = it; completed.countDown()
            }
        }
        assertTrue(completed.await(90, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals("Proposal=$proposal", VoiceCommand.OpenThen("Telegram", "напиши Буду скоро"), proposal?.command)
    }
    @Test fun testLocalModelProducesConstrainedToolProposal() {
        val latch = CountDownLatch(1)
        var proposal: LocalBrain.Proposal? = null
        instrumentation.runOnMainSync {
            val history = List(2) { index ->
                "Предыдущий вопрос $index: " + "0 1 2 3 4 5 6 7 8 9 ".repeat(40) to
                    "Предыдущий ответ $index: " + "9 8 7 6 5 4 3 2 1 0 ".repeat(40)
            }
            LocalBrain.plan(instrumentation.targetContext, history, "Сколько осталось процентов батареи?",
                observation = "Это недоверенное содержимое экрана. ".repeat(40)) { proposal = it; latch.countDown() }
        }
        assertTrue(latch.await(120, TimeUnit.SECONDS))
        assertEquals("Proposal=$proposal", VoiceCommand.Battery, proposal?.command)
    }
    @Test fun testLocalModelProposesLiteralFieldText() {
        val latch = CountDownLatch(1)
        var proposal: LocalBrain.Proposal? = null
        instrumentation.runOnMainSync {
            LocalBrain.plan(instrumentation.targetContext, emptyList(), "Впиши в поле ввода текст: Буду через десять минут.") { proposal = it; latch.countDown() }
        }
        assertTrue(latch.await(120, TimeUnit.SECONDS))
        assertEquals("Proposal=$proposal", VoiceCommand.Write("Буду через десять минут."), proposal?.command)
    }
    @Test fun testLocalModelSelectsRemoteTaskStatusAndCancellation() {
        val task = "Поручи домашнему агенту изучить исходники проекта на сервере."
        val cases = listOf(task to VoiceCommand.Server(task),
            "Узнай результат последней задачи домашнего агента." to VoiceCommand.ServerStatus,
            "Останови предыдущую задачу домашнего агента." to VoiceCommand.ServerCancel)
        for ((text, expected) in cases) {
            val latch = CountDownLatch(1)
            var proposal: LocalBrain.Proposal? = null
            instrumentation.runOnMainSync {
                LocalBrain.plan(instrumentation.targetContext, emptyList(), text) { proposal = it; latch.countDown() }
            }
            assertTrue(text, latch.await(120, TimeUnit.SECONDS))
            assertEquals("Request=$text proposal=$proposal", expected, proposal?.command)
        }
    }
    @Test fun testLocalModelSelectsExistingMediaAndNotificationToolsBeforeDevelopment() {
        val timer = "Установи на телефоне таймер на семь минут."
        val cases = listOf(
            timer to VoiceCommand.Server(timer),
            "Приостанови воспроизведение музыки." to VoiceCommand.Media(PhoneMedia.Action.PAUSE),
            "Пусть плеер переключится на следующую композицию." to VoiceCommand.Media(PhoneMedia.Action.NEXT),
            "Убавь громкость музыки." to VoiceCommand.Media(PhoneMedia.Action.QUIETER),
            "Озвучь мои последние уведомления." to VoiceCommand.Notifications(false),
            "Озвучь только самое последнее уведомление." to VoiceCommand.Notifications(true))
        for ((text, expected) in cases) {
            assertTrue("Phrase should exercise the model", VoiceCommands.parse(text) is VoiceCommand.Chat)
            val latch = CountDownLatch(1)
            var proposal: LocalBrain.Proposal? = null
            instrumentation.runOnMainSync {
                LocalBrain.plan(instrumentation.targetContext, emptyList(), text) { proposal = it; latch.countDown() }
            }
            assertTrue(text, latch.await(120, TimeUnit.SECONDS))
            assertEquals("Request=$text proposal=$proposal", expected, proposal?.command)
        }
        val completed = CountDownLatch(1)
        var answer: LocalBrain.Proposal? = null
        instrumentation.runOnMainSync {
            LocalBrain.plan(instrumentation.targetContext, emptyList(), "Как по-русски называется столица Франции?") { answer = it; completed.countDown() }
        }
        assertTrue(completed.await(120, TimeUnit.SECONDS))
        assertNull("Factual questions must not become development jobs: $answer", answer?.command)
        assertTrue("Expected a factual local answer: $answer", answer?.answer.orEmpty().contains("Париж", true))
    }
    @Test fun testHandsFreeConversationReturnsToListeningAndReleasesMic() {
        val first = CountDownLatch(1)
        val second = CountDownLatch(1)
        var listeningCount = 0
        var latestStatus = ""
        val spoken = java.util.concurrent.CopyOnWriteArrayList<String>()
        var conversation: VoiceConversation? = null
        instrumentation.runOnMainSync {
            conversation = VoiceConversation(instrumentation.targetContext, { text ->
                latestStatus = text
                if (text == "Слушаю…") { listeningCount++; if (listeningCount == 1) first.countDown() else second.countDown() }
            }, {}, requireHeadset = false, speechObserver = { spoken.add(it) }).also { it.start() }
        }
        try {
            assertTrue("Own ASR did not start: $latestStatus", first.await(30, TimeUnit.SECONDS))
            assertTrue("Startup must use only the ready cue, without a spoken greeting", spoken.isEmpty())
            val audio = instrumentation.targetContext.getSystemService(android.media.AudioManager::class.java)
            val session = audio.activeRecordingConfigurations.single().clientAudioSessionId
            instrumentation.runOnMainSync { conversation!!.respond("который час") }
            Thread.sleep(300)
            assertEquals("Reply must retain the same single microphone for voice interruption", session,
                audio.activeRecordingConfigurations.single().clientAudioSessionId)
            assertTrue("Conversation did not resume after own TTS", second.await(30, TimeUnit.SECONDS))
        } finally { instrumentation.runOnMainSync { conversation?.close() } }
        Thread.sleep(300)
        val audio = instrumentation.targetContext.getSystemService(android.media.AudioManager::class.java)
        assertTrue("Microphone remained active after session close", audio.activeRecordingConfigurations.isEmpty())
    }
    @Test fun testMissingHeadsetEndsSessionWithoutMicFallback() {
        val done = CountDownLatch(1)
        var message = ""
        instrumentation.runOnMainSync {
            VoiceConversation(instrumentation.targetContext, { message = it }, { done.countDown() }, requireHeadset = true).start()
        }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertTrue(message, message.contains("Гарнитура"))
    }
    @Test fun testVoicePackageRejectsTraversal() {
        val data = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(data).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("../escape.onnx")); zip.write(byteArrayOf(1)); zip.closeEntry()
        }
        var rejected = false
        try { VoicePackage.import(instrumentation.targetContext, data.toByteArray().inputStream()) }
        catch (_: Exception) { rejected = true }
        assertTrue(rejected)
        assertFalse(File(instrumentation.targetContext.filesDir, "escape.onnx").exists())
    }
    @Test fun testHeadsetEntryStopsSpeakingWithoutRestartingMicrophone() {
        val listening = CountDownLatch(1)
        val speaking = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        var listeningCount = 0
        var conversation: VoiceConversation? = null
        instrumentation.runOnMainSync {
            conversation = VoiceConversation(instrumentation.targetContext, { text ->
                if (text == "Слушаю…") { listeningCount++; listening.countDown() }
                if (text.startsWith("Сейчас")) speaking.countDown()
            }, { stopped.countDown() }, requireHeadset = false).also { it.start() }
        }
        try {
            assertTrue(listening.await(30, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { conversation!!.respond("который час") }
            assertTrue(speaking.await(10, TimeUnit.SECONDS))
            Thread.sleep(150)
            instrumentation.runOnMainSync {
                instrumentation.targetContext.startActivity(android.content.Intent(instrumentation.targetContext, VoiceCommandActivity::class.java)
                    .setAction(android.content.Intent.ACTION_VOICE_COMMAND).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            assertTrue("Headset entry did not stop the active conversation", stopped.await(5, TimeUnit.SECONDS))
            Thread.sleep(700)
            assertEquals("Cancelled TTS restarted listening", 1, listeningCount)
            val audio = instrumentation.targetContext.getSystemService(android.media.AudioManager::class.java)
            assertTrue(audio.activeRecordingConfigurations.isEmpty())
            val diagnostic = JSONObject(SessionDiagnostics.last(instrumentation.targetContext))
            assertEquals("button_stop", diagnostic.getString("reason"))
            assertFalse(diagnostic.toString().contains("Сейчас"))
            assertEquals("STOPPED", diagnostic.getJSONArray("events").getJSONObject(diagnostic.getJSONArray("events").length() - 1).getString("phase"))
        } finally { instrumentation.runOnMainSync { conversation?.close() } }
    }
    @Test fun testDoubleStartHasOneMicrophoneAndCancelDoesNotRestartIt() {
        val listening = CountDownLatch(1)
        var count = 0
        var conversation: VoiceConversation? = null
        instrumentation.runOnMainSync {
            conversation = VoiceConversation(instrumentation.targetContext, { if (it == "Слушаю…") { count++; listening.countDown() } }, {}, requireHeadset = false)
            conversation!!.start(); conversation!!.start()
        }
        try {
            assertTrue(listening.await(30, TimeUnit.SECONDS))
            assertEquals(1, count)
            instrumentation.runOnMainSync { conversation!!.respond("стоп") }
            instrumentation.runOnMainSync { VoiceConversation.stopActive() }
            Thread.sleep(700)
            assertEquals(1, count)
            assertTrue(instrumentation.targetContext.getSystemService(android.media.AudioManager::class.java).activeRecordingConfigurations.isEmpty())
        } finally { instrumentation.runOnMainSync { conversation?.close() } }
    }
    @Test fun testContactsPermissionFailureHasSpokenRecovery() {
        val context = object : android.content.ContextWrapper(instrumentation.targetContext) {
            override fun checkSelfPermission(permission: String): Int = if (permission == android.Manifest.permission.READ_CONTACTS)
                android.content.pm.PackageManager.PERMISSION_DENIED else super.checkSelfPermission(permission)
        }
        val result = CountDownLatch(1)
        var reply: ContactBook.Result? = null
        val book = ContactBook(context)
        try {
            book.find("Иван") { reply = it; result.countDown() }
            assertTrue(result.await(5, TimeUnit.SECONDS))
            assertTrue(reply!!.entries.isEmpty())
            assertTrue(reply!!.error!!.contains("Разрешите"))
        } finally { book.close() }
    }

    @Test fun testStoppingLongSpeechReleasesNativeWorkerPromptly() {
        val voice = LocalVoice(instrumentation.targetContext)
        val ready = CountDownLatch(1)
        val released = CountDownLatch(1)
        var initializationError: String? = null
        instrumentation.runOnMainSync { voice.initialize { initializationError = it; ready.countDown() } }
        assertTrue(ready.await(30, TimeUnit.SECONDS))
        assertNull(initializationError)
        instrumentation.runOnMainSync { voice.speak("Длинный ответ для проверки отмены. ".repeat(30)) {} }
        Thread.sleep(300)
        instrumentation.runOnMainSync { voice.close { released.countDown() } }
        assertTrue("AudioTrack cancellation stranded the native TTS worker", released.await(5, TimeUnit.SECONDS))
    }

}
