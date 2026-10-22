package local.voice

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import java.util.concurrent.Executors

/** Owner-enabled dev OTA, unmetered network only. No audio, microphone or activity launches. */
internal object AutoUpdates {
    internal const val PERIODIC_JOB = 7313
    internal const val CHECK_JOB = 7314
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var request: BackendClient.Request? = null
    private fun prefs(context: Context) = context.getSharedPreferences("dev_ota", 0)
    fun status(context: Context) = prefs(context).getString("status", "Автообновления выключены.").orEmpty()
    fun cancel(context: Context) {
        request?.cancel()
        context.getSystemService(JobScheduler::class.java).apply { cancel(PERIODIC_JOB); cancel(CHECK_JOB) }
    }
    fun schedule(context: Context, immediate: Boolean = false) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (!OtaClient.enabled(context)) { cancel(context); return }
        val component = ComponentName(context, AutoUpdateJob::class.java)
        if (scheduler.getPendingJob(PERIODIC_JOB) == null) {
            check(scheduler.schedule(JobInfo.Builder(PERIODIC_JOB, component).setPersisted(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED).setPeriodic(15 * 60_000L).build()) == JobScheduler.RESULT_SUCCESS)
        }
        if (immediate) scheduler.schedule(JobInfo.Builder(CHECK_JOB, component)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED).setMinimumLatency(5_000)
            .setBackoffCriteria(60_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build())
    }
    internal fun idle(context: Context): Boolean {
        val audio = context.getSystemService(AudioManager::class.java)
        return !AssistantApplication.foreground && VoiceConversation.activePhase == null && !SpeechDatasetRecorder.recording && !AlarmRingService.ringing && CallGuard.inCall(context) != true &&
            audio.mode !in setOf(AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION) &&
            audio.activeRecordingConfigurations.isEmpty()
    }
    /** A cancellation is scoped to this job, so a stopped job cannot cancel its successor. */
    fun check(context: Context, operation: BackendClient.Request, done: (Boolean) -> Unit) {
        val app = context.applicationContext
        worker.execute {
            request = operation
            val result = runCatching { cycle(app, operation) }
            if (result.isFailure && OtaClient.enabled(app)) prefs(app).edit()
                .putString("status", "Dev OTA недоступен или обновление отклонено. Повторю позже.").apply()
            if (request === operation) request = null
            done(result.getOrDefault(true))
        }
    }
    internal fun cycle(context: Context, operation: BackendClient.Request): Boolean {
        operation.ensureActive()
        if (!OtaClient.enabled(context)) return false
        val source = OtaClient.source(context)
        Updates.reconcile(context)
        if (Updates.state(context) in setOf("installing", "confirmation")) return false
        if (!idle(context)) { prefs(context).edit().putString("status", "Обновление отложено до завершения работы ассистента или звонка.").apply(); return true }
        if (!context.packageManager.canRequestPackageInstalls()) {
            prefs(context).edit().putString("status", "Разрешите установку обновлений в настройках доступов.").apply(); return false
        }
        val apk = Updates.ready(context) ?: Updates.download(context, operation)
        operation.ensureActive()
        if (!OtaClient.enabled(context) || source != OtaClient.source(context)) return false
        prefs(context).edit().putLong("checked_at", System.currentTimeMillis()).apply()
        if (apk == null) { prefs(context).edit().putString("status", "Установлена последняя dev-версия.").apply(); return false }
        if (!idle(context)) { prefs(context).edit().putString("status", "Обновление скачано. Установлю, когда ассистент освободится.").apply(); return true }
        operation.ensureActive()
        Updates.install(context, apk, requireIdle = true, ensureActive = operation::ensureActive)
        prefs(context).edit().putString("status", "Обновление передано системному установщику.").apply()
        return false
    }
}

class AutoUpdateJob : JobService() {
    private var operation: BackendClient.Request? = null
    override fun onStartJob(params: JobParameters): Boolean {
        val work = BackendClient.Request(); operation = work
        AutoUpdates.check(this, work) { retry ->
            android.os.Handler(mainLooper).post { if (operation === work) { operation = null; jobFinished(params, retry) } }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { operation?.cancel(); operation = null; return OtaClient.enabled(this) }
    override fun onDestroy() { operation?.cancel(); operation = null; super.onDestroy() }
}

class AutoUpdateBoot : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) runCatching { AutoUpdates.schedule(context) }
    }
}
