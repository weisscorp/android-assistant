package local.voice

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Direct authenticated HTTPS, no Google dependency. Doze can delay network delivery. */
object HomeLink {
    private const val JOB = 7312
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private var future: ScheduledFuture<*>? = null
    @Volatile private var active = false
    @Volatile private var request: BackendClient.Request? = null
    private var failures = 0
    private fun prefs(context: Context) = context.getSharedPreferences("home_link", 0)
    fun enabled(context: Context) = BackendClient.endpoint(context).isNotBlank() && context.getSharedPreferences("settings", 0).getBoolean("home_link_enabled", true)
    @Synchronized fun device(context: Context): String {
        prefs(context).getString("device", null)?.let { return it }
        return UUID.randomUUID().toString().also { check(prefs(context).edit().putString("device", it).commit()) }
    }
    @Synchronized fun changedServer(context: Context) {
        request?.cancel()
        prefs(context).getString("device", null)?.let {
            java.io.File(context.filesDir, "home-knowledge-$it.json").delete()
            HomeEvents(context).retire(it)
        }
        prefs(context).edit().clear().putString("device", UUID.randomUUID().toString()).commit()
        kick(context)
    }
    fun attach(context: Context) { active = true; kick(context) }
    fun detach() { active = false; request?.cancel() }
    @Synchronized fun kick(context: Context) {
        val app = context.applicationContext
        val scheduler = app.getSystemService(JobScheduler::class.java)
        if (!enabled(app)) { request?.cancel(); future?.cancel(false); scheduler.cancel(JOB); return }
        runCatching { scheduler.schedule(JobInfo.Builder(JOB, ComponentName(app, HomeLinkJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setPeriodic(15 * 60_000L).build()) }
            .onFailure { prefs(app).edit().putString("status", "Фоновые задания недоступны. Синхронизация продолжится при запуске помощника.").apply() }
        request?.cancel()
        future?.cancel(false)
        future = worker.schedule({ cycle(app) }, 0, TimeUnit.MILLISECONDS)
    }
    @Synchronized private fun later(context: Context, millis: Long) {
        if (future?.isDone == false) return // A mutation already queued an immediate sync.
        future = worker.schedule({ cycle(context) }, millis, TimeUnit.MILLISECONDS)
    }
    private fun cycle(context: Context) {
        synchronized(this) { future = null }
        if (!enabled(context)) return
        val result = runCatching { exchange(context, if (active) 25 else 0) }
        if (result.isSuccess) failures = 0 else {
            failures = minOf(8, failures + 1)
            prefs(context).edit().putString("status", "Связь недоступна. Знания и сообщения сохраняются; повторю позже.").apply()
        }
        if (enabled(context) && (active || result.isFailure)) later(context,
            if (result.isSuccess) 1000 else minOf(15 * 60_000L, 5000L shl failures))
    }
    internal fun exchange(context: Context, wait: Int = 0) {
        check(enabled(context))
        val device = device(context)
        val operation = BackendClient.Request(); request = operation
        try {
            val (snapshot, images) = HomeKnowledge.prepare(context, device)
            val revision = snapshot.getLong("revision")
            if (prefs(context).getLong("synced_revision", 0) != revision) {
                val uploaded = prefs(context).getStringSet("uploaded_images", emptySet()).orEmpty().toMutableSet()
                images.filterKeys { it !in uploaded }.forEach { (sha, file) ->
                    val (code, _) = BackendClient.link(context, "POST", "/link/$device/image/$sha", file.readBytes(), operation)
                    check(code == 200) { "Image sync failed" }
                    uploaded.add(sha)
                    synchronized(this) { operation.ensureActive(); check(device == device(context)); prefs(context).edit().putStringSet("uploaded_images", uploaded.toSet()).commit() }
                }
                val (code, json) = BackendClient.link(context, "POST", "/link/$device/knowledge", snapshot.toString().toByteArray(), operation)
                if (code == 409) prefs(context).edit().putLong("revision_floor", json.getLong("revision") + 1).commit()
                check(code == 200 && json.getLong("revision") == revision) { "Knowledge sync failed" }
                synchronized(this) {
                    operation.ensureActive(); check(device == device(context))
                    prefs(context).edit().putLong("synced_revision", revision).putStringSet("uploaded_images", images.keys.toSet()).putLong("synced_at", System.currentTimeMillis()).commit()
                }
            }
            operation.ensureActive()
            val unlocked = !context.getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked
            val activity = unlocked && (AssistantApplication.foreground || VoiceConversation.activePhase?.let { it != SessionDiagnostics.Phase.STOPPED } == true)
            val presence = JSONObject().put("active", activity).toString().toByteArray()
            val (presenceCode, _) = BackendClient.link(context, "POST", "/link/$device/presence", presence, operation)
            check(presenceCode in setOf(200, 404)) { "Presence failed" }
            HomeEvents(context).flush(device, operation)
            val inbox = HomeInbox(context)
            val (code, json) = BackendClient.link(context, "GET", "/link/$device/events?after=${inbox.cursor(device)}&wait=$wait", null, operation)
            if (code == 404) prefs(context).edit().remove("synced_revision").remove("uploaded_images").commit()
            check(code == 200) { "Events failed" }
            if (json.optLong("revision") != revision) {
                prefs(context).edit().remove("synced_revision").commit()
                error("Server snapshot changed")
            }
            operation.ensureActive(); check(device == device(context))
            inbox.accept(device, json.getJSONArray("events"))
            inbox.notifyPending(device)
            HomeEvents(context).flush(device, operation)
            val (ack, _) = BackendClient.link(context, "POST", "/link/$device/ack", JSONObject().put("through", inbox.cursor(device)).toString().toByteArray(), operation)
            check(ack == 200) { "Ack failed" }
            synchronized(this) { operation.ensureActive(); check(device == device(context)); prefs(context).edit().putString("status", "Знания синхронизированы. Сообщения получены.").putLong("checked_at", System.currentTimeMillis()).apply() }
        } finally { if (request === operation) request = null }
    }
    internal fun checkNow(context: Context): java.util.concurrent.Future<Unit> = worker.submit<Unit> { exchange(context.applicationContext, 0) }
    internal fun poll(context: Context, done: () -> Unit) {
        worker.execute { try { if (enabled(context)) cycle(context.applicationContext) } finally { done() } }
    }
    internal fun cancelRequest() { request?.cancel() }
    fun status(context: Context) = if (!enabled(context)) "Подключите Hermes и включите синхронизацию."
        else prefs(context).getString("status", "Ожидаю первого соединения с Hermes.").orEmpty() +
            "\nУстройство: ${device(context)}\nНепрочитанных: ${HomeInbox(context).unreadCount()}\nИсходящих событий: ${HomeEvents(context).pendingCount()}"
}

class HomeLinkJob : JobService() {
    @Volatile private var stopped = false
    override fun onStartJob(params: JobParameters): Boolean {
        stopped = false
        HomeLink.poll(this) { if (!stopped) jobFinished(params, false) }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { stopped = true; HomeLink.cancelRequest(); return true }
}
