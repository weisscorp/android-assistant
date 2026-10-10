package local.voice

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors

/** SAF import is copied and tested before the existing active model is changed. */
object ModelImporter {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    fun start(context: Context, uri: Uri, progress: (String) -> Unit, done: (Result<ModelCatalog.Brain>) -> Unit) {
        if (ModelDownload.busy || ModelDownload.importBusy || LocalBrain.maintenanceBusy) {
            done(Result.failure(IllegalStateException("Дождитесь загрузки, импорта или теста модели."))); return
        }
        ModelDownload.importBusy = true; VoiceConversation.stopActive(); LocalBrain.cancel()
        val app = context.applicationContext
        val temporary = File(app.filesDir, "${UUID.randomUUID()}.import")
        progress("Копирую GGUF в приватное хранилище…")
        worker.execute {
            val prepared = runCatching {
                var name = "Своя GGUF-модель"
                app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val label = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (label >= 0) name = cursor.getString(label).orEmpty().removeSuffix(".gguf").take(120)
                        val size = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (size >= 0 && !cursor.isNull(size)) require(cursor.getLong(size) < app.filesDir.usableSpace - 32 * 1024 * 1024) { "Недостаточно места для импорта." }
                    }
                }
                val digest = MessageDigest.getInstance("SHA-256")
                app.contentResolver.openInputStream(uri)?.use { input -> temporary.outputStream().use { output ->
                    val magic = input.readNBytes(4)
                    require(magic.contentEquals(byteArrayOf(71, 71, 85, 70))) { "Это не GGUF-файл." }
                    output.write(magic); digest.update(magic)
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val read = input.read(buffer); if (read < 0) break
                        require(app.filesDir.usableSpace > read + 32 * 1024 * 1024L) { "Недостаточно места для импорта." }
                        output.write(buffer, 0, read); digest.update(buffer, 0, read)
                    }
                    output.fd.sync()
                } } ?: error("Не удалось открыть файл.")
                Triple(name, digest.digest().joinToString("") { "%02x".format(it) }, LocalBrain.inspect(temporary))
            }
            main.post {
                prepared.fold({ (name, digest, info) ->
                    progress("Проверяю архитектуру, шаблон и генерацию…")
                    LocalBrain.validate(temporary, info.contextSize) { validation ->
                        val installed = validation.mapCatching {
                            val known = BuiltinModels.available(app).singleOrNull { it.sha256 == digest && it.size == temporary.length() }
                            val metadata = known ?: ModelMetadata.imported(it, temporary, name, digest)
                            ModelCatalog.installBrain(app, temporary, known?.name ?: name, metadata)
                        }
                        temporary.delete(); ModelDownload.importBusy = false; done(installed)
                    }
                }, { error -> temporary.delete(); ModelDownload.importBusy = false; done(Result.failure(error)) })
            }
        }
    }
}
