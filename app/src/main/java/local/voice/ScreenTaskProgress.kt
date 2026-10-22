package local.voice

/** Consume only a human clause whose corresponding action was actually acknowledged/verified. */
internal object ScreenTaskProgress {
    fun remaining(request: String, action: VoiceCommand, sameApp: ((String, String) -> Boolean)? = null): String {
        val trimmed = request.trim()
        val clause = when (action) {
            is VoiceCommand.Open -> Regex("^(?:открой|запусти|зайди в|перейди в)\\s+(?:приложение\\s+)?(.+?)(?:\\s*,\\s*|\\s+(?:и|затем|потом)\\s+)(.+)$", RegexOption.IGNORE_CASE)
            is VoiceCommand.Tap -> Regex("^нажми\\s+(.+?)(?:\\s*,\\s*|\\s+(?:и|затем|потом)\\s+)(.+)$", RegexOption.IGNORE_CASE)
            else -> null
        }?.matchEntire(trimmed)
        val name = when (action) { is VoiceCommand.Open -> action.app; is VoiceCommand.Tap -> action.label; else -> null }
        if (clause != null && name != null && (if (action is VoiceCommand.Open && sameApp != null) sameApp(clause.groupValues[1], name)
            else AppNames.canonical(clause.groupValues[1]) == AppNames.canonical(name)))
            return clause.groupValues[2].trim()
        if (action is VoiceCommand.Write) {
            val replacement = Regex("^замени\\s+.+?\\s+на\\s+(.+)$", RegexOption.IGNORE_CASE).matchEntire(trimmed)
            if (replacement?.groupValues?.get(1) == action.text) return "прочитай экран"
            val literal = VoiceCommands.parse(trimmed) as? VoiceCommand.Write
            if (literal?.text == action.text) return "прочитай экран"
        }
        return request
    }
}
