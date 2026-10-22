package local.voice

import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ResolveInfo
import android.content.res.Configuration
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Handler
import android.os.Looper
import java.util.Locale
import java.util.concurrent.Executors

/** Device-owned application knowledge. Package identity comes only from PackageManager. */
internal class AppKnowledge(context: Context) {
    private val context = context.applicationContext
    private val db get() = database(this.context).writableDatabase
    data class App(val pkg: String, val label: String, val russian: String, val version: Long, val installedAt: Long, val updatedAt: Long)
    companion object {
        @Volatile private var helper: Database? = null
        @Volatile private var ready = false
        @Volatile private var started = false
        private val lock = Any()
        private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "app-knowledge").apply { priority = Thread.MIN_PRIORITY } }
        private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
        fun observe(listener: () -> Unit) { listeners.add(listener) }
        fun unobserve(listener: () -> Unit) { listeners.remove(listener) }
        private fun changed() { Handler(Looper.getMainLooper()).post { listeners.forEach { it() } } }
        @Volatile internal var scanCount = 0; private set
        @Synchronized private fun database(context: Context) = helper ?: Database(context).also { helper = it }
        private class Database(context: Context) : SQLiteOpenHelper(context, "applications.db", null, 1) {
            init { setWriteAheadLoggingEnabled(true) }
            override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL("CREATE TABLE apps (package TEXT PRIMARY KEY, label TEXT NOT NULL, russian TEXT NOT NULL, custom_name TEXT NOT NULL DEFAULT '', version INTEGER NOT NULL, installed_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, available INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE names (package TEXT NOT NULL REFERENCES apps(package) ON DELETE CASCADE, key TEXT NOT NULL, label TEXT NOT NULL, origin TEXT NOT NULL, PRIMARY KEY(package,key,origin))")
                db.execSQL("CREATE INDEX app_name_lookup ON names(key,package)")
            }
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unsupported application knowledge schema")
        }
        fun start(context: Context) {
            synchronized(lock) {
                if (started) return
                val app = context.applicationContext
                val filter = IntentFilter().apply {
                    addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
                    addAction(Intent.ACTION_PACKAGE_REPLACED); addAction(Intent.ACTION_PACKAGE_CHANGED); addDataScheme("package")
                }
                app.registerReceiver(object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        val pkg = intent.data?.schemeSpecificPart ?: return
                        if (intent.action == Intent.ACTION_PACKAGE_REMOVED && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
                        worker.execute { runCatching { AppKnowledge(app).refreshPackage(pkg) } }
                    }
                }, filter, Context.RECEIVER_NOT_EXPORTED)
                started = true
            }
            worker.execute { runCatching { AppKnowledge(context).ensureReady() } }
        }
    }
    fun ensureReady() { if (!ready) synchronized(lock) { if (!ready) refresh() } }
    fun refreshAsync(done: (String?) -> Unit = {}) {
        worker.execute {
            val failure = runCatching { refresh() }.exceptionOrNull()?.let { "Не удалось обновить каталог приложений." }
            Handler(Looper.getMainLooper()).post { done(failure) }
        }
    }
    private fun launchers(pkg: String? = null): List<ResolveInfo> = context.packageManager.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).apply { if (pkg != null) setPackage(pkg) }, 0)
    private fun russianLabel(info: ResolveInfo): String = runCatching {
        val activity = info.activityInfo
        val packageContext = context.createPackageContext(activity.packageName, Context.CONTEXT_IGNORE_SECURITY)
        val config = Configuration(packageContext.resources.configuration).apply { setLocale(Locale("ru")) }
        val resources = packageContext.createConfigurationContext(config).resources
        val resource = info.labelRes.takeIf { it != 0 } ?: activity.labelRes.takeIf { it != 0 } ?: activity.applicationInfo.labelRes
        if (resource != 0) resources.getText(resource).toString() else info.loadLabel(context.packageManager).toString()
    }.getOrDefault(info.loadLabel(context.packageManager).toString()).take(120)
    private fun upsert(database: SQLiteDatabase, pkg: String, launchers: List<ResolveInfo>) {
        val info = context.packageManager.getPackageInfo(pkg, 0)
        val labels = launchers.map { it.loadLabel(context.packageManager).toString().take(120) }.filter(String::isNotBlank).distinct()
        if (labels.isEmpty()) return
        val existing = get(pkg, availableOnly = false)
        // A normal cold start refreshes presence/version only. Name generation is not repeated.
        val changed = existing == null || existing.label !in labels || existing.updatedAt != info.lastUpdateTime || aliases(pkg).isEmpty()
        val localized = if (changed) launchers.map(::russianLabel).distinct() else emptyList()
        val allNames = if (changed) ((labels + localized + labels.flatMap(AppNames::seededAliases)).flatMap(AppSpeechNames::variants)).distinct() else emptyList()
        val russian = if (!changed) existing!!.russian else localized.firstOrNull { it.any { c -> c in 'А'..'я' || c in "ёЁ" } }
            ?: labels.flatMap(AppNames::seededAliases).firstOrNull()
            ?: AppSpeechNames.variants(labels.first()).firstOrNull { it.any { c -> c in 'А'..'я' || c in "ёЁ" } } ?: labels.first()
        val values = ContentValues().apply {
            put("package", pkg); put("label", labels.first()); if (changed) put("russian", russian)
            put("version", info.longVersionCode); put("installed_at", info.firstInstallTime); put("updated_at", info.lastUpdateTime); put("available", 1)
        }
        if (existing == null) database.insertOrThrow("apps", null, values)
        else database.update("apps", values, "package=?", arrayOf(pkg))
        if (changed) {
            database.delete("names", "package=? AND origin='auto'", arrayOf(pkg))
            for (name in allNames) addName(database, pkg, name, "auto")
        }
    }
    private fun addName(database: SQLiteDatabase, pkg: String, name: String, origin: String) {
        val key = AppNames.canonical(name)
        if (key.isBlank()) return
        database.insertWithOnConflict("names", null, ContentValues().apply { put("package", pkg); put("key", key); put("label", name.trim()); put("origin", origin) }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    internal fun refresh() = synchronized(lock) {
        val groups = launchers().groupBy { it.activityInfo.packageName }
        val database = db
        database.beginTransaction()
        try {
            database.execSQL("UPDATE apps SET available=0")
            groups.forEach { (pkg, entries) ->
                try { upsert(database, pkg, entries) } catch (_: android.content.pm.PackageManager.NameNotFoundException) { /* Uninstalled during scan. */ }
            }
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
        ready = true; scanCount++; changed()
    }
    internal fun refreshPackage(pkg: String) = synchronized(lock) {
        val entries = launchers(pkg)
        val database = db
        database.beginTransaction()
        try {
            if (entries.isEmpty()) database.update("apps", ContentValues().apply { put("available", 0) }, "package=?", arrayOf(pkg))
            else upsert(database, pkg, entries)
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
        changed()
    }
    private fun row(cursor: Cursor): App {
        fun text(name: String) = cursor.getString(cursor.getColumnIndexOrThrow(name))
        fun number(name: String) = cursor.getLong(cursor.getColumnIndexOrThrow(name))
        return App(text("package"), text("label"), text("custom_name").ifBlank { text("russian") }, number("version"), number("installed_at"), number("updated_at"))
    }
    fun get(pkg: String, availableOnly: Boolean = true): App? = db.query("apps", null, "package=?" + if (availableOnly) " AND available=1" else "", arrayOf(pkg), null, null, null).use { if (it.moveToFirst()) row(it) else null }
    fun list(): List<App> { ensureReady(); return db.query("apps", null, "available=1", null, null, null, "label COLLATE NOCASE").use { buildList { while (it.moveToNext()) add(row(it)) } } }
    fun aliases(pkg: String, userOnly: Boolean = false): List<String> = db.query("names", arrayOf("label"), "package=?" + if (userOnly) " AND origin='user'" else "", arrayOf(pkg), null, null, "origin DESC,label").use { buildList { while (it.moveToNext()) add(it.getString(0)) } }.distinct()
    fun saveNames(pkg: String, russian: String, aliases: List<String>) {
        require(russian.isNotBlank() && russian.length <= 120 && AppNames.canonical(russian).isNotBlank()) { "Укажите имя до 120 символов." }
        require(aliases.size <= 20 && aliases.all { it.isNotBlank() && it.length <= 120 && AppNames.canonical(it).isNotBlank() }) { "Не более 20 названий, каждое до 120 символов." }
        require(get(pkg, availableOnly = false) != null) { "Приложение не найдено в каталоге." }
        val database = db; database.beginTransaction()
        try {
            database.update("apps", ContentValues().apply { put("custom_name", russian.trim()) }, "package=?", arrayOf(pkg))
            database.delete("names", "package=? AND origin IN ('user','user_form')", arrayOf(pkg))
            (listOf(russian) + aliases).forEach { name ->
                addName(database, pkg, name, "user")
                AppSpeechNames.variants(name).filter { it != name }.forEach { addName(database, pkg, it, "user_form") }
            }
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
        changed()
    }
    fun resetNames(pkg: String) {
        val database = db; database.beginTransaction()
        try {
            database.update("apps", ContentValues().apply { put("custom_name", "") }, "package=?", arrayOf(pkg))
            database.delete("names", "package=? AND origin IN ('user','user_form')", arrayOf(pkg))
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
        changed()
    }
    private fun query(condition: String, args: Array<String>, limit: Int) = db.rawQuery(
        "SELECT DISTINCT apps.* FROM names INDEXED BY app_name_lookup JOIN apps ON apps.package=names.package WHERE apps.available=1 AND $condition ORDER BY apps.label COLLATE NOCASE LIMIT $limit", args).use { buildList { while (it.moveToNext()) add(row(it)) } }
    fun matching(name: String, limit: Int = 6): List<App> {
        ensureReady()
        val key = AppNames.canonical(name)
        if (key.isBlank()) return emptyList()
        val exact = query("names.key=?", arrayOf(key), limit)
        return exact.ifEmpty { query("names.key>=? AND names.key<?", arrayOf(key, key + '\uffff'), limit) }
    }
    /** Bounded local lookup for clarification, separate from exact launch matching. */
    fun suggestions(name: String, limit: Int = 3): List<App> {
        ensureReady()
        val scores = mutableMapOf<String, Double>()
        db.rawQuery("SELECT names.package,names.label FROM names JOIN apps ON apps.package=names.package WHERE apps.available=1 AND LENGTH(names.label) BETWEEN 6 AND 80 LIMIT 10000", null).use { cursor ->
            while (cursor.moveToNext()) {
                val score = AppSpeechNames.suggestionScore(name, cursor.getString(1))
                val pkg = cursor.getString(0)
                if (score > (scores[pkg] ?: 0.0)) scores[pkg] = score
            }
        }
        return scores.entries.sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .take(limit).mapNotNull { get(it.key) }
    }
    fun relevant(text: String, limit: Int = 8): List<App> {
        ensureReady()
        val keys = AppSpeechNames.keys(text).toList()
        return if (keys.isEmpty()) emptyList() else query("names.key IN (${keys.joinToString(",") { "?" }})", keys.toTypedArray(), limit)
    }
    fun mentioned(name: String, request: String): Boolean = AppNames.mentioned(name, request) ||
        matching(name).map { it.pkg }.intersect(relevant(request).map { it.pkg }.toSet()).isNotEmpty()
    fun sameApp(first: String, second: String): Boolean = AppNames.canonical(first) == AppNames.canonical(second) ||
        matching(first).map { it.pkg }.intersect(matching(second).map { it.pkg }.toSet()).isNotEmpty()
    fun completeMention(name: String, request: String): String {
        val requested = relevant(request).map { it.pkg }.toSet()
        return matching(name).filter { it.pkg in requested }.singleOrNull()?.label ?: name
    }
}
