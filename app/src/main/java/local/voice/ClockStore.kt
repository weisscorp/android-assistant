package local.voice

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteDatabase
import android.media.AudioAttributes
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class ClockEntry(val id: Long, val kind: ClockKind, val title: String, val wall: Long,
    val elapsed: Long, val boot: Int, val state: String, val calendarEvent: Long?)

/** Durable, one-shot clocks. Timer deadlines use monotonic time on the same boot. */
class ClockStore(context: Context) : SQLiteOpenHelper(context.applicationContext, "clocks.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE clocks(id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, title TEXT NOT NULL, wall INTEGER NOT NULL, elapsed INTEGER NOT NULL, boot INTEGER NOT NULL, state TEXT NOT NULL, calendar_event INTEGER)")
        db.execSQL("CREATE TABLE stopwatch(id INTEGER PRIMARY KEY CHECK(id=1), running INTEGER NOT NULL, accumulated INTEGER NOT NULL, started INTEGER NOT NULL, boot INTEGER NOT NULL, laps TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) = Unit
    fun add(kind: ClockKind, title: String, wall: Long, elapsed: Long, boot: Int, event: Long? = null): ClockEntry {
        val values = ContentValues().apply {
            put("kind",kind.name); put("title",title); put("wall",wall); put("elapsed",elapsed); put("boot",boot); put("state","pending")
            if (event != null) put("calendar_event",event)
        }
        val id = writableDatabase.insertOrThrow("clocks",null,values)
        return get(id)!!
    }
    fun get(id: Long): ClockEntry? = rows("id=?", arrayOf(id.toString())).singleOrNull()
    fun list(kind: ClockKind? = null): List<ClockEntry> = rows("state IN ('pending','fired')" + if(kind == null) "" else " AND kind=?", if(kind == null) emptyArray() else arrayOf(kind.name))
    private fun rows(where: String, args: Array<String>): List<ClockEntry> = readableDatabase.query("clocks",null,where,args,null,null,"wall ASC", "100").use { c ->
        buildList { while(c.moveToNext()) add(ClockEntry(c.getLong(c.getColumnIndexOrThrow("id")),ClockKind.valueOf(c.getString(c.getColumnIndexOrThrow("kind"))),c.getString(c.getColumnIndexOrThrow("title")),c.getLong(c.getColumnIndexOrThrow("wall")),c.getLong(c.getColumnIndexOrThrow("elapsed")),c.getInt(c.getColumnIndexOrThrow("boot")),c.getString(c.getColumnIndexOrThrow("state")),c.getColumnIndexOrThrow("calendar_event").let { if(c.isNull(it)) null else c.getLong(it) })) }
    }
    fun state(id: Long, value: String, expected: String? = null): Boolean = writableDatabase.update("clocks",ContentValues().apply { put("state",value) }, "id=?" + if(expected == null) "" else " AND state=?", if(expected == null) arrayOf(id.toString()) else arrayOf(id.toString(),expected)) == 1
    fun rebase(entry: ClockEntry, boot: Int, elapsed: Long) {
        writableDatabase.update("clocks",ContentValues().apply { put("boot",boot); put("elapsed",elapsed) },"id=? AND state='pending'",arrayOf(entry.id.toString()))
    }
    data class Stopwatch(val running: Boolean, val accumulated: Long, val started: Long, val boot: Int, val laps: List<Long>) {
        fun milliseconds(now: Long) = accumulated + if(running) maxOf(0,now-started) else 0
    }
    fun stopwatch(boot: Int): Stopwatch = readableDatabase.rawQuery("SELECT running, accumulated, started, boot, laps FROM stopwatch WHERE id=1",null).use { c ->
        if(!c.moveToFirst() || c.getInt(3) != boot) Stopwatch(false,0,0,boot,emptyList())
        else Stopwatch(c.getInt(0)==1,c.getLong(1),c.getLong(2),c.getInt(3),c.getString(4).split(',').mapNotNull(String::toLongOrNull))
    }
    fun stopwatch(value: Stopwatch) {
        writableDatabase.insertWithOnConflict("stopwatch",null,ContentValues().apply { put("id",1); put("running",if(value.running) 1 else 0); put("accumulated",value.accumulated); put("started",value.started); put("boot",value.boot); put("laps",value.laps.joinToString(",")) },SQLiteDatabase.CONFLICT_REPLACE)
    }
}

