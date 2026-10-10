package local.voice

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.system.Os
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Exact sizes and digests are checked before an artifact can enter the catalog. */
object ModelArtifact {
    const val bytes = 639446688L
    const val sha256 = "9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031"
    const val url = "https://huggingface.co/Qwen/Qwen3-0.6B-GGUF/resolve/23749fefcc72300e3a2ad315e1317431b06b590a/Qwen3-0.6B-Q8_0.gguf"
    fun copyVerified(input: InputStream, output: OutputStream, expectedBytes: Long = bytes,
                     expectedHash: String = sha256, active: () -> Boolean, progress: (Long) -> Unit) {
        val digest = MessageDigest.getInstance("SHA-256")
        val magic = input.readNBytes(4)
        require(magic.contentEquals(byteArrayOf(71, 71, 85, 70))) { "Сервер вернул не GGUF-файл." }
        output.write(magic); digest.update(magic)
        var total = 4L
        val buffer = ByteArray(256 * 1024)
        while (true) {
            check(active()) { "Загрузка отменена." }
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= expectedBytes) { "Размер модели не совпадает." }
            output.write(buffer, 0, count); digest.update(buffer, 0, count); progress(total)
        }
        check(active()) { "Загрузка отменена." }
        require(total == expectedBytes) { "Модель скачана не полностью. Попробуйте ещё раз." }
        require(digest.digest().joinToString("") { "%02x".format(it) } == expectedHash) { "Проверка модели не пройдена. Попробуйте ещё раз." }
    }
}

object ModelDownload {
    @Volatile var modelId = ""
        internal set
    @Volatile var importBusy = false
    @Volatile var busy = false
        internal set
    @Volatile var message = ""
        internal set
    fun start(context: Context, id: String = "lfm25-12b-qad-q4") {
        if (busy || importBusy || LocalBrain.maintenanceBusy) return
        val model = BuiltinModels.find(context, id) ?: run { message = "Модель не совместима с текущим Android runtime."; return }
        if (ModelCatalog.installed(context, model) != null) { message = "Модель уже скачана."; return }
        modelId = id; busy = true; message = "Начинаю загрузку ${model.name}…"
        runCatching { context.startForegroundService(Intent(context, ModelDownloadService::class.java).putExtra("model_id", id)) }
            .onFailure { busy = false; message = "Не удалось начать загрузку: ${it.message}" }
    }
    fun cancel(context: Context) { context.startService(Intent(context, ModelDownloadService::class.java).setAction("cancel")) }
}

class ModelDownloadService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var cancelled = false
    @Volatile private var connection: javax.net.ssl.HttpsURLConnection? = null
    private var started = false
    private var lastProgress = 0L
    private var wake: android.os.PowerManager.WakeLock? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "cancel") {
            cancelled = true; connection?.disconnect()
            if (!started) { ModelDownload.busy = false; stopSelf() }
            return START_NOT_STICKY
        }
        if (started) return START_NOT_STICKY
        val model = BuiltinModels.find(this, intent?.getStringExtra("model_id").orEmpty())
        if (model == null) { ModelDownload.busy = false; stopSelf(); return START_NOT_STICKY }
        ModelDownload.modelId = model.id; ModelDownload.busy = true
        started = true
        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("model_download", "Загрузка модели", NotificationManager.IMPORTANCE_LOW))
            startForeground(101, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            wake = getSystemService(android.os.PowerManager::class.java).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "local.voice:model-download").apply { acquire(30 * 60_000L) }
        } catch (e: Exception) {
            ModelDownload.message = "Не удалось начать загрузку: ${e.message}"; ModelDownload.busy = false; stopSelf(); return START_NOT_STICKY
        }
        val context = applicationContext
        worker.execute {
            val temporary = File(context.filesDir, "model.download")
            try {
                require(context.filesDir.usableSpace > model.size + 32 * 1024 * 1024L) { "Недостаточно свободного места для модели и временного файла." }
                val deadline = android.os.SystemClock.elapsedRealtime() + 30 * 60_000L
                var address = URL(model.url)
                var connected: javax.net.ssl.HttpsURLConnection? = null
                for (redirect in 0..5) {
                    check(!cancelled) { "Загрузка отменена." }
                    require(address.protocol == "https") { "Загрузка разрешена только по HTTPS." }
                    val current = address.openConnection() as javax.net.ssl.HttpsURLConnection
                    connection = current
                    current.connectTimeout = 20_000; current.readTimeout = 30_000; current.instanceFollowRedirects = false
                    current.setRequestProperty("Accept-Encoding", "identity")
                    val code = current.responseCode
                    if (code in listOf(301, 302, 303, 307, 308)) {
                        val location = current.getHeaderField("Location") ?: error("Сервер не указал адрес модели.")
                        address = URL(address, location); current.disconnect()
                    } else {
                        check(code == 200) { "Сервер загрузки вернул HTTP $code." }
                        val length = current.contentLengthLong
                        require(length == -1L || length == model.size) { "Размер модели не совпадает." }
                        connected = current; break
                    }
                }
                val download = connected ?: error("Слишком много перенаправлений.")
                download.inputStream.use { input -> temporary.outputStream().use { output ->
                    ModelArtifact.copyVerified(input, output, model.size, model.sha256, active = { !cancelled && android.os.SystemClock.elapsedRealtime() < deadline }) { total ->
                        if (android.os.SystemClock.elapsedRealtime() - lastProgress >= 500) {
                            lastProgress = android.os.SystemClock.elapsedRealtime()
                            val percent = (total * 100 / model.size).toInt()
                            ModelDownload.message = "${model.name}: $percent% · ${total / 1024 / 1024} / ${model.size / 1024 / 1024} МБ"
                            main.post { if (!cancelled && ModelDownload.busy) getSystemService(NotificationManager::class.java).notify(101, notification(percent)) }
                        }
                    }
                    output.fd.sync()
                } }
                check(!cancelled) { "Загрузка отменена." }
                ModelDownload.message = "Проверяю GGUF и шаблон диалога…"
                val info = LocalBrain.inspect(temporary)
                require(info.architecture == model.architecture && info.chatTemplate == model.chatTemplate && info.bindingRevision == model.validatedBinding) { "Метаданные GGUF не совпадают с проверенным каталогом." }
                check(!cancelled) { "Загрузка отменена." }
                ModelCatalog.installBuiltin(context, temporary, model)
                ModelDownload.message = "${model.name} скачана. Выберите её в каталоге."
            } catch (e: Exception) {
                ModelDownload.message = if (cancelled) "Загрузка отменена. Прежняя модель сохранена." else "Не удалось скачать модель: ${e.message ?: "проверьте интернет"}"
            } finally {
                connection?.disconnect(); connection = null; temporary.delete()
                main.post { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            }
        }
        return START_NOT_STICKY
    }
    private fun notification(percent: Int = 0): Notification {
        val open = PendingIntent.getActivity(this, 101, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val cancel = PendingIntent.getService(this, 101, Intent(this, ModelDownloadService::class.java).setAction("cancel"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, "model_download").setSmallIcon(R.drawable.ic_assistant)
            .setContentTitle("Ассистент · загрузка модели").setContentText(ModelDownload.message).setContentIntent(open)
            .setProgress(100, percent, percent == 0).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Отмена", cancel).build()).build()
    }
    override fun onDestroy() {
        cancelled = true; connection?.disconnect(); worker.shutdown()
        wake?.let { if (it.isHeld) it.release() }; wake = null
        ModelDownload.busy = false; ModelDownload.modelId = ""
        super.onDestroy()
    }
}
