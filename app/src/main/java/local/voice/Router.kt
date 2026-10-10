package local.voice

enum class Origin { USER, EXTERNAL_NOTIFICATION }
sealed interface Command {
    data object Updates : Command
    data class CallDraft(val recipient: String) : Command
    data class MessageDraft(val text: String) : Command
    data object Unknown : Command
}

// Replace only this interface with a constrained local LLM adapter.
// No model or notification can obtain permission to execute a tool.
fun interface IntentModel { fun parse(text: String): Command }
class RuleIntentModel : IntentModel {
    override fun parse(text: String): Command {
        val value = text.trim().lowercase(java.util.Locale.ROOT)
        return when {
            value in setOf("апдейты", "что нового", "обновления dot", "обновления дот") -> Command.Updates
            value.startsWith("позвони ") && value.length > 8 -> Command.CallDraft(text.trim().substring(8).trim())
            value.startsWith("напиши ") && value.length > 7 -> Command.MessageDraft(text.trim().substring(7).trim())
            else -> Command.Unknown
        }
    }
}
class MockToolRouter {
    fun route(command: Command, origin: Origin): String {
        if (origin != Origin.USER) return "Внешнее уведомление не может выполнять команды."
        val valid = when (command) {
            is Command.CallDraft -> command.recipient.isNotBlank() && command.recipient.length <= 120
            is Command.MessageDraft -> command.text.isNotBlank() && command.text.length <= 500
            else -> true
        }
        if (!valid) return "Аргументы команды пустые или превышают допустимую длину."
        return when (command) {
            Command.Updates -> "Демонстрация: мост к dot ещё не подключён. Реальных обновлений нет."
            is Command.CallDraft -> "Черновик звонка: ${command.recipient}. Звонок не выполнен."
            is Command.MessageDraft -> "Черновик сообщения: ${command.text}. Сообщение не отправлено."
            Command.Unknown -> "Команда не распознана. Скажите: что нового, позвони или напиши."
        }
    }
}
