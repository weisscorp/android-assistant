package local.voice

import android.app.Activity
import android.app.Instrumentation
import android.app.role.RoleManager
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic picker interception on an emulator, never changes a phone's defaults. */
@RunWith(AndroidJUnit4::class)
class AssistantRoleSelectionTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    @Test fun systemAssistantButtonOpensPublicSelectionSettingsWithoutAudio() {
        val roles = context.getSystemService(RoleManager::class.java)
        assertTrue(roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT))
        assertFalse("Run with no assistant role on a disposable emulator", roles.isRoleHeld(RoleManager.ROLE_ASSISTANT))
        val request = Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS)
        var requestedAction: String? = null
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != request.action) return null
                requestedAction = intent.action
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        val activity = instrumentation.startActivitySync(Intent(context, SettingsActivity::class.java)
            .putExtra(SettingsActivity.EXTRA_SECTION, SettingsSection.ACCESS.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SettingsActivity
        try {
            instrumentation.runOnMainSync {
                views(activity.window.decorView).filterIsInstance<Button>().single { it.text == "Выбрать системным ассистентом" }.performClick()
            }
            instrumentation.waitForIdleSync()
            assertEquals(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS, requestedAction)
            instrumentation.runOnMainSync {
                val labels = views(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }
                assertTrue(labels.any { it.contains("Простого открытия этого экрана недостаточно") })
                assertTrue(labels.any { it.contains("Системный голосовой сервис: не выбран") })
                assertNull(VoiceConversation.activePhase)
                assertTrue(context.getSystemService(AudioManager::class.java).activeRecordingConfigurations.isEmpty())
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.removeMonitor(monitor)
        }
    }
}
