package local.voice

import android.Manifest
import android.app.NotificationManager
import android.content.ContentUris
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.SystemClock
import android.provider.CalendarContract
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ClockRuntimeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val ids = mutableListOf<Long>()
    private val events = mutableListOf<Long>()
    private var selected = -1L
    @Before fun ownedEmulatorOnly() {
        val property = instrumentation.uiAutomation.executeShellCommand("getprop ro.kernel.qemu")
        val qemu = android.os.ParcelFileDescriptor.AutoCloseInputStream(property).bufferedReader().use { it.readText().trim() }
        assertEquals("Clock tests require our emulator", "1", qemu)
        assertTrue(PhoneClocks.exact(context)); assertTrue(PhoneClocks.alerts(context)); assertTrue(ClockCalendar.permitted(context))
        selected = context.getSharedPreferences("settings",0).getLong("clock_calendar",-1)
        PhoneClocks.stopwatch(context,ClockOperation.RESET)
    }
    @After fun cleanup() {
        ids.forEach { PhoneClocks.cancel(context,it) }
        events.forEach { ClockCalendar.delete(context,it) }
        context.getSharedPreferences("settings",0).edit().putLong("clock_calendar",selected).commit()
        PhoneClocks.stopwatch(context,ClockOperation.RESET)
    }
    private fun entries() = ClockStore(context).use { it.list() }
    private fun entry(id: Long) = ClockStore(context).use { it.get(id) }!!
    private fun record(e: ClockEntry): ClockEntry { ids += e.id; e.calendarEvent?.let { events += it }; return e }
    private fun until(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime()+10_000
        while(!condition() && SystemClock.elapsedRealtime()<deadline) Thread.sleep(50)
        assertTrue("Clock condition timed out",condition())
    }
    @Test fun exactTimerFiresRealBroadcastAndRingingServiceOnce() {
        val e = record(PhoneClocks.create(context,ClockKind.TIMER,"Clock test timer",Instant.now().plusSeconds(2),2))
        PhoneClocks.fire(context,e.id) // An early or stale delivery must not fire it.
        assertEquals("pending",entry(e.id).state)
        until { entry(e.id).state == "fired" }
        until { context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == AlarmRingService.NOTIFICATION_ID } }
        val notifications = context.getSystemService(NotificationManager::class.java)
        val posted = notifications.activeNotifications.single { it.id == AlarmRingService.NOTIFICATION_ID }
        assertEquals("Clock test timer",posted.notification.extras.getString("android.text"))
        assertFalse(posted.notification.publicVersion.extras.toString().contains("Clock test timer"))
        PhoneClocks.fire(context,e.id)
        assertEquals(1,notifications.activeNotifications.count { it.id == AlarmRingService.NOTIFICATION_ID })
        assertTrue(PhoneClocks.cancel(context,e.id))
        until { notifications.activeNotifications.none { it.id == AlarmRingService.NOTIFICATION_ID } }
    }
    @Test fun cancelledTimerCannotFireAndTimerIgnoresWallClockChanges() {
        val e = record(PhoneClocks.create(context,ClockKind.TIMER,"Clock test cancelled",Instant.now().plusSeconds(60),60))
        val elapsed = e.elapsed-45_000
        assertEquals(45_000L,PhoneClocks.remaining(context,e,wall=e.wall+86_400_000,elapsed=elapsed))
        PhoneClocks.cancel(context,e.id); PhoneClocks.fire(context,e.id)
        assertEquals("cancelled",entry(e.id).state)
        assertFalse(context.getSystemService(NotificationManager::class.java).activeNotifications.any { it.tag=="clock" && it.id==e.id.toInt() })
    }
    @Test fun stopwatchSurvivesStoreReopeningAndPauseResumeAndLaps() {
        PhoneClocks.stopwatch(context,ClockOperation.START); Thread.sleep(120)
        val (paused,_) = PhoneClocks.stopwatch(context,ClockOperation.PAUSE)
        assertTrue(paused>=100); Thread.sleep(100)
        assertEquals(paused,PhoneClocks.stopwatch(context,ClockOperation.STATUS).first)
        assertEquals(paused,ClockStore(context).use { it.stopwatch(PhoneClocks.boot(context)).milliseconds(SystemClock.elapsedRealtime()) })
        PhoneClocks.stopwatch(context,ClockOperation.RESUME); Thread.sleep(110)
        val (continued,laps) = PhoneClocks.stopwatch(context,ClockOperation.LAP)
        assertTrue(continued>=paused+100); assertEquals(listOf(continued),laps)
        assertEquals(0L,ClockStore(context).use { it.stopwatch(PhoneClocks.boot(context)+1).milliseconds(SystemClock.elapsedRealtime()) })
        assertEquals(0L,PhoneClocks.stopwatch(context,ClockOperation.RESET).first)
    }
    @Test fun rebootRecoveryRebasesTimersAndDoesNotRingOldAlarms() {
        val timer = record(ClockStore(context).use { it.add(ClockKind.TIMER,"Clock test reboot",System.currentTimeMillis()+60_000,1000,PhoneClocks.boot(context)-1) })
        val stale = record(ClockStore(context).use { it.add(ClockKind.ALARM,"Clock test missed",System.currentTimeMillis()-600_000,0,PhoneClocks.boot(context)-1) })
        val staleTimer = record(ClockStore(context).use { it.add(ClockKind.TIMER,"Clock test missed timer",System.currentTimeMillis()-600_000,1000,PhoneClocks.boot(context)-1) })
        ClockReceiver().onReceive(context,Intent(Intent.ACTION_BOOT_COMPLETED))
        val restored = entry(timer.id)
        assertEquals(PhoneClocks.boot(context),restored.boot)
        assertTrue(PhoneClocks.remaining(context,restored) in 55_000..60_000)
        assertEquals("missed",entry(stale.id).state)
        assertEquals("missed",entry(staleTimer.id).state)
    }
    @Test fun settingsOpensClockManagementAndReturnsWithoutStartingVoice() {
        val home = instrumentation.startActivitySync(Intent(context,SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SettingsActivity
        fun views(v: android.view.View): List<android.view.View> = listOf(v) + if(v is android.view.ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
        var screen: ClockActivity? = null
        try {
            instrumentation.runOnMainSync {
                assertTrue(views(home.window.decorView).single { it.isClickable && it.contentDescription?.toString()?.startsWith("Время и напоминания.")==true }.performClick())
            }
            until {
                instrumentation.runOnMainSync { screen = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<ClockActivity>().singleOrNull() }
                screen != null
            }
            instrumentation.runOnMainSync {
                val text = views(screen!!.window.decorView).filterIsInstance<android.widget.TextView>().map { it.text.toString() }
                assertTrue(text.containsAll(listOf("Секундомер","Пауза","Создать локальный календарь","Разрешить точные таймеры и будильники")))
                assertTrue(views(screen!!.window.decorView).filterIsInstance<android.widget.Button>().single { it.text=="← Назад" }.performClick())
            }
            assertTrue(context.getSystemService(AudioManager::class.java).activeRecordingConfigurations.isEmpty())
        } finally { instrumentation.runOnMainSync { screen?.finish(); home.finish() } }
    }
    @Test fun reminderNeedsConfirmationAndWritesRealCalendarEventAndOwnAlert() {
        val calendar = ClockCalendar.createLocal(context)
        val tools = ClockTools(context)
        val before = entries().map { it.id }.toSet()
        val offered = tools.offer(ClockRequests.parse("напомни через десять минут Clock test reminder")!!,false)
        assertTrue(offered.actionPending); assertTrue(offered.text.contains(calendar.name))
        assertEquals(before,entries().map { it.id }.toSet())
        val reply = tools.answer(VoiceCommand.Confirm,"подтверждаю")!!
        assertTrue(reply.text.contains("записано"))
        val e = record(entries().single { it.id !in before })
        assertEquals(ClockKind.REMINDER,e.kind)
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI,e.calendarEvent!!)
        context.contentResolver.query(uri,arrayOf(CalendarContract.Events.TITLE,CalendarContract.Events.DTSTART,CalendarContract.Events.HAS_ALARM),null,null,null)!!.use {
            assertTrue(it.moveToFirst()); assertEquals("Clock test reminder",it.getString(0)); assertEquals(e.wall,it.getLong(1)); assertEquals(0,it.getInt(2))
        }
        assertTrue(PhoneClocks.cancel(context,e.id,deleteCalendar=true))
        context.contentResolver.query(uri,arrayOf("deleted"),null,null,null)!!.use { assertTrue(!it.moveToFirst() || it.getInt(0)==1) }
    }
    @Test fun explicitAlarmFiresWithStoppableForegroundNotification() {
        // Silence only this owned emulator; no microphone, physical phone or audible check.
        val audio = context.getSystemService(AudioManager::class.java)
        val volume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        audio.setStreamVolume(AudioManager.STREAM_ALARM,0,0)
        try {
            val e = record(PhoneClocks.create(context,ClockKind.ALARM,"Clock test alarm",Instant.now().plusSeconds(2)))
            until { entry(e.id).state == "fired" }
            val notifications = context.getSystemService(NotificationManager::class.java)
            until { notifications.activeNotifications.any { it.id==7001 && it.notification.channelId==PhoneClocks.RING_CHANNEL } }
            PhoneClocks.cancel(context,e.id)
            until { notifications.activeNotifications.none { it.id==7001 } }
        } finally { audio.setStreamVolume(AudioManager.STREAM_ALARM,volume,0) }
    }
    @Test fun missingNotificationPermissionNeverClaimsOrSchedulesSuccess() {
        val denied = object : ContextWrapper(context) {
            override fun checkSelfPermission(permission: String) = if(permission==Manifest.permission.POST_NOTIFICATIONS) PackageManager.PERMISSION_DENIED else super.checkSelfPermission(permission)
        }
        val before = entries().map { it.id }
        val reply = ClockTools(denied).offer(ClockRequests.parse("таймер на десять минут")!!,false)
        assertTrue(reply.text.contains("Разрешите уведомления")); assertEquals(before,entries().map { it.id })
    }
    @Test fun newCommandsClearConfirmationsAndRemoteToolsCannotCreateUnrequestedClocks() {
        val tools = ClockTools(context); val before = entries().map { it.id }
        assertTrue(tools.offer(ClockRequests.parse("будильник завтра в семь")!!,false).actionPending)
        assertNull(tools.answer(VoiceCommand.Battery,"заряд"))
        assertNull(tools.answer(VoiceCommand.Confirm,"подтверждаю")); assertEquals(before,entries().map { it.id })
        val remote = RemotePhoneTools.decode(org.json.JSONObject("""{"tool":"clock","arguments":{"request":"таймер на десять минут"}}"""))
        assertTrue(remote is VoiceCommand.Clock && remote.proposed)
        assertTrue(RemotePhoneTools.requested(remote,"поставь таймер на десять минут"))
        assertFalse(RemotePhoneTools.requested(remote,"где сейчас мой ребёнок"))
        assertFalse(RemotePhoneTools.requested(remote,"отмени все таймеры"))
        assertFalse(RemotePhoneTools.requested(remote,"запусти секундомер"))
        assertTrue(tools.offer((remote as VoiceCommand.Clock).request,remote.proposed).actionPending)
        tools.answer(VoiceCommand.Cancel,"отмена"); assertEquals(before,entries().map { it.id })
    }
    @Test fun typedTimerAndSpokenDurationClarificationUseNoModelMicrophoneOrSpeech() {
        context.startActivity(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val replies = java.util.concurrent.LinkedBlockingQueue<String>()
        var conversation: VoiceConversation? = null
        val before = entries().map { it.id }.toSet()
        instrumentation.runOnMainSync {
            conversation = VoiceConversation(context,{}, {},textOnly=true,replyObserver={ replies.offer(it) }).also { it.start(); it.respond("поставь таймер") }
        }
        try {
            assertTrue(replies.poll(3,TimeUnit.SECONDS)!!.contains("На сколько"))
            instrumentation.runOnMainSync { conversation!!.respond("две минуты") }
            assertTrue(replies.poll(3,TimeUnit.SECONDS)!!.contains("установлен"))
            val e = record(entries().single { it.id !in before })
            assertEquals(ClockKind.TIMER,e.kind)
            assertTrue(PhoneClocks.remaining(context,e) in 115_000..120_000)
            assertTrue(context.getSystemService(AudioManager::class.java).activeRecordingConfigurations.isEmpty())
            assertEquals(0L,conversation!!.speechFrames); assertNull(conversation!!.speechOutputDeviceType)
        } finally { instrumentation.runOnMainSync { conversation?.close() } }
    }
    @Test fun nativeGrammarSupportsClockTool() {
        val done = CountDownLatch(1); var proposal: LocalBrain.Proposal? = null; var raw = ""
        instrumentation.runOnMainSync { LocalBrain.plan(context,emptyList(),"Поставь таймер на пять минут",rawObserver={raw=it}) { proposal=it; done.countDown() } }
        assertTrue("Native clock planner timed out",done.await(45,TimeUnit.SECONDS))
        java.io.File(context.filesDir,"clock-planner.json").writeText(org.json.JSONObject().put("model",LocalBrain.model(context).name).put("raw",raw).put("proposal",proposal.toString()).toString())
        assertTrue("Native clock proposal: $proposal; raw=$raw", proposal?.command is VoiceCommand.Clock)
        assertEquals(300L,(proposal!!.command as VoiceCommand.Clock).request.seconds)
    }
}
