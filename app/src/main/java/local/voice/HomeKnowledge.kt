package local.voice

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Phone is authoritative. A durable full snapshot propagates deletions as well as edits. */
internal object HomeKnowledge {
    fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    fun content(context: Context): Pair<JSONObject, Map<String, File>> {
        val store = SavedSources(context)
        val images = linkedMapOf<String, File>()
        val sources = JSONArray()
        store.list().sortedBy { it.id }.forEach { item ->
            val image = if (item.kind == SourceKind.IMAGE) store.image(item) else null
            val digest = image?.let { file ->
                check(file.length() in 1..10_485_760) { "Изображение знания недоступно." }
                val cache = context.getSharedPreferences("home_image_hashes", 0)
                val signature = "${file.length()}:${file.lastModified()}"
                val old = cache.getString(item.id, "").orEmpty().split(':')
                val cached = if (old.size == 3 && old.take(2).joinToString(":") == signature) old[2] else null
                (cached ?: sha(file.readBytes()).also { cache.edit().putString(item.id, "$signature:$it").commit() }).also { images[it] = file }
            }.orEmpty()
            sources.put(JSONObject().put("id", item.id).put("name", item.name).put("kind", item.kind.name)
                .put("value", item.value).put("participant", item.participant).put("aliases", JSONArray(item.aliases))
                .put("savedAt", item.savedAt).put("instructions", item.instructions).put("followLinks", item.followLinks)
                .put("readerRecipe", item.readerRecipe).put("imageSha256", digest)
                .put("observation", store.observation(item.id) ?: JSONObject.NULL))
        }
        val people = JSONArray().apply { PeopleMemory(context).list().forEach {
            put(JSONObject().put("alias", it.alias).put("name", it.name).put("telegram", it.telegram))
        } }
        val note = ConversationMemory.read(context)?.let {
            JSONObject().put("topics", JSONArray(it.topics)).put("points", JSONArray(it.points)).put("updatedAt", it.updatedAt)
        }
        return JSONObject().put("version", 1).put("sources", sources).put("people", people).put("note", note ?: JSONObject.NULL) to images
    }
    fun prepare(context: Context, device: String): Pair<JSONObject, Map<String, File>> {
        val (content, images) = content(context)
        val digest = sha(content.toString().toByteArray())
        val outbox = AtomicFile(File(context.filesDir, "home-knowledge-$device.json"))
        val previous = runCatching { JSONObject(outbox.readFully().toString(Charsets.UTF_8)) }.getOrNull()
        val floor = context.getSharedPreferences("home_link", 0).getLong("revision_floor", 1)
        val revision = maxOf(floor, if (previous?.optString("digest") == digest) previous.getJSONObject("snapshot").getLong("revision")
            else (previous?.optJSONObject("snapshot")?.optLong("revision") ?: 0) + 1)
        content.put("revision", revision)
        check(content.toString().toByteArray().size <= 4_194_304) { "Знания превышают размер синхронизации." }
        if (previous?.optString("digest") != digest || previous?.optJSONObject("snapshot")?.optLong("revision") != revision) {
            val stream = outbox.startWrite()
            try { stream.write(JSONObject().put("digest", digest).put("snapshot", content).toString().toByteArray()); outbox.finishWrite(stream) }
            catch (error: Exception) { outbox.failWrite(stream); throw error }
        }
        return content to images
    }
}
