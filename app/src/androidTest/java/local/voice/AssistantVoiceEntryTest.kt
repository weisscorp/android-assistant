package local.voice

import android.app.role.RoleManager
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Run only on a disposable emulator with a silent audio backend and no assistant role. */
@RunWith(AndroidJUnit4::class)
class AssistantVoiceEntryTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun await(message: String, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 35000
        while (System.currentTimeMillis() < deadline) {
            var passed = false
            instrumentation.runOnMainSync { passed = check() }
            if (passed) return
            Thread.sleep(50)
        }
        fail(message + ": " + AssistantHealth.message)
    }

    @Test fun explicitVoiceCommandWithoutRoleStartsListeningAndSecondPressStops() {
        assertFalse("Use a disposable emulator without the assistant role",
            context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT))
        val home = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        val entry = instrumentation.addMonitor(VoiceCommandActivity::class.java.name, null, false)
        var activity: VoiceCommandActivity? = null
        try {
            context.startActivity(Intent(context, VoiceCommandActivity::class.java)
                .setAction(Intent.ACTION_VOICE_COMMAND).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            activity = instrumentation.waitForMonitorWithTimeout(entry, 10000) as? VoiceCommandActivity
            assertNotNull("Headset intent did not open voice entry", activity)
            await("Explicit headset entry did not start listening") {
                VoiceConversation.activePhase == SessionDiagnostics.Phase.LISTENING
            }
            assertEquals("Voice entry silently redirected to idle home", 0, home.hits)
            context.startActivity(Intent(context, VoiceCommandActivity::class.java)
                .setAction(Intent.ACTION_VOICE_COMMAND).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            await("Second headset invocation did not stop the session") { VoiceConversation.activePhase == null }
            Thread.sleep(750)
            instrumentation.runOnMainSync { assertNull("A resume restarted capture after stopping", VoiceConversation.activePhase) }
        } finally {
            instrumentation.runOnMainSync { VoiceConversation.stopActive(); activity?.finish() }
            instrumentation.removeMonitor(entry); instrumentation.removeMonitor(home)
        }
    }

    @Test fun launcherHomeDoesNotStartMicrophone() {
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        var activity: MainActivity? = null
        try {
            context.startActivity(Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            activity = instrumentation.waitForMonitorWithTimeout(monitor, 10000) as? MainActivity
            assertNotNull(activity)
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { assertNull(VoiceConversation.activePhase) }
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
            instrumentation.removeMonitor(monitor)
        }
    }
}
