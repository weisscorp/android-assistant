package local.voice

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.util.UUID

/** Local durable display receipts and explicitly requested events. No event executes a tool. */
internal class HomeEvents(private val context: Context) {
    data class Outgoing(val message: String, val status: String)
    companion object {
        @Volatile private var helper: Database? = null
        @Synchronized private fun database(context: Context) = helper ?: Database(context.applicationContext).also { helper = it }
        private class Database(context: Context) : SQLiteOpenHelper(context, "home-events.db", null, 1) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL("CREATE TABLE outgoing (device TEXT NOT NULL,id TEXT PRIMARY KEY,body TEXT NOT NULL,targets TEXT NOT NULL,status TEXT NOT NULL,created INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX outgoing_queue ON outgoing(device,status,created)")
                db.execSQL("CREATE TABLE receipts (device TEXT NOT NULL,id TEXT NOT NULL,status TEXT NOT NULL,pending INTEGER NOT NULL,PRIMARY KEY(device,id))")
            }
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
    }
    private val db get() = database(context).writableDatabase
    fun retire(device: String) {
        // A saved credential/endpoint change must never send old events to a new profile.
        db.execSQL("UPDATE outgoing SET status='old_profile' WHERE device=? AND status='queued'", arrayOf(device))
        db.execSQL("DELETE FROM outgoing WHERE status!='queued' AND id NOT IN (SELECT id FROM outgoing WHERE status!='queued' ORDER BY created DESC LIMIT 100)")
        db.execSQL("DELETE FROM receipts WHERE device=?", arrayOf(device))
    }
    fun enqueue(text: String, targets: String = "auto", schedule: Boolean = true): String {
        require(HomeLink.enabled(context)) { "Подключите Hub и включите синхронизацию." }
        require(text.isNotBlank() && text.toByteArray().size <= 32_000) { "Событие пустое или слишком длинное." }
        require(targets in setOf("auto", "phone", "desktop", "both"))
        val device = HomeLink.device(context); val id = UUID.randomUUID().toString()
        val database = db; database.beginTransaction()
        try {
            check(device == HomeLink.device(context)) { "Подключение изменилось. Повторите отправку." }
            database.execSQL("UPDATE outgoing SET status='old_profile' WHERE device!=? AND status='queued'", arrayOf(device))
            check(DatabaseUtils.longForQuery(database, "SELECT count(*) FROM outgoing WHERE status='queued'", null) < 128) { "Очередь событий заполнена." }
            check(database.insertOrThrow("outgoing", null, ContentValues().apply {
                put("device", device); put("id", id); put("body", JSONObject().put("message", text).toString())
                put("targets", targets); put("status", "queued"); put("created", System.currentTimeMillis())
            }) != -1L)
            database.execSQL("DELETE FROM outgoing WHERE status!='queued' AND id NOT IN (SELECT id FROM outgoing WHERE status!='queued' ORDER BY created DESC LIMIT 100)")
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
        if (schedule) HomeLink.kick(context)
        return id
    }
    fun receipt(device: String, id: String, status: String) {
        require(status in setOf("submitted", "blocked"))
        db.execSQL("INSERT INTO receipts VALUES(?,?,?,1) ON CONFLICT(device,id) DO UPDATE SET status=excluded.status,pending=1 WHERE receipts.status!=excluded.status", arrayOf(device, id, status))
    }
    fun pendingCount(): Long = DatabaseUtils.longForQuery(db, "SELECT count(*) FROM outgoing WHERE device=? AND status='queued'", arrayOf(HomeLink.device(context)))
    fun recent(): List<Outgoing> = db.rawQuery("SELECT body,status FROM outgoing WHERE device=? ORDER BY created DESC LIMIT 10", arrayOf(HomeLink.device(context))).use { cursor ->
        buildList { while (cursor.moveToNext()) add(Outgoing(JSONObject(cursor.getString(0)).getString("message"), cursor.getString(1))) }
    }
    internal fun flush(device: String, operation: BackendClient.Request) {
        val outgoing = db.rawQuery("SELECT id,body,targets FROM outgoing WHERE device=? AND status='queued' ORDER BY created LIMIT 10", arrayOf(device)).use { cursor ->
            buildList { while (cursor.moveToNext()) add(Triple(cursor.getString(0), cursor.getString(1), cursor.getString(2))) }
        }
        for ((id, body, targets) in outgoing) {
            operation.ensureActive(); check(device == HomeLink.device(context))
            val (code, _) = BackendClient.webhook(context, id, body.toByteArray(), targets, operation)
            if (code == 404) break // Older server; keep the event and continue knowledge sync.
            if (code == 202 || code in setOf(400, 403)) db.execSQL("UPDATE outgoing SET status=? WHERE id=? AND device=?", arrayOf(if (code == 202) "accepted" else "failed", id, device))
            else error("Event delivery deferred")
        }
        val receipts = db.rawQuery("SELECT id,status FROM receipts WHERE device=? AND pending=1 LIMIT 100", arrayOf(device)).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1)) }
        }
        for ((id, status) in receipts) {
            operation.ensureActive(); check(device == HomeLink.device(context))
            val body = JSONObject().put("id", id).put("status", status).toString().toByteArray()
            val (code, _) = BackendClient.link(context, "POST", "/link/$device/receipt", body, operation)
            if (code == 404) break
            check(code == 200) { "Receipt delivery deferred" }
            db.execSQL("UPDATE receipts SET pending=0 WHERE device=? AND id=? AND status=?", arrayOf(device, id, status))
        }
        db.execSQL("DELETE FROM receipts WHERE pending=0 AND rowid NOT IN (SELECT rowid FROM receipts WHERE pending=0 ORDER BY rowid DESC LIMIT 1200)")
    }
}
