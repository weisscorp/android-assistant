package local.voice

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** The external entry owns a visible console and one microphone, including rotation/cancellation. */
@RunWith(AndroidJUnit4::class)
class AssistantEntryTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val audio get() = context.getSystemService(AudioManager::class.java)
    private fun entry() = Intent(context, VoiceCommandActivity::class.java)
        .setAction(Intent.ACTION_VOICE_COMMAND).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    private fun waitForFixture() {
        val until = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < until) {
            val dump = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("dumpsys activity activities"))
                .bufferedReader().use { it.readText() }
            if (Regex("topResumedActivity=.*local\\.voice\\.fixture/\\.FieldActivity").containsMatchIn(dump)) return
            Thread.sleep(50)
        }
        fail("Releasing the voice console did not restore the previous external app")
    }
    private fun waitFor(message: String, test: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < until) {
            var passed = false
            instrumentation.runOnMainSync { passed = test() }
            if (passed) return
            Thread.sleep(50)
        }
        fail(message)
    }
    @Test fun visibleEntryKeepsItsMicrophoneAcrossRotationAndRepeatedInvocationStopsIt() {
        val activity = instrumentation.startActivitySync(entry()) as VoiceCommandActivity
        try {
            waitFor("External entry did not start listening") { VoiceConversation.activePhase == SessionDiagnostics.Phase.LISTENING }
            assertFalse("Entry hid its console behind a second system window", activity.isFinishing)
            val session = audio.activeRecordingConfigurations.single().clientAudioSessionId
            instrumentation.runOnMainSync { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            waitFor("Console did not rotate") { activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            instrumentation.runOnMainSync {
                val resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                assertTrue("Rotation recreated/stopped the external entry", resumed.any { it === activity })
                assertFalse(activity.isFinishing)
                assertEquals(SessionDiagnostics.Phase.LISTENING, VoiceConversation.activePhase)
            }
            assertEquals("Rotation replaced the microphone", session, audio.activeRecordingConfigurations.single().clientAudioSessionId)
            context.startActivity(entry())
            waitFor("Repeated external entry did not stop the conversation") { VoiceConversation.activePhase == null }
            waitFor("Repeated external entry left a microphone active") { audio.activeRecordingConfigurations.isEmpty() }
            Thread.sleep(700)
            assertTrue("Cancellation restarted capture", audio.activeRecordingConfigurations.isEmpty())
        } finally { instrumentation.runOnMainSync { VoiceConversation.stopActive(); activity.finish() } }
    }
    @Test fun repeatedEntryDuringStartupCannotLeaveDelayedCaptureRunning() {
        val began = System.currentTimeMillis()
        val activity = instrumentation.startActivitySync(entry()) as VoiceCommandActivity
        try {
            context.startActivity(entry())
            waitFor("Rapid repeated entry did not cancel startup") { VoiceConversation.activePhase == null }
            Thread.sleep(1000)
            assertTrue("Foreground/model callback restarted a cancelled entry", audio.activeRecordingConfigurations.isEmpty())
            assertNull(VoiceConversation.activePhase)
            val diagnostic = JSONObject(SessionDiagnostics.last(context))
            assertTrue("Test observed an old cancellation", diagnostic.getLong("startedAtEpochMs") >= began)
            assertEquals("button_stop", diagnostic.getString("reason"))
        } finally { instrumentation.runOnMainSync { VoiceConversation.stopActive(); activity.finish() } }
    }
    @Test fun newConversationIsNotHiddenByThePreviousDelayedFinish() {
        val activity = instrumentation.startActivitySync(entry()) as VoiceCommandActivity
        try {
            waitFor("First entry did not listen") { VoiceConversation.activePhase == SessionDiagnostics.Phase.LISTENING }
            instrumentation.runOnMainSync { assertTrue(VoiceConversation.stopActive()) }
            waitFor("First microphone did not close") { audio.activeRecordingConfigurations.isEmpty() }
            waitFor("First end cue did not finish") { VoiceConversation.activePhase == null }
            context.startActivity(entry())
            waitFor("Second entry did not listen") { VoiceConversation.activePhase == SessionDiagnostics.Phase.LISTENING }
            Thread.sleep(3300)
            instrumentation.runOnMainSync {
                assertFalse("Old finish callback hid the new console", activity.isFinishing)
                assertTrue(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).any { it === activity })
            }
            assertEquals(1, audio.activeRecordingConfigurations.size)
        } finally { instrumentation.runOnMainSync { VoiceConversation.stopActive(); activity.finish() } }
    }
    @Test fun releasingConsoleRestoresExternalAppAndKeepsCapture() {
        val dashboard = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var console: VoiceCommandActivity? = null
        try {
            context.startActivity(Intent().setClassName("local.voice.fixture", "local.voice.fixture.FieldActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitForFixture()
            console = instrumentation.startActivitySync(entry()) as VoiceCommandActivity
            waitFor("External entry did not listen") { VoiceConversation.activePhase == SessionDiagnostics.Phase.LISTENING }
            val session = audio.activeRecordingConfigurations.single().clientAudioSessionId
            instrumentation.runOnMainSync { console.finish() }
            waitForFixture()
            assertEquals("Releasing the console stopped the foreground conversation", SessionDiagnostics.Phase.LISTENING, VoiceConversation.activePhase)
            assertEquals("Releasing the console replaced the microphone", session, audio.activeRecordingConfigurations.single().clientAudioSessionId)
        } finally {
            instrumentation.runOnMainSync { VoiceConversation.stopActive(); console?.finish(); dashboard.finish() }
        }
    }
}
