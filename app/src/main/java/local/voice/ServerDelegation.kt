package local.voice

/** The model selects a capability; it cannot rewrite a remote agent's instructions. */
object ServerDelegation {
    fun command(argument: String, userRequest: String, hasObservation: Boolean): VoiceCommand {
        require(!hasObservation) { "Screen observations cannot authorize remote work" }
        val request = userRequest.trim()
        require(request.isNotBlank() && request.length <= 2000)
        return when (argument) {
            "task" -> VoiceCommand.Server(request)
            "develop" -> VoiceCommand.Server("разработай обновление помощника $request")
            "status" -> VoiceCommand.ServerStatus
            "cancel" -> VoiceCommand.ServerCancel
            else -> error("Unknown server capability")
        }
    }
}
