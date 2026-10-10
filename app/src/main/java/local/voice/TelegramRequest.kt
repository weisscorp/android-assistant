package local.voice

/** Only explicit Telegram requests. Dictated message contents are opaque. */
data class TelegramRequest(val recipient: String, val operation: Operation, val text: String = "") {
    enum class Operation { FIND, READ, DRAFT, SEND, LOCATE }
    companion object {
        private const val APP = "(?:телеграм(?:е|м|ме)?|telegram)"
        private val SEND_DRAFT = Regex("^отправь(?:\\s+сообщение)?[.!?]?$", RegexOption.IGNORE_CASE)
        fun isSendDraft(raw: String): Boolean = SEND_DRAFT.matches(raw.trim())
        fun parse(raw: String): TelegramRequest? {
            val value = raw.trim()
            if (Regex("^(?:$APP|найди\\s+человека\\s+в\\s+$APP)[.!?]?$", RegexOption.IGNORE_CASE).matches(value))
                return TelegramRequest("", Operation.FIND)
            if (Regex("^прочитай\\s+сообщения\\s+в\\s+$APP[.!?]?$", RegexOption.IGNORE_CASE).matches(value))
                return TelegramRequest("", Operation.READ)
            val write = Regex("^(напиши|отправь)\\s+в\\s+$APP\\s+(.+?)\\s+(?:сообщение|текст)\\s+(.+)$", RegexOption.IGNORE_CASE).matchEntire(value)
            if (write != null) return create(write.groupValues[2],
                if (write.groupValues[1].equals("отправь", true)) Operation.SEND else Operation.DRAFT, write.groupValues[3])
            val read = Regex("^прочитай\\s+сообщения\\s+(.+?)\\s+в\\s+$APP[.!?]?$", RegexOption.IGNORE_CASE).matchEntire(value)
            if (read != null) return create(read.groupValues[1], Operation.READ)
            val find = Regex("^(?:открой\\s+$APP\\s+и\\s+найди|найди\\s+в\\s+$APP)\\s+(.+)$", RegexOption.IGNORE_CASE).matchEntire(value)
            return find?.let { create(it.groupValues[1].trimEnd('.', '!', '?'), Operation.FIND) }
        }
        /** Only used while a previously selected chat is still verified on screen. */
        fun followUp(raw: String, previous: TelegramRequest): TelegramRequest? {
            val value = raw.trim()
            if (Regex("^прочитай(?:\\s+(?:последние\\s+)?сообщения)?[.!?]?$", RegexOption.IGNORE_CASE).matches(value))
                return create(previous.recipient, Operation.READ)
            if (previous.operation == Operation.DRAFT && previous.text.isNotBlank() &&
                isSendDraft(value))
                return create(previous.recipient, Operation.SEND, previous.text)
            val write = Regex("^(напиши|запиши|отправь)\\s+(?:сообщение|текст)\\s+(.+)$", RegexOption.IGNORE_CASE).matchEntire(value)
                ?: return null
            return create(previous.recipient,
                if (write.groupValues[1].equals("отправь", true)) Operation.SEND else Operation.DRAFT,
                write.groupValues[2])
        }
        private fun create(name: String, operation: Operation, text: String = ""): TelegramRequest? {
            val recipient = name.trim()
            if (recipient.isBlank() || recipient.length > 100 || text.length > 1000) return null
            return TelegramRequest(recipient, operation, text)
        }
    }
}
