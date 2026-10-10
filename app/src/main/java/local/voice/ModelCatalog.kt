package local.voice

import android.content.Context
import android.system.Os
import java.io.File
import java.util.UUID

/** Installed models stay in private storage; switching never copies a large file. */
object ModelCatalog {
    data class Brain(val id: String, val name: String, val file: File, val metadata: ModelMetadata? = null)
    private fun directory(context: Context) = File(context.filesDir, "brains").apply { mkdirs() }
    fun brains(context: Context): List<Brain> {
        val prefs = context.getSharedPreferences("settings", 0)
        val legacy = File(context.filesDir, "model.gguf")
        if (legacy.isFile && !prefs.contains("legacy_brain_name"))
            prefs.edit().putString("legacy_brain_name", prefs.getString("model_name", "Qwen / GGUF")).commit()
        val models = directory(context).listFiles().orEmpty().filter { it.extension == "gguf" }
            .sortedBy { it.name }.map { Brain(it.name, prefs.getString("brain_name_${it.name}", it.nameWithoutExtension)!!, it, metadata(context, it.name)) }
        return (if (legacy.isFile) listOf(Brain("legacy", prefs.getString("legacy_brain_name", "Qwen / GGUF")!!, legacy, metadata(context, "legacy"))) else emptyList()) + models
    }
    fun selectedBrain(context: Context): Brain? {
        val models = brains(context)
        val id = context.getSharedPreferences("settings", 0).getString("brain_id", "legacy")
        return models.firstOrNull { it.id == id } ?: models.firstOrNull()
    }
    fun selectBrain(context: Context, brain: Brain) {
        require(brains(context).any { it.id == brain.id && it.file == brain.file }) { "Модель не установлена." }
        context.getSharedPreferences("settings", 0).edit().putString("brain_id", brain.id)
            .putString("model_name", brain.name).commit()
    }
    fun installBrain(context: Context, temporary: File, name: String, metadata: ModelMetadata? = null, activate: Boolean = true): Brain {
        // A unique destination preserves every existing model, even another copy with the same name.
        val id = "${UUID.randomUUID()}.gguf"
        val file = File(directory(context), id)
        Os.rename(temporary.absolutePath, file.absolutePath)
        val brain = Brain(id, name.take(120), file, metadata)
        metadata?.let { saveMetadata(context, id, it) }
        context.getSharedPreferences("settings", 0).edit().putString("brain_name_$id", brain.name).commit()
        if (activate) selectBrain(context, brain)
        return brain
    }
    private fun metadataFile(context: Context, id: String) = File(directory(context), "$id.json")
    private fun metadata(context: Context, id: String): ModelMetadata? = runCatching {
        val file = metadataFile(context, id)
        if (!file.isFile || file.length() > 256 * 1024) return null
        ModelMetadata.decode(org.json.JSONObject(file.readText()))
    }.getOrNull()
    fun saveMetadata(context: Context, id: String, metadata: ModelMetadata) {
        require(id == "legacy" || id.matches(Regex("[A-Za-z0-9_.-]+\\.gguf")))
        val temporary = File(directory(context), "$id.json.tmp")
        temporary.outputStream().use { it.write(metadata.json().toString().toByteArray()); it.fd.sync() }
        Os.rename(temporary.absolutePath, metadataFile(context, id).absolutePath)
    }
    fun installBuiltin(context: Context, temporary: File, model: ModelMetadata): Brain {
        require(BuiltinModels.find(context, model.id) == model)
        val id = "${model.id}.gguf"
        val file = File(directory(context), id)
        require(!file.exists()) { "Эта модель уже скачана." }
        val activate = selectedBrain(context) == null
        Os.rename(temporary.absolutePath, file.absolutePath)
        saveMetadata(context, id, model)
        context.getSharedPreferences("settings", 0).edit().putString("brain_name_$id", model.name).commit()
        return Brain(id, model.name, file, model).also { if (activate) selectBrain(context, it) }
    }
    fun installed(context: Context, model: ModelMetadata): Brain? = selectedBrain(context)?.takeIf { it.metadata?.sha256 == model.sha256 }
        ?: brains(context).firstOrNull { it.metadata?.sha256 == model.sha256 }
    fun deleteBrain(context: Context, brain: Brain) {
        require(brains(context).any { it.id == brain.id && it.file == brain.file })
        val wasActive = selectedBrain(context)?.id == brain.id
        check(brain.file.delete()) { "Не удалось удалить модель." }
        metadataFile(context, brain.id).delete(); ModelBenchmark.delete(context, brain.id)
        val prefs = context.getSharedPreferences("settings", 0)
        prefs.edit().remove("brain_name_${brain.id}").apply { if (brain.id == "legacy") remove("legacy_brain_name") }.commit()
        if (wasActive) {
            val next = brains(context).firstOrNull()
            if (next != null) selectBrain(context, next)
            else prefs.edit().remove("brain_id").remove("model_name").commit()
        }
    }
    fun voice(context: Context): String {
        val prefs = context.getSharedPreferences("settings", 0)
        return prefs.getString("tts_model", null)
            ?: if (prefs.getBoolean("custom_voice", false) && VoicePackage.custom(context).isDirectory) "custom" else "baya"
    }
    fun selectVoice(context: Context, id: String) {
        require(id in listOf("ruslan", "custom", "baya", "aidar", "kseniya", "eugene", "xenia"))
        require(id != "custom" || VoicePackage.custom(context).isDirectory) { "Сначала импортируйте голос." }
        context.getSharedPreferences("settings", 0).edit().putString("tts_model", id)
            .putBoolean("custom_voice", id == "custom").commit()
    }
}
