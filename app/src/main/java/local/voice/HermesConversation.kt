package local.voice

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object HermesConversation {
    data class Result(val reply: String, val action: VoiceCommand? = null)
    fun body(session: String, id: String, text: String, history: List<Pair<String, String>>, capabilities: JSONArray): JSONObject =
        JSONObject().put("version", 1).put("session", session).put("id", id).put("text", text.take(2000))
            .put("history", JSONArray().apply { history.takeLast(4).forEach { (user, reply) ->
                put(JSONObject().put("user", user.take(600)).put("assistant", reply.take(1000)))
            } }).put("capabilities", capabilities)
    fun ask(context: Context, session: String, text: String, history: List<Pair<String, String>>, request: BackendClient.Request): Result {
        val id = UUID.randomUUID().toString()
        val payload = body(session, id, text, history, CapabilityCatalog.json(context)).put("phoneDevice", HomeLink.device(context))
        val (code, json) = BackendClient.exchange(context, "POST", "/conversation", payload, request)
        if (code != 200) return Result(when (code) {
            401, 403 -> "Hermes отклонил токен. Проверьте подключение в настройках."
            404, 405, 501 -> "Сервер ещё не поддерживает разговор. Нужен новый мост с маршрутом conversation."
            503 -> "Голосовой агент Hermes на сервере ещё не настроен. Локальные команды доступны."
            429 -> "Hermes занят. Повторите позже или скажите локальный режим."
            else -> "Hermes не ответил. Локальные команды доступны; повторите запрос или скажите локальный режим."
        })
        require(json.opt("version") == 1 && json.optString("id") == id)
        val reply = json.opt("reply") as? String ?: error("Invalid Hermes reply")
        require(reply.isNotBlank() && reply.length <= 1500)
        val action = json.optJSONObject("action")?.let(RemotePhoneTools::decode)
        require(!json.has("action") || json.isNull("action") || action != null)
        return Result(reply, action)
    }
}

/** Server proposes typed capabilities. It cannot supply an Intent, URL, shell code or a confirmation. */
object RemotePhoneTools {
    fun requested(command: VoiceCommand, text: String): Boolean {
        val value = text.lowercase(java.util.Locale.ROOT)
        fun has(pattern: String) = Regex(pattern).containsMatchIn(value)
        return when (command) {
            VoiceCommand.Time, VoiceCommand.Battery -> true
            is VoiceCommand.Clock -> ClockRequests.parse(text)?.let { original -> original.kind==command.request.kind && original.operation==command.request.operation } == true
            is VoiceCommand.Open -> has("откр|запуст|зайди|перейди")
            is VoiceCommand.Call -> has("позвон|набери|звонок")
            is VoiceCommand.Telegram -> has("телеграм|telegram|напиш|отправ|сообщен|чат")
            is VoiceCommand.Route -> has("маршрут|навига|добрать|доехать|дойти|проложи")
            is VoiceCommand.Music -> has("включ|воспроиз|найди|музык|исполнител|spotify|спотифай")
            is VoiceCommand.Media -> if (command.action == PhoneMedia.Action.INFO) MediaIntent.operation(text) == "info"
                else has("музык|трек|песн|проигрыв|громк|громче|тише|пауз")
            is VoiceCommand.Weather -> WeatherRequest.parse(text) != null
            else -> false
        }
    }
    fun decode(json: JSONObject): VoiceCommand {
        require(json.length() == 2 && json.opt("tool") is String && json.opt("arguments") is JSONObject)
        val args = json.getJSONObject("arguments")
        fun text(key: String, limit: Int): String {
            val value = args.get(key) as? String ?: error("Invalid argument")
            require(value.isNotBlank() && value.length <= limit)
            return value
        }
        fun keys(vararg names: String) { require(args.keys().asSequence().toSet() == names.toSet()) }
        return when (json.getString("tool")) {
            "time" -> { keys(); VoiceCommand.Time }
            "clock" -> { keys("request"); VoiceCommand.Clock(ClockRequests.parse(text("request",600)) ?: error("Invalid clock request"),proposed=true) }
            "battery" -> { keys(); VoiceCommand.Battery }
            "open" -> { keys("app"); VoiceCommand.Open(text("app", 120)) }
            "call" -> { keys("recipient"); VoiceCommand.Call(text("recipient", 120)) }
            "telegram" -> {
                keys("recipient", "operation", "text")
                val operation = TelegramRequest.Operation.valueOf(text("operation", 10).uppercase(java.util.Locale.ROOT))
                require(operation != TelegramRequest.Operation.LOCATE) { "Location sources must be bound by the user." }
                val message = args.get("text") as? String ?: error("Invalid message")
                require(message.length <= 1000 && (operation !in setOf(TelegramRequest.Operation.SEND, TelegramRequest.Operation.DRAFT) || message.isNotBlank()))
                VoiceCommand.Telegram(TelegramRequest(text("recipient", 100), operation, message))
            }
            "navigate" -> {
                keys("destination", "mode")
                val mode = if (args.isNull("mode")) null else NavigationRequest.Mode.valueOf(text("mode", 10).uppercase(java.util.Locale.ROOT))
                VoiceCommand.Route(NavigationRequest(text("destination", 300), mode))
            }
            "music" -> { keys("artist", "play"); require(args.get("play") is Boolean); VoiceCommand.Music(MusicRequest(text("artist", 150), args.getBoolean("play"))) }
            "media" -> { keys("action"); VoiceCommand.Media(PhoneMedia.Action.valueOf(text("action", 10).uppercase(java.util.Locale.ROOT))) }
            "weather" -> {
                keys("place", "day"); require(args.opt("day") is Int && args.getInt("day") in 0..2)
                val place = if (args.isNull("place")) null else text("place", 100)
                VoiceCommand.Weather(WeatherRequest("Погода", args.getInt("day"), place, false, false))
            }
            else -> error("Unsupported phone capability")
        }
    }
}
