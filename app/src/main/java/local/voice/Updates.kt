package local.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors

/** Only update our package, with the current signing certificate. The platform verifies it again. */
object Updates {
    private const val CONFIRMATION_CHANNEL = "updates_quiet_v1"
    private val installations = Executors.newSingleThreadExecutor()
    fun enqueueInstall(context: Context, file: File) {
        val app = context.applicationContext
        installations.execute {
            runCatching { install(app, file) }.onFailure { error ->
                preferences(app).edit().putString("message", error.message ?: "Не удалось установить обновление.").commit()
            }
        }
    }
    private fun preferences(context: Context) = context.getSharedPreferences("updates", 0)
    private fun sha256(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(65536)
            while (true) { val count = stream.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }
    private fun current(context: Context) = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    private fun verifiedCandidate(context: Context, file: File, version: Long) {
        val installed = current(context)
        check(version > installed.longVersionCode) { "Эта версия уже установлена." }
        val candidate = context.packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES) ?: error("Некорректный APK.")
        check(candidate.packageName == context.packageName && candidate.longVersionCode == version) { "Неверное приложение или версия APK." }
        val old = installed.signingInfo?.apkContentsSigners?.map { it.toByteArray().toList() }?.toSet().orEmpty()
        val new = candidate.signingInfo?.apkContentsSigners?.map { it.toByteArray().toList() }?.toSet().orEmpty()
        check(old.isNotEmpty() && old == new) { "APK подписан другим ключом." }
    }
    private fun cached(context: Context): File? {
        val name = preferences(context).getString("file", null) ?: return null
        if (!name.matches(Regex("ota-[a-f0-9-]{36}\\.apk"))) return null
        return File(context.cacheDir, name)
    }
    fun message(context: Context): String {
        reconcile(context)
        return preferences(context).getString("message", "Обновления ещё не проверялись.")!!
    }
    fun reconcile(context: Context) {
        val prefs = preferences(context)
        val state = prefs.getString("state", "")
        val target = prefs.getLong("version", 0)
        if (state in setOf("installing", "confirmation", "ready", "failed") && target > 0 && current(context).longVersionCode >= target) {
            cached(context)?.delete()
            prefs.edit().putString("state", "installed").putString("message", "Обновление установлено. Версия $target.").remove("file").commit()
            context.getSystemService(NotificationManager::class.java).cancel(102)
        } else if (state in setOf("installing", "confirmation") && context.packageManager.packageInstaller.getSessionInfo(prefs.getInt("session", -1)) == null) {
            prefs.edit().putString("state", "failed").putString("message", "Установка не завершена. Можно повторно проверить обновление.").commit()
        }
    }
    @Synchronized fun download(context: Context, request: BackendClient.Request = BackendClient.Request()): File? {
        reconcile(context)
        val prefs = preferences(context)
        check(prefs.getString("state", "") !in setOf("installing", "confirmation")) { "Предыдущее обновление ещё устанавливается." }
        val connection = BackendClient.open(context, "/updates/manifest")
        val manifest = try {
            request.attach(connection)
            check(connection.responseCode == 200) { "Сервер не предоставил обновление." }
            val bytes = connection.inputStream.use { it.readNBytes(16_385) }
            check(bytes.size <= 16_384) { "Некорректный manifest обновления." }
            JSONObject(bytes.toString(Charsets.UTF_8))
        } finally { request.detach(); connection.disconnect() }
        val rawVersion = manifest.get("versionCode")
        require(rawVersion is Int || rawVersion is Long) { "Некорректная версия обновления." }
        val version = (rawVersion as Number).toLong()
        require(version in 1..2_100_000_000L)
        if (version <= current(context).longVersionCode) return null
        val name = manifest.get("file")
        require(name is String && name.matches(Regex("[a-zA-Z0-9._-]+\\.apk")))
        val expected = manifest.get("sha256")
        require(expected is String && expected.matches(Regex("[a-f0-9]{64}")))
        val artifact = File(context.cacheDir, "ota-${UUID.randomUUID()}.apk")
        val download = BackendClient.open(context, "/updates/$name")
        try {
            request.attach(download)
            check(download.responseCode == 200) { "Не удалось скачать APK." }
            download.inputStream.use { input -> artifact.outputStream().use { output ->
                UpdateDownload.copy(input, output, request::ensureActive)
                output.fd.sync()
            } }
            check(sha256(artifact) == expected) { "Контрольная сумма APK не совпала." }
            verifiedCandidate(context, artifact, version)
            request.ensureActive()
            val old = cached(context)
            check(prefs.edit().putString("file", artifact.name).putString("hash", expected).putLong("version", version)
                .putString("state", "ready").putString("message", "Версия $version скачана и проверена.").commit())
            old?.delete()
            return artifact
        } catch (error: Exception) { artifact.delete(); throw error }
        finally { request.detach(); download.disconnect() }
    }
    @Synchronized fun install(context: Context, file: File) {
        val prefs = preferences(context)
        check(prefs.getString("state", "") == "ready" && file.canonicalFile == cached(context)?.canonicalFile) { "Сначала скачайте и проверьте обновление." }
        check(context.packageManager.canRequestPackageInstalls()) { "Разрешите установку обновлений для этого приложения." }
        val version = prefs.getLong("version", 0)
        check(sha256(file) == prefs.getString("hash", "")) { "Скачанный APK изменился." }
        verifiedCandidate(context, file, version)
        val installer = context.packageManager.packageInstaller
        val parameters = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(parameters)
        val token = UUID.randomUUID().toString()
        try {
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, file.length()).use { output -> file.inputStream().use { it.copyTo(output) }; session.fsync(output) }
                check(prefs.edit().putInt("session", id).putString("callback", token).putString("state", "installing")
                    .putString("message", "APK проверен. Ожидаю результат системного установщика.").commit())
                val callback = PendingIntent.getBroadcast(context, id,
                    Intent(context, InstallResult::class.java).putExtra("callback", token), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                session.commit(callback.intentSender)
            }
        } catch (error: Exception) {
            runCatching { installer.abandonSession(id) }
            prefs.edit().putString("state", "failed").putString("message", "Не удалось передать обновление установщику.").commit()
            throw error
        }
    }
    internal fun result(context: Context, intent: Intent) {
        val prefs = preferences(context)
        if (intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -2) != prefs.getInt("session", -1) ||
            intent.getStringExtra("callback") != prefs.getString("callback", null) || prefs.getString("state", "") !in setOf("installing", "confirmation")) return
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                prefs.edit().putString("state", "confirmation").putString("message", "Android требует подтвердить обновление. Откройте уведомление установщика.").commit()
                val confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) ?: return
                val notifications = context.getSystemService(NotificationManager::class.java)
                notifications.createNotificationChannel(NotificationChannel(CONFIRMATION_CHANNEL, "Обновления ассистента", NotificationManager.IMPORTANCE_LOW).apply {
                    setSound(null, null)
                    enableVibration(false)
                })
                val action = PendingIntent.getActivity(context, 102, confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                runCatching { notifications.notify(102, Notification.Builder(context, CONFIRMATION_CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("Обновление ассистента").setContentText("Android требует подтверждения установки").setContentIntent(action).setAutoCancel(true)
                    // A child-only quiet group suppresses sound/vibration even if
                    // this channel's settings are subsequently made audible.
                    .setGroup("local.voice.updates.quiet").setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
                    .setOnlyAlertOnce(true).build()) }
            }
            PackageInstaller.STATUS_SUCCESS -> reconcile(context)
            else -> {
                prefs.edit().putString("state", "failed").putString("message", "Установка отменена или завершилась ошибкой.").commit()
                context.getSystemService(NotificationManager::class.java).cancel(102)
            }
        }
    }
}
class InstallResult : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { Updates.result(context, intent) }
}
class UpdateReplaced : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) Updates.reconcile(context)
    }
}
