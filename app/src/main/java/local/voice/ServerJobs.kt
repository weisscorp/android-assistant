package local.voice

import android.content.Context
import org.json.JSONObject
import java.util.UUID

/** Remember only a job ID and its endpoint; transcripts/results are not saved on the phone. */
object ServerJobs {
    private class ServerError(val code: Int) : RuntimeException()
    private fun response(code: Int, expected: Int) { if (code != expected) throw ServerError(code) }
    private val active = setOf("queued", "running", "cancelling")
    private val states = active + setOf("succeeded", "failed", "cancelled", "interrupted", "timed_out")
    data class Job(val id: String, val state: String, val reply: String) {
        fun spoken(): String = when (state) {
            "queued" -> "Задача ждёт своей очереди на домашнем сервере."
            "running" -> "Домашний агент ещё выполняет задачу."
            "cancelling" -> "Отмена запрошена. Сервер ещё останавливает задачу."
            "cancelled" -> "Серверная задача отменена."
            "interrupted" -> "Серверная задача прервана перезапуском сервера. Повторно она не запускалась."
            "timed_out" -> "Сервер остановил задачу по истечении времени."
            "failed" -> "Домашний агент завершил задачу с ошибкой."
            else -> "Домашний агент завершил задачу. $reply"
        }
    }
    fun decode(json: JSONObject, expected: String): Job {
        val id = json.getString("id")
        val state = json.getString("state")
        require(id == expected && UUID.fromString(id).toString() == id && state in states)
        require(json.opt("id") is String && json.opt("state") is String && json.opt("reply") is String)
        val reply = json.getString("reply").take(1500)
        require(state != "succeeded" || reply.isNotBlank())
        return Job(id, state, reply)
    }
    private fun last(context: Context): String? {
        val prefs = context.getSharedPreferences("settings", 0)
        if (prefs.getString("server_job_endpoint", null) != BackendClient.endpoint(context)) return null
        return prefs.getString("server_job", null)?.takeIf { runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false) }
    }
    private fun obtain(context: Context, id: String, request: BackendClient.Request): Job? {
        val (code, json) = BackendClient.exchange(context, "GET", "/jobs/$id", null, request)
        if (code == 404) return null
        response(code, 200)
        return decode(json, id)
    }
    fun perform(context: Context, command: VoiceCommand, request: BackendClient.Request): String {
        if (BackendClient.endpoint(context).isBlank()) return "Домашний сервер ещё не настроен. Добавьте HTTPS-адрес и токен в настройках."
        return runCatching {
            val previous = last(context)
            when (command) {
                VoiceCommand.ServerStatus -> if (previous == null) "Сохранённой серверной задачи нет. Скажите сервер и ваш запрос."
                    else obtain(context, previous, request)?.spoken() ?: "Сервер не нашёл предыдущий запрос. Перед новой задачей скажите отмени задачу сервера."
                VoiceCommand.ServerCancel -> if (previous == null) "Сохранённой серверной задачи нет."
                    else {
                        val (code, json) = BackendClient.exchange(context, "DELETE", "/jobs/$previous", null, request)
                        response(code, 200)
                        decode(json, previous).spoken()
                    }
                is VoiceCommand.Server -> {
                    if (previous != null) {
                        val job = obtain(context, previous, request)
                        if (job == null) return "Предыдущий запрос не подтверждён. Скажите статус сервера или отмени задачу сервера перед новым заданием."
                        if (job.state in active) return job.spoken() + " Для отмены скажите отмени задачу сервера."
                    }
                    val id = UUID.randomUUID().toString()
                    // Save before POST: a lost response/process death must not lead to an automatic duplicate.
                    check(context.getSharedPreferences("settings", 0).edit().putString("server_job", id)
                        .putString("server_job_endpoint", BackendClient.endpoint(context)).commit())
                    val (code, json) = BackendClient.exchange(context, "POST", "/jobs", JSONObject().put("id", id).put("text", command.request.take(2000)), request)
                    response(code, 202)
                    val job = decode(json, id)
                    if (job.state !in setOf("queued", "running")) job.spoken() else {
                        val purpose = if (command.request.startsWith("разработай обновление помощника ")) " для доработки помощника" else ""
                        "Задача принята домашним сервером$purpose. Скажите статус сервера для результата или отмени задачу сервера для отмены."
                    }
                }
                else -> error("Unsupported server command")
            }
        }.getOrElse { error ->
            when ((error as? ServerError)?.code) {
                401, 403 -> "Сервер отклонил токен. Проверьте настройки доступа."
                404, 405, 501 -> "Сервер не поддерживает очередь задач. Обновите серверную часть помощника."
                429 -> "Очередь домашнего сервера заполнена. Попробуйте позже."
                503 -> "Домашний агент или очередь задач не настроены на сервере."
                else -> "Не удалось получить ответ сервера. Запрос мог быть принят. Скажите статус сервера после восстановления связи. Локальные команды доступны."
            }
        }
    }
}
