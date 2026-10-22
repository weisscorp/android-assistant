package local.voice

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One compact local note, no transcript. The model supplies abstract topics and brief key points. */
object ConversationMemory {
    data class Note(val topics: List<String>, val points: List<String>, val updatedAt: Long) {
        fun context() = buildString {
            if (topics.isNotEmpty()) append("Тема: ${topics.joinToString(", ")}. ")
            if (points.isNotEmpty()) append("Ключевые моменты: ${points.joinToString("; ")}")
        }.take(480)
    }
    private fun prefs(context: Context) = context.getSharedPreferences("conversation_memory", 0)
    fun read(context: Context): Note? = runCatching {
        val raw = prefs(context).getString("note", null) ?: return null
        val json = JSONObject(raw)
        val topics = strings(json.optJSONArray("topics"), 3, 36)
        val points = strings(json.optJSONArray("points"), 3, 110)
        if (topics.isEmpty() && points.isEmpty()) null else Note(topics, points, json.optLong("updatedAt"))
    }.getOrNull()
    fun save(context: Context, topics: List<String>, points: List<String>, utterance: String) {
        fun cleaned(values: List<String>, limit: Int) = values.map { it.replace(Regex("[\\r\\n]+"), " ").trim() }
            .filter { it.isNotBlank() && it.length <= limit && !it.contains("<|") && !it.contains("http", true) &&
                !(utterance.length > 24 && it.contains(utterance.trim(), true)) }.distinct().take(3)
        val theme = cleaned(topics, 36)
        val moments = cleaned(points, 110)
        // An empty result leaves the previous directions intact (e.g. a greeting).
        if (theme.isEmpty() && moments.isEmpty()) return
        val note = JSONObject().put("topics", JSONArray(theme)).put("points", JSONArray(moments))
            .put("updatedAt", System.currentTimeMillis())
        // Commit completed memory immediately; speech may already be playing.
        prefs(context).edit().putString("note", note.toString()).commit()
        HomeLink.kick(context)
    }
    fun clear(context: Context) { prefs(context).edit().clear().commit(); HomeLink.kick(context) }
    fun message(context: Context) = read(context)?.context() ?: "Сохранённых тем пока нет. После обычного разговора здесь появится краткая заметка."
    private fun strings(array: JSONArray?, count: Int, length: Int) = if (array == null) emptyList() else
        (0 until minOf(count, array.length())).map { array.optString(it).take(length) }.filter(String::isNotBlank)
}
