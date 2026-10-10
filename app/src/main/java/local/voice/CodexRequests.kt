package local.voice

import android.content.Context
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/** Explicit native-Codex commands go to Hub without loading the local LLM. */
object CodexRequests {
    fun matches(text: String): Boolean {
        val value = text.trim().lowercase(Locale.ROOT).replace('ё', 'е').trimEnd('.', '!', '?')
        if (value in setOf("задачи кодекса", "список задач кодекса", "какие задачи кодекса", "задачи codex")) return true
        if (listOf("выбери задачу кодекса ", "выбери чат кодекса ", "передай кодексу ", "скажи кодексу ", "codex ").any { value.startsWith(it) && value.length > it.length }) return true
        return listOf("статус кодекса", "отчет кодекса", "прочитай отчет кодекса", "что сделал кодекс").any {
            value == it || (value.startsWith("$it по задаче ") && value.length > "$it по задаче ".length)
        }
    }

    fun ask(context: Context, text: String, request: BackendClient.Request): String {
        if (BackendClient.endpoint(context).isBlank()) return "Подключите Hub в настройках домашнего сервера для доступа к Codex."
        require(matches(text))
        val id = UUID.randomUUID().toString()
        val body = JSONObject().put("id", id).put("client", "android:" + HomeLink.device(context)).put("text", text.take(2000))
        val (code, json) = BackendClient.exchange(context, "POST", "/api/codex/voice", body, request)
        if (code != 200) return when (code) {
            401, 403 -> "Hub отклонил ключ. Проверьте доступ в настройках."
            404, 405, 501 -> "Обновите Hub: мост Codex ещё не доступен."
            409 -> json.optString("error").take(500).ifBlank { "Предыдущее сообщение ещё выполняется. Проверьте задачу Codex." }
            else -> "Hub не подтвердил запрос. Автоматического повтора нет. Проверьте задачу Codex."
        }
        require(json.optString("id") == id)
        val reply = json.opt("reply") as? String ?: error("Missing Codex reply")
        require(reply.isNotBlank() && reply.length <= 1500)
        return reply
    }
}
