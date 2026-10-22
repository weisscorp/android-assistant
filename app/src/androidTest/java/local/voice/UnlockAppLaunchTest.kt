package local.voice

import android.app.KeyguardManager
import android.content.Intent
import android.media.AudioManager
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test

/** Dummy PIN on an explicitly selected owned AVD. No microphone or real credentials. */
class UnlockAppLaunchTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val keyguard get() = context.getSystemService(KeyguardManager::class.java)
    private var pinSet = false
    private var disabled = "false"
    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
        java.io.FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() }
    }
    @Before fun ownEmulatorOnly() {
        assertEquals("true", InstrumentationRegistry.getArguments().getString("owned_lock_test"))
        assertEquals("1", shell("getprop ro.kernel.qemu").trim())
        assertFalse("Do not alter an emulator with an unknown credential", keyguard.isDeviceSecure)
        assertTrue(shell("locksettings verify").contains("verified successfully"))
        disabled = shell("locksettings get-disabled").trim()
        assertTrue(disabled in setOf("true", "false"))
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard")
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync(); AppKnowledge(context).refresh()
    }
    @After fun restore() {
        instrumentation.runOnMainSync {
            for (stage in listOf(Stage.RESUMED, Stage.PAUSED, Stage.STARTED, Stage.STOPPED))
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage)
                    .filterIsInstance<UnlockAppActivity>().forEach { it.finish() }
        }
        if (pinSet) { assertTrue(shell("locksettings clear --old 564738").contains("cleared")); pinSet = false }
        shell("locksettings set-disabled $disabled")
        shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard")
        until { !keyguard.isDeviceLocked }
        assertTrue(context.getSystemService(AudioManager::class.java).activeRecordingConfigurations.isEmpty())
    }
    private fun until(condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 15000
        while (!condition() && SystemClock.elapsedRealtime() < end) Thread.sleep(50)
        assertTrue("Unlock condition timed out", condition())
    }
    private fun lock(secure: Boolean) {
        if (secure) { assertTrue(shell("locksettings set-pin 564738").contains("set to")); pinSet = true }
        else shell("locksettings set-disabled false")
        shell("input keyevent KEYCODE_SLEEP"); shell("input keyevent KEYCODE_WAKEUP")
        until { keyguard.isKeyguardLocked && (!secure || keyguard.isDeviceLocked) }
    }
    private fun fixtureVisible(): Boolean {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return false
        return try { root.packageName == "local.voice.fixture" } finally { root.recycle() }
    }
    private fun pinVisible(): Boolean = instrumentation.uiAutomation.windows.any { window ->
        val root = window.root ?: return@any false
        try {
            val nodes = root.findAccessibilityNodeInfosByViewId("com.android.systemui:id/pinEntry")
            try { nodes.any { it.isVisibleToUser } } finally { nodes.forEach { it.recycle() } }
        } finally { root.recycle() }
    }
    private fun requestApp(): DeviceTools.Reply {
        val app = AppKnowledge(context).get("local.voice.fixture")!!
        val reply = DeviceTools(context).execute(VoiceCommand.Open(app.label))!!
        assertTrue(reply.text, reply.text.contains("Разблокируйте"))
        assertNull("Must not claim the app already opened", reply.openedPackage)
        assertTrue(reply.handoff); assertNotNull(reply.afterSpeech)
        instrumentation.runOnMainSync { assertNull(reply.afterSpeech!!.invoke()) }
        return reply
    }
    private fun player(mode: String) {
        context.startActivity(Intent().setClassName("local.voice.fixture", "local.voice.fixture.MediaActivity")
            .putExtra("mode", mode).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Thread.sleep(350)
    }
    private fun lockedPlay(artist: String? = null): Pair<Boolean, String> {
        val result = java.util.concurrent.LinkedBlockingQueue<Pair<Boolean, String>>()
        instrumentation.runOnMainSync {
            val reply = PackagePlayback.request(context, "local.voice.fixture", "Fixture", artist)
            assertNotNull(reply.text, reply.mediaHandoff)
            assertNull(reply.afterSpeech)
            reply.mediaHandoff!!.invoke { ok, text -> result.offer(ok to text) }
        }
        return result.poll(12, java.util.concurrent.TimeUnit.SECONDS) ?: error("Missing playback receipt")
    }
    private fun withPlayerAccess(test: () -> Unit) {
        val component = PhoneNotifications.component(context).flattenToString()
        val granted = PhoneNotifications.hasAccess(context)
        try {
            shell("cmd notification allow_listener $component")
            until { PhoneNotifications.hasAccess(context) }
            test()
        } finally {
            shell("am stopservice -n local.voice.fixture/.BrowserPlayerService")
            // Clear the credential before cleanup launches the disposable fixture Activity.
            if (pinSet) { assertTrue(shell("locksettings clear --old 564738").contains("cleared")); pinSet = false }
            shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard")
            player("clear")
            shell("cmd notification ${if (granted) "allow_listener" else "disallow_listener"} $component")
        }
    }
    @Test fun targetedResumeWorksUnderSecureLockAndNeverAsksForPin() = withPlayerAccess {
        player("focus")
        val controller = context.getSystemService(android.media.session.MediaSessionManager::class.java)
            .getActiveSessions(PhoneNotifications.component(context)).single { it.packageName == "local.voice.fixture" }
        controller.transportControls.pause()
        until { controller.playbackState?.state == android.media.session.PlaybackState.STATE_PAUSED }
        lock(true)
        val result = lockedPlay()
        assertTrue(result.second, result.first)
        assertEquals(android.media.session.PlaybackState.STATE_PLAYING, controller.playbackState!!.state)
        assertTrue(keyguard.isDeviceLocked); assertTrue(keyguard.isKeyguardLocked)
        assertFalse(pinVisible())
    }
    @Test fun coldBrowserResumptionAndArtistSearchWorkWithoutOpeningAnActivity() = withPlayerAccess {
        player("clear")
        lock(true)
        val result = lockedPlay("Synthetic Artist")
        assertTrue(result.second, result.first)
        assertTrue(keyguard.isDeviceLocked); assertFalse(pinVisible())
        val controller = context.getSystemService(android.media.session.MediaSessionManager::class.java)
            .getActiveSessions(PhoneNotifications.component(context)).single { it.packageName == "local.voice.fixture" }
        assertEquals("Synthetic Artist", controller.metadata!!.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST))
        assertFalse(fixtureVisible())
    }
    @Test fun ignoredPlaybackNeverClaimsSuccessUnderSecureLock() = withPlayerAccess {
        player("paused-ignore"); lock(true)
        val result = lockedPlay()
        assertFalse(result.second, result.first)
        assertTrue(result.second.contains("не подтвердил"))
        assertTrue(keyguard.isDeviceLocked); assertFalse(pinVisible())
    }
    @Test fun ninetyMinuteTimerCanBeCreatedWhilePinLocked() {
        assertTrue(PhoneClocks.exact(context)); assertTrue(PhoneClocks.alerts(context))
        lock(true)
        val before = ClockStore(context).use { it.list().map { e -> e.id }.toSet() }
        try {
            val command = VoiceCommands.parse("поставь таймер на полтора часа")
            val reply = DeviceTools(context).execute(command)!!
            assertTrue(reply.text, reply.text.contains("установлен"))
            val timer = ClockStore(context).use { it.list().single { e -> e.id !in before } }
            assertEquals(ClockKind.TIMER, timer.kind)
            assertTrue(PhoneClocks.remaining(context, timer) in 5_390_000L..5_400_000L)
            assertTrue(keyguard.isDeviceLocked); assertFalse(pinVisible())
        } finally {
            ClockStore(context).use { it.list().filter { e -> e.id !in before } }.forEach { PhoneClocks.cancel(context, it.id) }
        }
    }
    @Test fun secureLockRequestsSystemPinThenOpensThePreviouslyChosenApp() {
        lock(true)
        val app = AppKnowledge(context).get("local.voice.fixture")!!
        val replies = java.util.concurrent.LinkedBlockingQueue<String>()
        var conversation: VoiceConversation? = null
        try {
            instrumentation.runOnMainSync {
                conversation = VoiceConversation(context, {}, {}, textOnly = true, replyObserver = { replies.offer(it) })
                    .also { it.start(); it.respond("открой ${app.label}") }
            }
            assertTrue(replies.poll(3, java.util.concurrent.TimeUnit.SECONDS).orEmpty().contains("Разблокируйте"))
            until { pinVisible() }
            assertTrue(keyguard.isDeviceLocked); assertFalse(fixtureVisible())
            shell("input text 564738"); shell("input keyevent KEYCODE_ENTER")
            until { !keyguard.isDeviceLocked && fixtureVisible() }
        } finally { instrumentation.runOnMainSync { conversation?.close() } }
    }
    @Test fun cancellingSystemUnlockNeverLaunchesAfterALaterUnlock() {
        lock(true); requestApp(); until { pinVisible() }
        shell("input keyevent KEYCODE_BACK")
        Thread.sleep(400)
        assertTrue(keyguard.isDeviceLocked); assertFalse(fixtureVisible())
        // Restore the owned dummy credential externally, then verify the cancelled
        // application's request is not resurrected by the later unlock event.
        assertTrue(shell("locksettings clear --old 564738").contains("cleared")); pinSet = false
        shell("wm dismiss-keyguard")
        until { !keyguard.isDeviceLocked }
        Thread.sleep(500)
        assertFalse(fixtureVisible())
    }
    @Test fun swipeLockIsDismissedByAndroidWithoutAskingForACredential() {
        lock(false); requestApp()
        until { !keyguard.isKeyguardLocked && fixtureVisible() }
    }
    @Test fun changedLauncherCannotBeOpenedByAnOldUnlockRequest() {
        val component = android.content.ComponentName("local.voice.fixture", "local.voice.fixture.FieldActivity")
        val before = context.packageManager.getComponentEnabledSetting(component)
        try {
            lock(true); requestApp(); until { pinVisible() }
            shell("am broadcast -n local.voice.fixture/.LauncherStateReceiver --ei state 2")
            until { context.packageManager.getComponentEnabledSetting(component) == 2 }
            shell("input text 564738"); shell("input keyevent KEYCODE_ENTER")
            until { !keyguard.isDeviceLocked }
            var message = ""
            until {
                instrumentation.runOnMainSync {
                    fun texts(view: android.view.View): List<String> =
                        (if (view is android.widget.TextView) listOf(view.text.toString()) else emptyList()) +
                        if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) } else emptyList()
                    message = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<UnlockAppActivity>().flatMap { texts(it.window.decorView) }.joinToString(" | ")
                }
                message.contains("Список приложений изменился")
            }
            assertFalse(fixtureVisible())
        } finally {
            shell("am broadcast -n local.voice.fixture/.LauncherStateReceiver --ei state $before")
            until { context.packageManager.getComponentEnabledSetting(component) == before }
        }
    }
}
