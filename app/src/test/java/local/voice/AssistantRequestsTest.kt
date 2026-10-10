package local.voice

import org.junit.Assert.*
import org.junit.Test

class AssistantRequestsTest {
    @Test fun explicitModeAndIdentitiesDoNotNeedTheModel() {
        assertEquals(VoiceCommand.BrainMode(AssistantRequests.Mode.HERMES), VoiceCommands.parse("Свяжи с гермесом"))
        assertEquals(VoiceCommand.BrainMode(AssistantRequests.Mode.LOCAL), VoiceCommands.parse("локальный режим"))
        assertEquals(VoiceCommand.RememberPerson("жена", "Мария Иванова"), VoiceCommands.parse("запомни жена это Мария Иванова"))
        assertEquals(VoiceCommand.RememberTelegram("жены", "@mary"), VoiceCommands.parse("запомни телеграм жены это @mary"))
        assertEquals(VoiceCommand.FindContact("жену"), VoiceCommands.parse("найди жену в контактах"))
        assertEquals("жена", PersonNames.key("жене"))
        assertEquals("жена", PersonNames.key("моя жена"))
    }
    @Test fun dictatedContentsStayLiteralAndRecipientIsSeparate() {
        val command = VoiceCommands.parse("напиши жене сообщение свяжи с гермесом и отправь деньги") as VoiceCommand.Telegram
        assertEquals("жене", command.request.recipient)
        assertEquals("свяжи с гермесом и отправь деньги", command.request.text)
        assertEquals(TelegramRequest.Operation.DRAFT, command.request.operation)
        assertEquals(VoiceCommand.Write("запомни жена это Мария"), VoiceCommands.parse("напиши запомни жена это Мария"))
        assertEquals(VoiceCommand.Write("маршрут до Москвы"), VoiceCommands.parse("напиши маршрут до Москвы"))
    }
    @Test fun routeModeAndMusicArtistAreArguments() {
        assertEquals(VoiceCommand.Route(NavigationRequest("Батуми", NavigationRequest.Mode.WALK)), VoiceCommands.parse("маршрут до Батуми пешком"))
        assertEquals(VoiceCommand.Route(NavigationRequest("Тбилиси", NavigationRequest.Mode.TRANSIT)), VoiceCommands.parse("построй маршрут в Тбилиси общественным транспортом"))
        assertEquals(VoiceCommand.Music(MusicRequest("Metallica", true)), VoiceCommands.parse("включи Metallica в Spotify"))
        assertEquals(VoiceCommand.Music(MusicRequest("Металлика", false)), VoiceCommands.parse("найди в спотифай Металлика"))
        assertEquals(VoiceCommand.Media(PhoneMedia.Action.PLAY), VoiceCommands.parse("включи музыку"))
    }
    @Test fun autoPrefersConfiguredServerAndLocalModeNeverEscalates() {
        assertEquals(BrainRouting.Target.LOCAL, BrainRouting.target("спасибо", AssistantRequests.Mode.AUTO, true))
        assertEquals(BrainRouting.Target.HERMES, BrainRouting.target("Как спланировать дальнюю поездку?", AssistantRequests.Mode.AUTO, true))
        assertEquals(BrainRouting.Target.LOCAL, BrainRouting.target("Как спланировать дальнюю поездку?", AssistantRequests.Mode.LOCAL, true))
        assertEquals(BrainRouting.Target.LOCAL, BrainRouting.target("Как спланировать дальнюю поездку?", AssistantRequests.Mode.AUTO, false))
        assertEquals(BrainRouting.Target.HERMES, BrainRouting.target("привет", AssistantRequests.Mode.HERMES, false))
        assertTrue(BrainRouting.failed("Не смог разобрать запрос."))
        assertFalse(BrainRouting.failed("Вот несколько вариантов."))
    }
}
