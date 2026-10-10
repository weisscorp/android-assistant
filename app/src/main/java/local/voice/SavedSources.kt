package local.voice

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class SourceKind(val title: String) { TELEGRAM("Чат Telegram"), WEB("Сайт / данные"), PLACE("Место / ориентир"), NOTE("Текст / заметка"), IMAGE("Картинка / снимок") }
data class SavedSource(val id: String = UUID.randomUUID().toString(), val name: String, val kind: SourceKind,
    val value: String, val participant: String = "", val aliases: List<String> = emptyList(), val attachment: String = "", val savedAt: Long = System.currentTimeMillis(),
    val instructions: String = "", val followLinks: Boolean = false, val readerRecipe: String = "")

/** User-owned knowledge and reader rules. There are no bundled sites, people or chat bindings. */
class SavedSources(private val context: Context) {
    private val db get() = database(context).writableDatabase
    companion object {
        @Volatile private var helper: Database? = null
        @Synchronized private fun database(context: Context): Database = helper ?: Database(context.applicationContext).also { helper = it }
        private class Database(context: Context) : SQLiteOpenHelper(context, "knowledge.db", null, 1) {
            init { setWriteAheadLoggingEnabled(true) }
            override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL("CREATE TABLE knowledge (id TEXT PRIMARY KEY, name TEXT NOT NULL, kind TEXT NOT NULL, value TEXT NOT NULL, participant TEXT NOT NULL, attachment TEXT NOT NULL, saved_at INTEGER NOT NULL, instructions TEXT NOT NULL, follow_links INTEGER NOT NULL, reader_recipe TEXT NOT NULL)")
                db.execSQL("CREATE TABLE aliases (key TEXT PRIMARY KEY, label TEXT NOT NULL, knowledge_id TEXT NOT NULL REFERENCES knowledge(id) ON DELETE CASCADE)")
                db.execSQL("CREATE TABLE observations (knowledge_id TEXT PRIMARY KEY REFERENCES knowledge(id) ON DELETE CASCADE, fingerprint TEXT NOT NULL, data TEXT NOT NULL)")
            }
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { error("Unsupported knowledge schema") }
        }
    }
    private fun row(cursor: Cursor): SavedSource {
        fun text(name: String) = cursor.getString(cursor.getColumnIndexOrThrow(name))
        val id = text("id")
        val name = text("name")
        val aliases = db.query("aliases", arrayOf("label"), "knowledge_id=?", arrayOf(id), null, null, "label").use { rows ->
            buildList { while (rows.moveToNext()) rows.getString(0).takeIf { PresenceFacts.key(it) != PresenceFacts.key(name) }?.let(::add) }
        }
        return SavedSource(id, name, SourceKind.valueOf(text("kind")), text("value"), text("participant"), aliases, text("attachment"),
            cursor.getLong(cursor.getColumnIndexOrThrow("saved_at")), text("instructions"), cursor.getInt(cursor.getColumnIndexOrThrow("follow_links")) == 1, text("reader_recipe"))
    }
    fun list(): List<SavedSource> = db.query("knowledge", null, null, null, null, null, "name COLLATE NOCASE").use { rows -> buildList { while (rows.moveToNext()) add(row(rows)) } }
    fun get(id: String): SavedSource? = db.query("knowledge", null, "id=?", arrayOf(id), null, null, null).use { if (it.moveToFirst()) row(it) else null }
    fun matching(alias: String): List<SavedSource> = db.rawQuery("SELECT knowledge.* FROM knowledge JOIN aliases ON aliases.knowledge_id=knowledge.id WHERE aliases.key=?", arrayOf(PresenceFacts.key(alias))).use { rows -> buildList { while (rows.moveToNext()) add(row(rows)) } }
    /** Candidate retrieval only; the LLM still chooses an existing record or asks for clarification. */
    fun relevant(query: String): List<SavedSource> = KnowledgeSelection.relevant(query, list())
    fun save(source: SavedSource) {
        require(source.id.matches(Regex("[a-f0-9-]{36}")) && PresenceFacts.validName(source.name)) { "Укажите название до 100 символов." }
        require(source.value.length <= 16000 && source.participant.length <= 100 && source.instructions.length <= 2000 && source.readerRecipe.length <= 8000 && source.aliases.size <= 10 && source.aliases.all(PresenceFacts::validName)) { "Слишком длинное описание." }
        require(source.attachment.isBlank() || source.attachment == "${source.id}.image")
        if (source.readerRecipe.isNotBlank()) { require(source.kind == SourceKind.WEB); KnowledgeReaderRecipe.decode(source.readerRecipe).endpoint(source.value) }
        when (source.kind) {
            SourceKind.WEB -> require(PresenceHttp.validUrl(source.value)) { "Для страницы нужен полный HTTPS-адрес." }
            SourceKind.TELEGRAM -> require(source.value.isNotBlank() && source.value.length <= 500 && (TelegramPresence.isTelegramLink(source.value) || !source.value.contains("://"))) { "Укажите имя чата либо ссылку t.me/tg." }
            SourceKind.PLACE -> require(PresenceFacts.pointFromLink(source.value) != null) { "Нужна ссылка geo:широта,долгота или ссылка на точку карты." }
            SourceKind.IMAGE -> require(image(source).isFile) { "Изображение не получено." }
            SourceKind.NOTE -> require(source.value.isNotBlank()) { "Введите текст." }
        }
        val database = db
        var old: SavedSource? = null
        database.beginTransaction()
        try {
            old = get(source.id)
            val others = list().filter { it.id != source.id }
            val keys = (source.aliases + source.name).map(PresenceFacts::key)
            require(others.none { other -> (other.aliases + other.name).any { PresenceFacts.key(it) in keys } }) { "Такое название или ключевое слово уже сохранено." }
            require(others.size < 100) { "Сохранено 100 знаний; удалите ненужное." }
            val values = ContentValues().apply {
                put("id", source.id); put("name", source.name.trim()); put("kind", source.kind.name); put("value", source.value)
                put("participant", source.participant.trim()); put("attachment", source.attachment); put("saved_at", source.savedAt)
                put("instructions", source.instructions.trim()); put("follow_links", if (source.followLinks) 1 else 0); put("reader_recipe", source.readerRecipe)
            }
            if (old == null) check(database.insertOrThrow("knowledge", null, values) != -1L)
            else check(database.update("knowledge", values, "id=?", arrayOf(source.id)) == 1)
            database.delete("aliases", "knowledge_id=?", arrayOf(source.id))
            (listOf(source.name) + source.aliases).distinctBy(PresenceFacts::key).forEach { label ->
                database.insertOrThrow("aliases", null, ContentValues().apply { put("key", PresenceFacts.key(label)); put("label", label.trim()); put("knowledge_id", source.id) })
            }
            if (old != null && (old.value != source.value || old.participant != source.participant || old.kind != source.kind || old.readerRecipe != source.readerRecipe || old.followLinks != source.followLinks)) database.delete("observations", "knowledge_id=?", arrayOf(source.id))
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
        if (old?.kind == SourceKind.IMAGE && source.kind != SourceKind.IMAGE) image(old).delete()
        HomeLink.kick(context)
    }
    fun delete(id: String) { val source = get(id); db.delete("knowledge", "id=?", arrayOf(id)); source?.let { image(it).delete() }; HomeLink.kick(context) }
    fun image(source: SavedSource) = File(File(context.filesDir, "shared_sources"), "${source.id}.image")
    fun previous(id: String, fingerprint: String = ""): GeoFix? = db.query("observations", arrayOf("data"), "knowledge_id=? AND fingerprint=?", arrayOf(id, fingerprint), null, null, null).use {
        if (it.moveToFirst()) runCatching { PresenceDocument.fix(JSONObject(it.getString(0))) }.getOrNull() else null
    }
    fun remember(id: String, fix: GeoFix, fingerprint: String = "") {
        if (!fix.valid() || !fix.fresh(System.currentTimeMillis())) return
        val database = db; database.beginTransaction()
        try {
            if (get(id) == null) return
            val old = previous(id, fingerprint)
            if (old?.updatedAt != null && old.updatedAt >= fix.updatedAt!!) return
            val json = JSONObject().put("latitude", fix.latitude).put("longitude", fix.longitude).put("updated_at", fix.updatedAt)
                .put("accuracy", fix.accuracy).put("heading", fix.heading).put("speed_mps", fix.speedMps)
            database.insertWithOnConflict("observations", null, ContentValues().apply { put("knowledge_id", id); put("fingerprint", fingerprint); put("data", json.toString()) }, SQLiteDatabase.CONFLICT_REPLACE)
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
        HomeLink.kick(context)
    }
    fun observation(id: String): JSONObject? = db.query("observations", arrayOf("data"), "knowledge_id=?", arrayOf(id), null, null, null).use {
        if (it.moveToFirst()) runCatching { JSONObject(it.getString(0)) }.getOrNull() else null
    }
    fun landmarks() = list().filter { it.kind == SourceKind.PLACE }.mapNotNull { item -> PresenceFacts.pointFromLink(item.value)?.let { item.name to it } }
}
