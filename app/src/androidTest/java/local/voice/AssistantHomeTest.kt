package local.voice

import android.content.Intent
import android.media.AudioManager
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssistantHomeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun launch() = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    private fun waitFor(description: String, predicate: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < until) {
            var matched = false
            instrumentation.runOnMainSync { matched = predicate() }
            if (matched) return
            Thread.sleep(50)
        }
        fail(description)
    }
    private fun texts(view: View): List<String> = when (view) {
        is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        is TextView -> listOf(view.text.toString())
        else -> emptyList()
    }
    @Test fun composerSendsTypedCommandWithoutStartingMicrophone() {
        val activity = launch()
        try {
            instrumentation.runOnMainSync {
                activity.findViewById<EditText>(R.id.assistant_message).setText("который час")
                activity.findViewById<ImageButton>(R.id.assistant_send).performClick()
            }
            waitFor("Typed command did not reach visible conversation") { texts(activity.window.decorView).any { it.startsWith("Сейчас") } }
            assertTrue(context.getSystemService(AudioManager::class.java).activeRecordingConfigurations.isEmpty())
            instrumentation.runOnMainSync {
                assertEquals(AssistantCoreView.Mode.IDLE, activity.findViewById<AssistantCoreView>(R.id.assistant_core).mode)
                assertTrue(activity.findViewById<ImageButton>(R.id.assistant_send).isEnabled)
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
    @Test fun primaryVoiceActionStartsAndFinishesRecordingAndStopReleasesAudio() {
        val activity = launch()
        try {
            instrumentation.runOnMainSync { activity.findViewById<Button>(R.id.assistant_mic).performClick() }
            waitFor("Microphone action did not enter listening") { activity.findViewById<Button>(R.id.assistant_mic).text == "Закончить запись" }
            assertFalse(context.getSystemService(AudioManager::class.java).activeRecordingConfigurations.isEmpty())
            instrumentation.runOnMainSync {
                assertEquals(AssistantCoreView.Mode.LISTENING, activity.findViewById<AssistantCoreView>(R.id.assistant_core).mode)
                activity.findViewById<Button>(R.id.assistant_mic).performClick()
                assertEquals(AssistantCoreView.Mode.THINKING, activity.findViewById<AssistantCoreView>(R.id.assistant_core).mode)
                activity.findViewById<ImageButton>(R.id.assistant_stop).performClick()
            }
            waitFor("Stopping from home left microphone open") { context.getSystemService(AudioManager::class.java).activeRecordingConfigurations.isEmpty() }
        } finally { instrumentation.runOnMainSync { activity.finish(); VoiceConversation.stopActive() } }
    }
    @Test fun homeConversationContinuesWhenItOpensAnotherApplication() {
        val activity = launch()
        val audio = context.getSystemService(AudioManager::class.java)
        try {
            instrumentation.runOnMainSync { activity.findViewById<Button>(R.id.assistant_mic).performClick() }
            waitFor("Home conversation did not start recording") { VoiceConversation.activePhase == SessionDiagnostics.Phase.LISTENING && audio.activeRecordingConfigurations.isNotEmpty() }
            instrumentation.runOnMainSync {
                val field = MainActivity::class.java.getDeclaredField("conversation").apply { isAccessible = true }
                val owned = field.get(activity) as VoiceConversation
                owned.respond("открой Ассистент: тест полей")
            }
            waitFor("Voice command did not open the requested application") {
                instrumentation.uiAutomation.rootInActiveWindow?.let { root ->
                    try { root.packageName == "local.voice.fixture" } finally { root.recycle() }
                } ?: false
            }
            instrumentation.waitForIdleSync()
            waitFor("Home Activity did not reach the background") {
                ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(activity) == Stage.STOPPED
            }
            waitFor("Opening an application ended the home conversation") {
                VoiceConversation.activePhase == SessionDiagnostics.Phase.LISTENING && audio.activeRecordingConfigurations.isNotEmpty()
            }
            // The owner Activity has actually stopped, rather than merely losing focus.
            instrumentation.runOnMainSync { assertFalse(activity.hasWindowFocus()) }
            instrumentation.runOnMainSync { VoiceConversation.stopActive() }
            waitFor("Stopping the background conversation left its microphone open") { audio.activeRecordingConfigurations.isEmpty() }
        } finally { instrumentation.runOnMainSync { VoiceConversation.stopActive(); activity.finish() } }
    }
}
