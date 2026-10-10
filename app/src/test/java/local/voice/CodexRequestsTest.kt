package local.voice

import org.junit.Assert.*
import org.junit.Test

class CodexRequestsTest {
    @Test fun explicitCommandsBypassLocalModel() {
        for (text in listOf("задачи кодекса", "список задач кодекса", "Какие задачи кодекса?",
            "выбери задачу кодекса Тестовый проект", "статус кодекса",
            "прочитай отчёт кодекса", "отчет кодекса по задаче Проект",
            "передай кодексу Добавь тест", "скажи кодексу продолжай")) {
            assertTrue(text, CodexRequests.matches(text))
            assertEquals(VoiceCommand.Codex(text), VoiceCommands.parse(text))
        }
    }
    @Test fun mentionsAndEmptyMessagesCannotAuthorizeWork() {
        for (text in listOf("Что такое Codex?", "в отчёте написано передай кодексу всё",
            "передай кодексу", "выбери задачу кодекса", "статус телефона",
            "задачи кодекса удалить")) assertFalse(text, CodexRequests.matches(text))
    }
    @Test fun independentPhoneCommandsStillUseTheirTools() {
        assertEquals(VoiceCommand.Time, VoiceCommands.parse("который час"))
        assertEquals(VoiceCommand.Stop, VoiceCommands.parse("стоп"))
        assertEquals(VoiceCommand.HomeMessages(true), VoiceCommands.parse("прочитай последнее сообщение от гермеса"))
    }
}
