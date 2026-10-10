package local.voice

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Durable inbox, scoped to a particular server credential profile. External text is data only. */
internal class HomeInbox(private val context: Context) {
    data class Entry(val seq: Long, val id: String, val title: String, val body: String, val source: String, val createdAt: Long)
    companion object {
        private const val CHANNEL = "hermes_messages_quiet_v1"
        @Volatile private var helper: Database? = null
        @Synchronized private fun database(context: Context) = helper ?: Database(context.applicationContext).also { helper = it }
        private class Database(context: Context) : SQLiteOpenHelper(context, "home-inbox.db", null, 1) {
            init { setWriteAheadLoggingEnabled(true) }
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL("CREATE TABLE inbox (device TEXT NOT NULL, id TEXT NOT NULL, seq INTEGER NOT NULL, data TEXT NOT NULL, announced INTEGER NOT NULL DEFAULT 0, seen INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(device,id), UNIQUE(device,seq))")
                db.execSQL("CREATE TABLE cursors (device TEXT PRIMARY KEY, seq INTEGER NOT NULL)")
            }
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
    }
    private val db get() = database(context).writableDatabase
    fun cursor(device: String): Long = db.rawQuery("SELECT seq FROM cursors WHERE device=?", arrayOf(device)).use { if (it.moveToFirst()) it.getLong(0) else 0 }
    fun accept(device: String, batch: JSONArray) {
        require(batch.length() <= 20)
        val parsed = (0 until batch.length()).map { index ->
            batch.getJSONObject(index).also { json ->
                require(json.keys().asSequence().toSet() == setOf("id", "seq", "kind", "source", "title", "body", "priority", "createdAt"))
                require(UUID.fromString(json.getString("id")).toString() == json.getString("id"))
                require(json.getLong("seq") > 0 && json.getLong("createdAt") > 0)
                require(json.getString("kind") in setOf("message", "update") && json.getString("priority") in setOf("normal", "important"))
                for ((key, limit) in listOf("title" to 160, "body" to 4000, "source" to 80)) require(json.getString(key).isNotBlank() && json.getString(key).length <= limit)
            }
        }
        require(parsed.zipWithNext().all { (a, b) -> a.getLong("seq") < b.getLong("seq") })
        val database = db
        database.beginTransaction()
        try {
            val count = android.database.DatabaseUtils.longForQuery(database, "SELECT count(*) FROM inbox WHERE device=? AND seen=0", arrayOf(device))
            val existing = parsed.count { json -> database.rawQuery("SELECT 1 FROM inbox WHERE device=? AND id=?", arrayOf(device, json.getString("id"))).use { it.moveToFirst() } }
            check(count + parsed.size - existing <= 1000) { "Входящие заполнены. Отметьте прочитанные сообщения." }
            var seq = cursor(device)
            parsed.forEach { json ->
                // Replayed deliveries do not create duplicate rows or repeat notifications.
                database.insertWithOnConflict("inbox", null, ContentValues().apply {
                    put("device", device); put("id", json.getString("id")); put("seq", json.getLong("seq")); put("data", json.toString())
                }, SQLiteDatabase.CONFLICT_IGNORE)
                seq = maxOf(seq, json.getLong("seq"))
            }
            database.insertWithOnConflict("cursors", null, ContentValues().apply { put("device", device); put("seq", seq) }, SQLiteDatabase.CONFLICT_REPLACE)
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
    }
    fun entries(device: String = HomeLink.device(context), unread: Boolean = false): List<Entry> = db.rawQuery(
        "SELECT seq,data FROM inbox WHERE device=?${if (unread) " AND seen=0" else ""} ORDER BY seq DESC LIMIT 30", arrayOf(device)).use { cursor ->
        buildList { while (cursor.moveToNext()) {
            val json = JSONObject(cursor.getString(1))
            add(Entry(cursor.getLong(0), json.getString("id"), json.getString("title"), json.getString("body"), json.getString("source"), json.getLong("createdAt")))
        } }
    }
    fun unreadCount(): Long = android.database.DatabaseUtils.longForQuery(db, "SELECT count(*) FROM inbox WHERE device=? AND seen=0", arrayOf(HomeLink.device(context)))
    fun markRead(device: String, through: Long) { db.execSQL("UPDATE inbox SET seen=1 WHERE device=? AND seq<=?", arrayOf(device, through)); trimRead(device) }
    fun allowed(): Boolean = !context.getSystemService(KeyguardManager::class.java).isDeviceLocked ||
        context.getSharedPreferences("settings", 0).getBoolean("home_read_when_locked", false)
    fun speech(latest: Boolean): String = reading(latest).first
    fun reading(latest: Boolean): Pair<String, List<Entry>> {
        if (!allowed()) return "Телефон заблокирован. Разблокируйте его для чтения сообщений от Гермеса." to emptyList()
        val messages = entries(unread = !latest).take(if (latest) 1 else 3)
        if (messages.isEmpty()) return "Новых сообщений от Гермеса нет." to emptyList()
        val text = messages.reversed().joinToString(" ") { "${it.source}. ${it.title}. ${it.body}" }
        return (if (text.length <= 1500) text else text.take(1430) + " Полный текст доступен во входящих помощника.") to messages
    }
    fun markSpoken(device: String, messages: List<Entry>) {
        messages.forEach { db.execSQL("UPDATE inbox SET seen=1 WHERE device=? AND id=?", arrayOf(device, it.id)) }
        trimRead(device)
    }
    fun notifyPending(device: String) {
        if (device != HomeLink.device(context) || !HomeLink.enabled(context)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Сообщения от Hermes", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Тихие сообщения домашнего агента. Озвучка только по запросу."
            setSound(null, null); enableVibration(false); lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED || !manager.areNotificationsEnabled() || manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return
        val pending = db.rawQuery("SELECT seq,data FROM inbox WHERE device=? AND announced=0 AND seen=0 ORDER BY seq LIMIT 100", arrayOf(device)).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getLong(0) to JSONObject(cursor.getString(1))) }
        }
        pending.forEach { (seq, json) ->
            if (device != HomeLink.device(context) || !HomeLink.enabled(context)) return
            val open = PendingIntent.getActivity(context, 0, Intent(context, HomeInboxActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val public = Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_assistant).setContentTitle("Сообщение от Hermes").setContentText("Откройте помощника для чтения").build()
            manager.notify("home-$device-${json.getString("id")}", 0, Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_assistant).setContentTitle(json.getString("title")).setContentText(json.getString("source"))
                .setStyle(Notification.BigTextStyle().bigText(json.getString("body"))).setContentIntent(open)
                .setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(public).setSound(null).setVibrate(null).setDefaults(0).setOnlyAlertOnce(true).setAutoCancel(true).build())
            db.execSQL("UPDATE inbox SET announced=1 WHERE device=? AND seq=?", arrayOf(device, seq))
        }
        trimRead(device)
    }
    private fun trimRead(device: String) {
        // Retention also applies when notification permission is denied.
        db.execSQL("DELETE FROM inbox WHERE device=? AND seen=1 AND seq NOT IN (SELECT seq FROM inbox WHERE device=? AND seen=1 ORDER BY seq DESC LIMIT 200)", arrayOf(device, device))
    }
}
