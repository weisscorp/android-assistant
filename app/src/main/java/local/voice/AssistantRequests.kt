package local.voice

import java.util.Locale

/** These commands are resolved before inference. Quoted/dictated text is never parsed again. */
object AssistantRequests {
    enum class Mode { AUTO, LOCAL, HERMES }
    fun parse(raw: String): VoiceCommand? {
        val value = raw.lowercase(Locale.ROOT).trimEnd('.', '!', '?')
        if (value in setOf("свяжи с сервером", "свяжи с гермесом", "подключи сервер", "подключи гермес", "режим сервера", "режим гермеса"))
            return VoiceCommand.BrainMode(Mode.HERMES)
        if (value in setOf("отключи сервер", "отключи гермес", "локальный режим", "работай локально", "вернись на телефон", "автономный режим", "работай без интернета", "работай автономно", "режим без интернета"))
            return VoiceCommand.BrainMode(Mode.LOCAL)
        if (value in setOf("автоматический режим", "выбирай автоматически")) return VoiceCommand.BrainMode(Mode.AUTO)
        if (value in setOf("возможности", "что можешь локально", "что умеешь локально", "каталог возможностей")) return VoiceCommand.Capabilities
        if (value in setOf("кого ты помнишь", "мои контакты", "связи контактов")) return VoiceCommand.People
        (Regex("^найди\\s+контакт\\s+(.+)$", RegexOption.IGNORE_CASE).matchEntire(raw)
            ?: Regex("^найди\\s+(.+?)\\s+в\\s+(?:контактах|адресной книге)$", RegexOption.IGNORE_CASE).matchEntire(raw))?.let {
            if (PersonNames.valid(it.groupValues[1])) return VoiceCommand.FindContact(it.groupValues[1])
        }
        Regex("^забудь\\s+(?:контакт\\s+)?(.+)$", RegexOption.IGNORE_CASE).matchEntire(raw)?.let {
            if (PersonNames.valid(it.groupValues[1])) return VoiceCommand.ForgetPerson(it.groupValues[1])
        }
        Regex("^запомни\\s+(?:телеграм|telegram)\\s+(.+?)\\s+это\\s+(.+)$", RegexOption.IGNORE_CASE).matchEntire(raw)?.let {
            if (PersonNames.valid(it.groupValues[1]) && it.groupValues[2].matches(Regex("@?[\\p{L}\\p{N}][\\p{L}\\p{N} ._'’-]{0,99}")))
                return VoiceCommand.RememberTelegram(it.groupValues[1], it.groupValues[2])
        }
        Regex("^запомни\\s+(.+?)\\s+это\\s+(.+)$", RegexOption.IGNORE_CASE).matchEntire(raw)?.let {
            if (PersonNames.valid(it.groupValues[1]) && PersonNames.valid(it.groupValues[2]))
                return VoiceCommand.RememberPerson(it.groupValues[1], it.groupValues[2])
        }
        // Recipient grammar stays narrow: arbitrary «напиши ...» is still a literal field write.
        Regex("^(напиши|отправь)\\s+(.+?)\\s+(?:сообщение|текст)\\s+(.+)$", RegexOption.IGNORE_CASE).matchEntire(raw)?.let {
            if (PersonNames.valid(it.groupValues[2]) && it.groupValues[3].length <= 1000)
                return VoiceCommand.Telegram(TelegramRequest(it.groupValues[2],
                    if (it.groupValues[1].equals("отправь", true)) TelegramRequest.Operation.SEND else TelegramRequest.Operation.DRAFT, it.groupValues[3]))
        }
        Regex("^(напиши|отправь)\\s+(жене|мужу|маме|папе)\\s+(.+)$", RegexOption.IGNORE_CASE).matchEntire(raw)?.let {
            if (it.groupValues[3].length <= 1000) return VoiceCommand.Telegram(TelegramRequest(it.groupValues[2],
                if (it.groupValues[1].equals("отправь", true)) TelegramRequest.Operation.SEND else TelegramRequest.Operation.DRAFT, it.groupValues[3]))
        }
        MusicRequest.parse(raw)?.let { return VoiceCommand.Music(it) }
        NavigationRequest.parse(raw)?.let { return VoiceCommand.Route(it) }
        return null
    }
}

object PersonNames {
    fun valid(value: String) = value.trim().matches(Regex("[\\p{L}][\\p{L} ._'’-]{0,99}"))
    fun key(value: String): String {
        val name = value.lowercase(Locale.ROOT).replace('ё', 'е').trim().replace(Regex("\\s+"), " ")
            .removePrefix("моя ").removePrefix("мой ")
        return when (name) {
            "жене", "жену", "жены" -> "жена"
            "мужу", "мужа" -> "муж"
            "маме", "маму", "мамы" -> "мама"
            "папе", "папу", "папы" -> "папа"
            else -> name
        }
    }
}

data class NavigationRequest(val destination: String, val mode: Mode? = null) {
    enum class Mode(val label: String) { CAR("на машине"), WALK("пешком"), TRANSIT("общественным транспортом") }
    companion object {
        fun mode(value: String): Mode? = when (value.lowercase(Locale.ROOT).trim().trimEnd('.', '!', '?')) {
            "на машине", "машиной", "автомобилем", "машина" -> Mode.CAR
            "пешком", "пеший", "пеший маршрут" -> Mode.WALK
            "транспортом", "общественным транспортом", "на транспорте", "автобусом" -> Mode.TRANSIT
            else -> null
        }
        fun parse(raw: String): NavigationRequest? {
            val match = Regex("^(?:маршрут|построй маршрут|проложи маршрут)(?:\\s+(?:до|в))?\\s+(.+)$", RegexOption.IGNORE_CASE).matchEntire(raw) ?: return null
            var destination = match.groupValues[1].trim()
            val suffix = Regex("\\s+(на машине|машиной|автомобилем|пешком|общественным транспортом|на транспорте)$", RegexOption.IGNORE_CASE).find(destination)
            val mode = suffix?.let { mode(it.groupValues[1]) }
            if (suffix != null) destination = destination.substring(0, suffix.range.first).trim()
            return destination.takeIf { it.isNotBlank() && it.length <= 300 }?.let { NavigationRequest(it, mode) }
        }
    }
}

data class MusicRequest(val artist: String, val play: Boolean) {
    companion object {
        fun parse(raw: String): MusicRequest? {
            val match = Regex("^(включи|найди)\\s+(?:в\\s+(?:спотифай|spotify)\\s+)?(?:исполнителя\\s+|музыку\\s+)?(.+?)\\s+в\\s+(?:спотифай|spotify)$", RegexOption.IGNORE_CASE).matchEntire(raw)
                ?: Regex("^(включи|найди)\\s+в\\s+(?:спотифай|spotify)\\s+(?:исполнителя\\s+|музыку\\s+)?(.+)$", RegexOption.IGNORE_CASE).matchEntire(raw)
                ?: return null
            return match.groupValues[2].trim().takeIf { it.isNotBlank() && it.length <= 150 }?.let { MusicRequest(it, match.groupValues[1].equals("включи", true)) }
        }
    }
}
