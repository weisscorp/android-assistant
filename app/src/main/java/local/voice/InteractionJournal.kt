package local.voice

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.lang.reflect.Modifier
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Content journal explicitly requested by the user. Separate from metadata-only SessionDiagnostics. */
internal object InteractionJournal {
    const val FILE_NAME = "interaction-journal.jsonl"
    const val LIMIT = 50_000_000L
    private val writer = Executors.newSingleThreadExecutor { task -> Thread(task, "interaction-journal") }
    private val sequence = AtomicLong()
    private val processId = UUID.randomUUID().toString()
    @Volatile private var appVersion: String? = null
    @Volatile private var storage: BoundedJournalFile? = null
    @Volatile private var failure: String? = null
    @Volatile var current: Scope? = null
        private set

    class Scope internal constructor(val session: String, val turn: String?, private val context: Context) {
        fun event(type: String, data: JSONObject = JSONObject(), call: String? = null) = record(context, session, turn, type, data, call)
        fun begin(type: String, data: JSONObject = JSONObject()): Call = Call(this, type, UUID.randomUUID().toString()).also {
            event("${type}_request", data, it.id)
        }
        fun nextTurn(): Scope = Scope(session, UUID.randomUUID().toString(), context).also { current = it }
    }
    class Call internal constructor(private val scope: Scope, private val type: String, val id: String) {
        fun event(stage: String, data: JSONObject = JSONObject()) = scope.event("${type}_$stage", data, id)
        fun result(data: JSONObject) = event("result", data)
        fun error(error: Throwable) = event("error", data("error" to error.javaClass.simpleName, "message" to error.message))
    }

    fun start(context: Context, textOnly: Boolean, headset: Boolean): Scope {
        val app = context.applicationContext
        return Scope(UUID.randomUUID().toString(), null, app).also {
            current = it
            it.event("session_start", data("input" to if (textOnly) "text" else "speech", "headset_required" to headset))
        }
    }
    fun end(scope: Scope, reason: String) {
        scope.event("session_end", data("reason" to reason))
        if (current?.session == scope.session) current = null
    }
    fun isEnabled(context: Context) = context.getSharedPreferences("interaction_journal", 0).getBoolean("enabled", true)
    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences("interaction_journal", 0).edit().putBoolean("enabled", value).apply()
    }
    fun description(context: Context): String {
        val length = storage?.file?.length() ?: File(context.filesDir, FILE_NAME).length()
        return "${if (isEnabled(context)) "Запись включена" else "Запись выключена"} · ${String.format(java.util.Locale.ROOT, "%.2f", length / 1_000_000.0)} из 50 МБ" +
            (failure?.let { "\nОшибка записи: $it" } ?: "")
    }
    /** Export and clear are queued after preceding events; neither runs on the audio/UI thread. */
    fun export(context: Context, output: OutputStream): Long = writer.submit<Long> { store(context).copyTo(output) }.get(60, TimeUnit.SECONDS)
    fun clear(context: Context) = writer.submit { store(context).clear(); failure = null }.get(60, TimeUnit.SECONDS)
    internal fun flush() { writer.submit {}.get(60, TimeUnit.SECONDS) }

    fun data(vararg values: Pair<String, Any?>) = JSONObject().apply {
        values.forEach { (name, value) -> put(name, when (value) {
            null -> JSONObject.NULL
            is Map<*, *> -> JSONObject(value)
            is Collection<*> -> org.json.JSONArray(value)
            is Enum<*> -> value.name
            else -> value
        }) }
    }
    fun command(command: VoiceCommand, expectedPackage: String? = null): JSONObject {
        // Only typed commands are inspected. No Context, Intent, credential store or callbacks.
        val args = JSONObject()
        command.javaClass.declaredFields.filter { !it.isSynthetic && !Modifier.isStatic(it.modifiers) }.forEach { field ->
            field.isAccessible = true
            val value = field.get(command)
            args.put(field.name, when (value) { null -> JSONObject.NULL; is String, is Number, is Boolean -> value; else -> value.toString() })
        }
        return data("name" to command.javaClass.simpleName, "arguments" to args, "expected_package" to expectedPackage)
    }
    fun reply(reply: DeviceTools.Reply?): JSONObject = if (reply == null) data("available" to false) else data(
        "text" to reply.text, "action_pending" to reply.actionPending, "action_accepted" to reply.actionAccepted,
        "app_selection" to reply.appSelection, "opened_package" to reply.openedPackage,
        "deferred_action" to (reply.afterSpeech != null || reply.beforeOpening != null),
        "verification_pending" to (reply.verify != null), "handoff_pending" to (reply.handoff || reply.mediaHandoff != null),
        "observation" to reply.observation?.let { data("tool" to it.tool, "operation" to it.operation, "status" to it.status, "data" to it.fields) })

    inline fun <T> action(name: String, fields: JSONObject = JSONObject(), block: () -> T): T {
        val call = current?.begin("action", data("name" to name, "arguments" to fields))
        return try { block().also { call?.result(data("status" to "returned", "value" to if (it == Unit) null else it)) } }
        catch (error: Exception) { call?.error(error); throw error }
    }
    private fun store(context: Context): BoundedJournalFile {
        storage?.let { return it }
        return BoundedJournalFile(File(context.filesDir, FILE_NAME), LIMIT).also { storage = it }
    }
    @Synchronized private fun record(context: Context, session: String, turn: String?, type: String, fields: JSONObject, call: String?) {
        // Snapshot mutable JSON before callbacks can change it. Disk work is always off the caller thread.
        if (!isEnabled(context)) return
        runCatching {
            val event = data("schema" to 1, "event_id" to UUID.randomUUID().toString(), "sequence" to sequence.incrementAndGet(),
                "time" to Instant.now().toString(), "process_id" to processId, "session_id" to session, "turn_id" to turn, "call_id" to call,
                "type" to type, "data" to fields, "version" to (appVersion ?: context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty().also { appVersion = it })).toString()
            writer.execute {
                try {
                    val journal = store(context)
                    val size = event.toByteArray(Charsets.UTF_8).size.toLong() + 1
                    val line = if (size <= LIMIT) event else data("schema" to 1, "type" to "record_exceeds_capacity",
                        "session_id" to session, "turn_id" to turn, "original_type" to type, "original_bytes" to size,
                        "time" to Instant.now().toString()).toString()
                    journal.append(line); failure = null
                } catch (error: Exception) { failure = error.message ?: error.javaClass.simpleName }
            }
        }.onFailure { failure = it.message ?: it.javaClass.simpleName }
    }
}
