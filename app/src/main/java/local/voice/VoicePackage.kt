package local.voice

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Portable voice package: manifest.json, model.onnx, tokens.txt; bundled phonemizer reused. */
object VoicePackage {
    fun custom(context: Context) = File(context.filesDir, "custom-voice")
    fun import(context: Context, source: InputStream) {
        val pending = File(context.filesDir, "voice.import").apply { deleteRecursively(); mkdirs() }
        try {
            val allowed = setOf("manifest.json", "model.onnx", "tokens.txt")
            val seen = mutableSetOf<String>()
            var total = 0L
            ZipInputStream(source).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(!entry.isDirectory && entry.name in allowed && seen.add(entry.name)) { "В пакете должны быть только manifest.json, model.onnx и tokens.txt." }
                    File(pending, entry.name).outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= 256L * 1024 * 1024) { "Голосовой пакет превышает 256 МБ." }
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
            require(seen == allowed) { "Пакет голоса неполный." }
            val manifest = File(pending, "manifest.json")
            require(manifest.length() < 16_384)
            val metadata = JSONObject(manifest.readText())
            require(metadata.getString("engine") == "piper-vits" && metadata.getString("language") == "ru") { "Нужен русский Piper/VITS-голос." }
            require(File(pending, "model.onnx").length() > 1_000_000 && File(pending, "tokens.txt").length() in 100..65_536)
            val destination = custom(context)
            val backup = File(context.filesDir, "voice.previous").apply { deleteRecursively() }
            if (destination.exists()) check(destination.renameTo(backup))
            if (!pending.renameTo(destination)) { backup.renameTo(destination); error("Не удалось сохранить голос.") }
            backup.deleteRecursively()
            ModelCatalog.selectVoice(context, "custom")
        } finally { pending.deleteRecursively() }
    }
}
