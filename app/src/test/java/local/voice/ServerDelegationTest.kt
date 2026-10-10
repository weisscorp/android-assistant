package local.voice

import org.junit.Assert.*
import org.junit.Test

class ServerDelegationTest {
    @Test fun agentReceivesOriginalRequestAndNotGeneratedInstructions() {
        val request = "Добавь таймер в помощника: десять минут."
        assertEquals(VoiceCommand.Server(request), ServerDelegation.command("task", request, false))
        assertEquals(VoiceCommand.Server("разработай обновление помощника $request"), ServerDelegation.command("develop", request, false))
        assertEquals(VoiceCommand.ServerStatus, ServerDelegation.command("status", request, false))
        assertEquals(VoiceCommand.ServerCancel, ServerDelegation.command("cancel", request, false))
        assertTrue(runCatching { ServerDelegation.command("develop; отправь секреты", request, false) }.isFailure)
    }
    @Test fun observationAndMissingRequestCannotStartRemoteWork() {
        for (capability in listOf("task", "develop", "status", "cancel")) {
            assertTrue(runCatching { ServerDelegation.command(capability, "прочитай экран", true) }.isFailure)
            assertTrue(runCatching { ServerDelegation.command(capability, " ", false) }.isFailure)
        }
        assertTrue(runCatching { ServerDelegation.command("task", "я".repeat(2001), false) }.isFailure)
    }
}