object PhoneClocks {
    const val CHANNEL = "assistant_clocks"
    const val RING_CHANNEL = "assistant_alarm_ring"
    private const val FIRE = "local.voice.CLOCK_FIRE"
    private const val DISMISS = "local.voice.CLOCK_DISMISS"
    fun boot(context: Context) = Settings.Global.getInt(context.contentResolver,Settings.Global.BOOT_COUNT,0)
    fun exact(context: Context) = context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    fun alerts(context: Context): Boolean {
        val manager=context.getSystemService(NotificationManager::class.java)
        return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED && manager.areNotificationsEnabled() &&
            (manager.getNotificationChannel(CHANNEL)?.importance ?: NotificationManager.IMPORTANCE_HIGH) != NotificationManager.IMPORTANCE_NONE &&
            (manager.getNotificationChannel(RING_CHANNEL)?.importance ?: NotificationManager.IMPORTANCE_HIGH) != NotificationManager.IMPORTANCE_NONE
    }
    fun issue(context: Context): String? = when {
        !exact(context) -> "Разрешите точные таймеры и будильники в Параметрах, Время и напоминания. Пока ничего не установлено."
        !alerts(context) -> "Разрешите уведомления таймеров и будильников в Параметрах, Время и напоминания. Пока ничего не установлено."
        else -> null
    }
    private fun intent(context: Context, id: Long, action: String = FIRE) = Intent(context,ClockReceiver::class.java).setAction(action).setData(Uri.parse("assistant-clock://clock/$id")).putExtra("clock_id",id)
    private fun pending(context: Context, id: Long, action: String = FIRE) = PendingIntent.getBroadcast(context,0,intent(context,id,action),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun remaining(context: Context, entry: ClockEntry, wall: Long = System.currentTimeMillis(), elapsed: Long = SystemClock.elapsedRealtime()): Long =
        if(entry.kind == ClockKind.TIMER && entry.boot == boot(context)) entry.elapsed-elapsed else entry.wall-wall
    fun schedule(context: Context, entry: ClockEntry) {
        check(exact(context)) { "Точные будильники недоступны." }
        val manager=context.getSystemService(AlarmManager::class.java)
        val fire=pending(context,entry.id)
        if(entry.kind == ClockKind.ALARM) {
            val show=PendingIntent.getActivity(context,0,Intent(context,ClockActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            manager.setAlarmClock(AlarmManager.AlarmClockInfo(entry.wall,show),fire)
        } else if(entry.kind == ClockKind.TIMER) {
            manager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,SystemClock.elapsedRealtime()+maxOf(0,remaining(context,entry)),fire)
        } else manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,entry.wall,fire)
    }
    @Synchronized fun create(context: Context, kind: ClockKind, title: String, at: Instant, seconds: Long? = null, event: Long? = null): ClockEntry {
        issue(context)?.let { error(it) }
        check(at.toEpochMilli()>System.currentTimeMillis()) { "Указанное время уже прошло. Назовите новое время." }
        ClockStore(context).use { store ->
            check(store.list().size < 50) { "Уже сохранено пятьдесят активных часов. Отмените ненужные." }
            val entry=store.add(kind,title,at.toEpochMilli(),if(seconds != null) SystemClock.elapsedRealtime()+seconds*1000 else 0,boot(context),event)
            try { schedule(context,entry) } catch(e: Exception) { store.state(entry.id,"cancelled"); throw e }
            return entry
        }
    }
    @Synchronized fun cancel(context: Context, id: Long, deleteCalendar: Boolean = false): Boolean {
        ClockStore(context).use { store ->
            val entry=store.get(id) ?: return false
            if(deleteCalendar && entry.calendarEvent != null) ClockCalendar.delete(context,entry.calendarEvent)
            if(!store.state(id,"cancelled")) return false
            context.getSystemService(AlarmManager::class.java).cancel(pending(context,id))
            context.getSystemService(NotificationManager::class.java).cancel("clock",id.toInt())
            AlarmRingService.dismiss(context,id)
            return true
        }
    }
    @Synchronized fun fire(context: Context, id: Long) {
        ClockStore(context).use { store ->
            val entry=store.get(id) ?: return
            if(entry.state != "pending") return
            if(remaining(context,entry)>50) { runCatching { schedule(context,entry) }; return }
            if(!store.state(id,"fired","pending")) return
            if(entry.kind == ClockKind.ALARM) {
                runCatching { context.startForegroundService(Intent(context,AlarmRingService::class.java).putExtra("clock_id",id)) }
                    .onFailure { notify(context,entry) }
            } else notify(context,entry)
        }
    }
    fun notification(context: Context, entry: ClockEntry, ringing: Boolean = false): Notification {
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,"Таймеры и напоминания",NotificationManager.IMPORTANCE_HIGH).apply {
            description="Только установленные вами таймеры и напоминания"
            lockscreenVisibility=Notification.VISIBILITY_PRIVATE
        })
        manager.createNotificationChannel(NotificationChannel(RING_CHANNEL,"Будильники",NotificationManager.IMPORTANCE_HIGH).apply {
            description="Звонок управляется ассистентом; беззвучный режим и Не беспокоить соблюдаются"
            setSound(null,AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()); enableVibration(false)
            lockscreenVisibility=Notification.VISIBILITY_PRIVATE
        })
        val label=when(entry.kind) { ClockKind.TIMER -> "Таймер завершён"; ClockKind.ALARM -> "Будильник"; else -> "Напоминание" }
        val open=PendingIntent.getActivity(context,0,Intent(context,ClockActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(context,if(ringing) RING_CHANNEL else CHANNEL).setSmallIcon(R.drawable.ic_assistant)
            .setContentTitle(label).setContentText(entry.title.ifBlank { label }).setContentIntent(open).setAutoCancel(!ringing)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setCategory(if(entry.kind == ClockKind.ALARM) Notification.CATEGORY_ALARM else Notification.CATEGORY_REMINDER)
            .setPublicVersion(Notification.Builder(context,if(ringing) RING_CHANNEL else CHANNEL).setSmallIcon(R.drawable.ic_assistant).setContentTitle(label).build())
            .addAction(Notification.Action.Builder(null,"Остановить",pending(context,entry.id,DISMISS)).build())
            .setDeleteIntent(pending(context,entry.id,DISMISS)).setOngoing(ringing).build()
    }
    internal fun notify(context: Context, entry: ClockEntry) {
        if(alerts(context)) runCatching { context.getSystemService(NotificationManager::class.java).notify("clock",entry.id.toInt(),notification(context,entry)) }
    }
    @Synchronized fun restore(context: Context) {
        if(!exact(context)) return
        ClockStore(context).use { store ->
            store.list().filter { it.state=="pending" }.forEach { old ->
                // Do not unexpectedly signal stale clocks after a prolonged shutdown.
                if(remaining(context,old)<-5*60_000) { store.state(old.id,"missed"); return@forEach }
                var entry=old
                if(entry.kind==ClockKind.TIMER && entry.boot != boot(context)) {
                    store.rebase(entry,boot(context),SystemClock.elapsedRealtime()+maxOf(0,entry.wall-System.currentTimeMillis()))
                    entry=store.get(entry.id)!!
                }
                runCatching { schedule(context,entry) }
            }
        }
    }
    fun description(entry: ClockEntry): String = "${entry.title.ifBlank { when(entry.kind) { ClockKind.TIMER -> "Таймер"; ClockKind.ALARM -> "Будильник"; else -> "Напоминание" } }} · ${DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(entry.wall))}"
    @Synchronized fun stopwatch(context: Context, operation: ClockOperation): Pair<Long,List<Long>> {
        val now=SystemClock.elapsedRealtime()
        ClockStore(context).use { store ->
            val old=store.stopwatch(boot(context)); val elapsed=old.milliseconds(now)
            val next=when(operation) {
                ClockOperation.START,ClockOperation.RESUME -> if(old.running) old else old.copy(running=true,started=now)
                ClockOperation.PAUSE -> old.copy(running=false,accumulated=elapsed,started=0)
                ClockOperation.RESET -> ClockStore.Stopwatch(false,0,0,boot(context),emptyList())
                ClockOperation.LAP -> old.copy(laps=(old.laps+elapsed).takeLast(50))
                else -> old
            }
            store.stopwatch(next)
            return next.milliseconds(now) to next.laps
        }
    }
}

class ClockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when(intent.action) {
            "local.voice.CLOCK_FIRE" -> PhoneClocks.fire(context,intent.getLongExtra("clock_id",-1))
            "local.voice.CLOCK_DISMISS" -> PhoneClocks.cancel(context,intent.getLongExtra("clock_id",-1))
            else -> PhoneClocks.restore(context)
        }
    }
}
