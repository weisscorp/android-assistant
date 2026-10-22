package local.voice

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import java.time.Instant
import org.vosk.Model
import org.vosk.Recognizer

/** Owned silent emulator only. Generated PCM is decoded without playing it. */
class ClockRingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val audio get() = context.getSystemService(AudioManager::class.java)
    private val notifications get() = context.getSystemService(NotificationManager::class.java)
    private val ids = mutableListOf<Long>()
    private var oldVolume = 0
    @Before fun prepare() {
        assertTrue(Build.HARDWARE in listOf("ranchu", "goldfish"))
        assertTrue(PhoneClocks.exact(context)); assertTrue(PhoneClocks.alerts(context))
        oldVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 3, 0)
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
    }
    @After fun cleanup() {
        ids.forEach { PhoneClocks.cancel(context, it) }
        until { !AlarmRingService.ringing && audio.activeRecordingConfigurations.isEmpty() }
        audio.setStreamVolume(AudioManager.STREAM_ALARM, oldVolume, 0)
        instrumentation.runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<ClockRingActivity>().forEach { it.finish() }
        }
    }
    private fun until(condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 15000
        while (!condition() && SystemClock.elapsedRealtime() < end) Thread.sleep(50)
        assertTrue("Ring state timeout: ${AlarmRingService.voiceStatus}; ids=${AlarmRingService.ringingIds}", condition())
    }
    private fun timer(seconds: Long, title: String = "Synthetic timer"): ClockEntry =
        PhoneClocks.create(context, ClockKind.TIMER, title, Instant.now().plusSeconds(seconds), seconds)
            .also { ids += it.id }
    private fun entry(id: Long) = ClockStore(context).use { it.get(id) }!!
    private fun alert() = notifications.activeNotifications.single { it.id == AlarmRingService.NOTIFICATION_ID }.notification
    @Test fun realTimerRingsAndNativeSpokenStopReleasesAudioWithoutCancellingPendingTimer() {
        val prefs = context.getSharedPreferences("settings", 0)
        val previousEngine = SpeechEngines.selected(context)
        try {
            SpeechEngines.select(context, SpeechEngine.GIGAAM)
            val pending = timer(300, "Unrelated pending timer")
            val fired = timer(2)
            until { fired.id in AlarmRingService.ringingIds && AlarmRingService.soundPlaying && AlarmRingService.listening }
            assertEquals(1, audio.activeRecordingConfigurations.size)
            val notification = alert()
            assertEquals(Notification.CATEGORY_ALARM, notification.category)
            assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
            assertTrue(notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
            if (PhoneClocks.fullScreen(context)) assertNotNull(notification.fullScreenIntent)
            AlarmRingService.recognizedStop("не стоп")
            Thread.sleep(100)
            assertTrue(AlarmRingService.ringing)
            // Actual native ASR for generated "стоп", with the production stop grammar.
            val chunks = mutableListOf<FloatArray>()
            val voice = SileroVoice(context, "baya")
            try { voice.generate("Стоп.", { true }) { samples -> chunks += samples.copyOf(); true } }
            finally { voice.close() }
            val wave = chunks.flatMap { it.toList() }
            val pcm = ShortArray(wave.size / 3 + 32000) { i ->
                if (i * 3 < wave.size) (wave[i*3] * 32767).toInt().coerceIn(-32768, 32767).toShort() else 0
            }
            val directory = AssetModels.unpack(context, "speech-ru.zip", "speech-ru", "vosk-small-ru-0.22")
            val words = mutableListOf<String>()
            Model(directory.absolutePath).use { model ->
                VoskDecoder(Recognizer(model, 16000f, OfflineSpeech.INTERRUPT_GRAMMAR)).use { decoder ->
                    for (start in pcm.indices step 160) {
                        if (decoder.accept(pcm.copyOfRange(start, minOf(start + 160, pcm.size)))) words += decoder.result()
                    }
                    words += decoder.finish()
                }
            }
            val recognized = words.filter { it.isNotBlank() }.joinToString(" ")
            assertEquals("стоп", recognized)
            AlarmRingService.recognizedStop(recognized)
            until { !AlarmRingService.ringing && audio.activeRecordingConfigurations.isEmpty() }
            until { notifications.activeNotifications.none { it.id == AlarmRingService.NOTIFICATION_ID } }
            assertEquals("cancelled", entry(fired.id).state)
            assertEquals("pending", entry(pending.id).state)
        } finally { SpeechEngines.select(context, previousEngine) }
    }
    @Test fun ringScreenStopsTheSignalWithoutOpeningConversation() {
        val fired = timer(2)
        until { fired.id in AlarmRingService.ringingIds }
        alert().contentIntent.send()
        var screen: ClockRingActivity? = null
        until {
            instrumentation.runOnMainSync {
                screen = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<ClockRingActivity>().singleOrNull()
            }
            screen != null
        }
        fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup)
            (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
        instrumentation.runOnMainSync {
            assertNull(VoiceConversation.activePhase)
            assertTrue(views(screen!!.window.decorView).filterIsInstance<Button>().single { it.text == "Остановить" }.performClick())
        }
        until { !AlarmRingService.ringing && audio.activeRecordingConfigurations.isEmpty() }
        assertEquals("cancelled", entry(fired.id).state)
    }
    @Test fun stoppingOneOfTwoFiredTimersKeepsTheOtherRingingAndOnlyOneMicrophone() {
        val first = timer(2, "First timer")
        val second = timer(3, "Second timer")
        until { AlarmRingService.ringingIds.containsAll(listOf(first.id, second.id)) && AlarmRingService.listening }
        PhoneClocks.cancel(context, first.id)
        until { AlarmRingService.ringingIds == setOf(second.id) }
        assertTrue(AlarmRingService.soundPlaying)
        assertEquals(1, audio.activeRecordingConfigurations.size)
        // NotificationManager publishes updates asynchronously; target the second
        // timer's displayed action, not the cached action for the dismissed first one.
        until { alert().extras.getString("android.text") == "Second timer" }
        alert().actions.single { it.title == "Остановить" }.actionIntent.send()
        until { !AlarmRingService.ringing && audio.activeRecordingConfigurations.isEmpty() }
        assertEquals("cancelled", entry(second.id).state)
    }
    @Test fun selectedSystemAssistantListensWhenTimerFiresOverAnotherApp() {
        assertTrue(android.service.voice.VoiceInteractionService.isActiveService(context,
            android.content.ComponentName(context, AssistantService::class.java)))
        val fired = timer(3)
        instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
        until { fired.id in AlarmRingService.ringingIds && AlarmRingService.listening }
        assertEquals(1, audio.activeRecordingConfigurations.size)
        alert().actions.single { it.title == "Остановить" }.actionIntent.send()
        until { !AlarmRingService.ringing && audio.activeRecordingConfigurations.isEmpty() }
    }
    @Test fun fullScreenTimerWakesTheScreenAndShowsStopAboveTheKeyguard() {
        assertTrue(PhoneClocks.fullScreen(context))
        val fired = timer(3)
        val power = context.getSystemService(android.os.PowerManager::class.java)
        try {
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_SLEEP").close()
            until { !power.isInteractive }
            var showing = false
            until {
                instrumentation.runOnMainSync {
                    showing = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<ClockRingActivity>().any { it.intent.getLongExtra("clock_id", -1) == fired.id }
                }
                power.isInteractive && showing
            }
            assertTrue(AlarmRingService.ringing)
            alert().actions.single { it.title == "Остановить" }.actionIntent.send()
            until { !AlarmRingService.ringing && audio.activeRecordingConfigurations.isEmpty() }
        } finally {
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
        }
    }
    @Test fun reminderStaysAnOrdinaryNotificationWithoutMicrophone() {
        val reminder = PhoneClocks.create(context, ClockKind.REMINDER, "Synthetic reminder", Instant.now().plusSeconds(2))
            .also { ids += it.id }
        until { notifications.activeNotifications.any { it.tag == "clock" && it.id == reminder.id.toInt() } }
        assertFalse(AlarmRingService.ringing)
        assertTrue(audio.activeRecordingConfigurations.isEmpty())
        assertEquals(Notification.CATEGORY_REMINDER,
            notifications.activeNotifications.single { it.tag == "clock" && it.id == reminder.id.toInt() }.notification.category)
    }
    @Test fun ringTakesOverAnActiveConversationWithoutCompetingPcmOwners() {
        var conversation: VoiceConversation? = null
        try {
            instrumentation.runOnMainSync {
                conversation = VoiceConversation(context, {}, {}, requireHeadset = false, phoneMicrophone = true)
                    .also { it.start() }
            }
            until { VoiceConversation.activePhase == SessionDiagnostics.Phase.LISTENING && audio.activeRecordingConfigurations.size == 1 }
            val fired = timer(2)
            until { fired.id in AlarmRingService.ringingIds && AlarmRingService.listening && VoiceConversation.activePhase == null }
            assertEquals(1, audio.activeRecordingConfigurations.size)
            var blocked: VoiceConversation? = null
            var blockedStatus = ""
            instrumentation.runOnMainSync {
                blocked = VoiceConversation(context, { blockedStatus = it }, {}, requireHeadset = false, phoneMicrophone = true)
                    .also { it.start() }
            }
            assertTrue(blockedStatus, blockedStatus.contains("Скажите «стоп»"))
            assertNull(VoiceConversation.activePhase)
            assertEquals("A second invocation opened another microphone", 1, audio.activeRecordingConfigurations.size)
            instrumentation.runOnMainSync { blocked?.close() }
            alert().actions.single { it.title == "Остановить" }.actionIntent.send()
            until { !AlarmRingService.ringing && audio.activeRecordingConfigurations.isEmpty() }
        } finally { instrumentation.runOnMainSync { conversation?.close() } }
    }
}
