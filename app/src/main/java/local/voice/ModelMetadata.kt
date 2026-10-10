package local.voice

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** A pinned artifact and its actual GGUF chat metadata; weights are never packaged in the APK. */
data class ModelMetadata(val id: String, val name: String, val family: String, val repo: String,
    val filename: String, val size: Long, val quantization: String, val chatTemplate: String,
    val contextSize: Int, val recommendedThreads: Int, val architecture: String,
    val revision: String, val sha256: String, val validatedBinding: String = "", val license: String = "",
    val languageNote: String = "") {
    val url get() = "https://huggingface.co/$repo/resolve/$revision/$filename"
    fun json() = JSONObject().put("id", id).put("name", name).put("family", family).put("repo", repo)
        .put("filename", filename).put("size", size).put("quantization", quantization).put("chatTemplate", chatTemplate)
        .put("contextSize", contextSize).put("recommendedThreads", recommendedThreads).put("architecture", architecture)
        .put("revision", revision).put("sha256", sha256).put("validatedBinding", validatedBinding).put("license", license)
        .put("languageNote", languageNote)
    companion object {
        const val MAX_CATALOG_BYTES = 2L * 1024 * 1024 * 1024
        fun decode(value: JSONObject) = ModelMetadata(value.getString("id"), value.getString("name"), value.getString("family"),
            value.getString("repo"), value.getString("filename"), value.getLong("size"), value.getString("quantization"),
            value.getString("chatTemplate"), value.getInt("contextSize"), value.getInt("recommendedThreads"),
            value.getString("architecture"), value.getString("revision"), value.getString("sha256"),
            value.optString("validatedBinding"), value.optString("license"), value.optString("languageNote")).also {
                require(it.size > 0 && it.chatTemplate.length <= 200_000 && it.contextSize in 512..8192 && it.recommendedThreads in 1..8)
                require(it.sha256.matches(Regex("[0-9a-f]{64}")))
            }
        fun digest(file: File): String = file.inputStream().use { input ->
            val hash = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(256 * 1024)
            while (true) { val read = input.read(buffer); if (read < 0) break; hash.update(buffer, 0, read) }
            hash.digest().joinToString("") { "%02x".format(it) }
        }
        fun imported(info: GgufMetadata, file: File, name: String, digest: String): ModelMetadata =
            ModelMetadata("import-$digest", name.take(120), info.architecture, "", file.name, file.length(),
                "GGUF type ${info.fileType}", info.chatTemplate, info.contextSize, info.recommendedThreads,
                info.architecture, "", digest, info.bindingRevision)
    }
}
data class GgufMetadata(val architecture: String, val chatTemplate: String, val contextSize: Int,
    val recommendedThreads: Int, val trainedContextSize: Int, val fileType: String, val bindingRevision: String) {
    companion object {
        fun decode(value: JSONObject) = GgufMetadata(value.getString("architecture"), value.getString("chatTemplate"),
            value.getInt("contextSize"), value.getInt("recommendedThreads"), value.getInt("trainedContextSize"),
            value.getString("fileType"), value.getString("bindingRevision"))
    }
}
object BuiltinModels {
    @Volatile private var cached: List<ModelMetadata>? = null
    fun all(context: Context): List<ModelMetadata> = cached ?: synchronized(this) {
        cached ?: context.assets.open("model-catalog.json").bufferedReader().use { reader ->
            val data = JSONArray(reader.readText())
            (0 until data.length()).map { ModelMetadata.decode(data.getJSONObject(it)) }.also { entries ->
                require(entries.distinctBy { it.id }.size == entries.size)
                require(entries.all { it.size <= ModelMetadata.MAX_CATALOG_BYTES && it.repo.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) &&
                    it.filename.matches(Regex("[A-Za-z0-9_.-]+\\.gguf")) && it.revision.matches(Regex("[0-9a-f]{40}")) })
                cached = entries
            }
        }
    }
    /** The release gate is bound to the exact compiled Android runtime, not a model marketing name. */
    fun available(context: Context): List<ModelMetadata> = all(context).filter {
        it.validatedBinding == LocalBrain.bindingRevision && it.architecture in setOf("lfm2", "qwen3", "qwen35", "gemma3")
    }
    fun find(context: Context, id: String): ModelMetadata? = available(context).singleOrNull { it.id == id }
}
