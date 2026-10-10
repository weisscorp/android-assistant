package local.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageInstaller
import android.media.AudioManager
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Background OTA callbacks must leave opening the installer to a user tap. */
@RunWith(AndroidJUnit4::class)
class QuietUpdateTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val notifications get() = context.getSystemService(NotificationManager::class.java)
    private class BackgroundContext(base: Context) : ContextWrapper(base) {
        var launches = 0
        override fun startActivity(intent: Intent) { launches++ }
    }
    private fun withUpdateState(test: (BackgroundContext) -> Unit) {
        val prefs = context.getSharedPreferences("updates", 0)
        val original = prefs.all.toMap()
        val channel = notifications.getNotificationChannel("updates")
        val quiet = notifications.getNotificationChannel("updates_quiet_v1")
        try {
            notifications.cancel(102)
            prefs.edit().clear().putInt("session", 71937).putString("callback", "quiet-update-test")
                .putString("state", "installing").commit()
            test(BackgroundContext(context))
        } finally {
            notifications.cancel(102)
            notifications.deleteNotificationChannel("updates")
            channel?.let(notifications::createNotificationChannel)
            notifications.deleteNotificationChannel("updates_quiet_v1")
            quiet?.let(notifications::createNotificationChannel)
            prefs.edit().clear().apply {
                original.forEach { (key, value) -> when (value) {
                    is String -> putString(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Float -> putFloat(key, value)
                } }
            }.commit()
        }
    }
    private fun confirmation() = Intent().putExtra(PackageInstaller.EXTRA_SESSION_ID, 71937)
        .putExtra("callback", "quiet-update-test")
        .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_PENDING_USER_ACTION)
        .putExtra(Intent.EXTRA_INTENT, Intent(Intent.ACTION_VIEW, Uri.parse("https://example.invalid/quiet-update-test")))
    private fun assertQuiet(context: BackgroundContext) {
        assertEquals("OTA callback opened an activity without a user tap", 0, context.launches)
        val deadline = System.currentTimeMillis() + 5000
        while (notifications.activeNotifications.none { it.id == 102 } && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val notice = notifications.activeNotifications.single { it.id == 102 }.notification
        assertEquals("updates_quiet_v1", notice.channelId)
        assertEquals("local.voice.updates.quiet", notice.group)
        assertNotNull("User must still be able to open the installer", notice.contentIntent)
        assertNull(notice.sound)
        assertNull(notice.vibrate)
        assertEquals(0, notice.defaults and (Notification.DEFAULT_SOUND or Notification.DEFAULT_VIBRATE))
        assertEquals(Notification.GROUP_ALERT_SUMMARY, notice.groupAlertBehavior)
        assertFalse(notice.flags and Notification.FLAG_GROUP_SUMMARY != 0)
        assertTrue(notice.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals("confirmation", context.getSharedPreferences("updates", 0).getString("state", ""))
        assertTrue(context.getSystemService(AudioManager::class.java).activeRecordingConfigurations.isEmpty())
        assertNull(VoiceConversation.activePhase)
    }
    @Test fun confirmationCallbackIsQuietAndRequiresUserTap() = withUpdateState { background ->
        Updates.result(background, confirmation())
        assertQuiet(background)
        notifications.getNotificationChannel("updates_quiet_v1").let {
            assertEquals(NotificationManager.IMPORTANCE_LOW, it.importance)
            assertNull(it.sound)
            assertFalse(it.shouldVibrate())
        }
    }
    @Test fun previousLoudChannelCannotMakeConfirmationCallbackAudible() = withUpdateState { background ->
        notifications.createNotificationChannel(NotificationChannel("updates", "Old updates", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI, null)
            enableVibration(true)
        })
        assertNotNull("Legacy test channel must actually have a sound", notifications.getNotificationChannel("updates").sound)
        Updates.result(background, confirmation())
        assertQuiet(background)
    }
    @Test fun packageReplacementOnlyReconcilesState() = withUpdateState { background ->
        background.getSharedPreferences("updates", 0).edit().putLong("version", 1).commit()
        UpdateReplaced().onReceive(background, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertEquals(0, background.launches)
        assertTrue(notifications.activeNotifications.none { it.id == 102 })
        assertEquals("installed", background.getSharedPreferences("updates", 0).getString("state", ""))
        assertTrue(background.getSystemService(AudioManager::class.java).activeRecordingConfigurations.isEmpty())
        assertNull(VoiceConversation.activePhase)
    }
}
