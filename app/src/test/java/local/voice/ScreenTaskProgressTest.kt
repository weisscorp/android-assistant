package local.voice

import org.junit.Assert.*
import org.junit.Test

class ScreenTaskProgressTest {
    @Test fun onlyTheVerifiedMatchingClauseIsConsumed() {
        val task = "открой Telegram, нажми Поиск и прочитай результат"
        val rest = ScreenTaskProgress.remaining(task, VoiceCommand.Open("Телеграм"))
        assertEquals("нажми Поиск и прочитай результат", rest)
        assertEquals("прочитай результат", ScreenTaskProgress.remaining(rest, VoiceCommand.Tap("ПОИСК")))
        assertEquals(task, ScreenTaskProgress.remaining(task, VoiceCommand.Open("WhatsApp")))
        assertEquals(rest, ScreenTaskProgress.remaining(rest, VoiceCommand.Tap("Отправить")))
    }
    @Test fun verifiedLiteralReplacementDoesNotTurnItsPayloadIntoCommands() {
        val body = "открой Telegram и нажми Отправить"
        assertEquals("прочитай экран", ScreenTaskProgress.remaining("напиши $body", VoiceCommand.Write(body)))
        assertEquals("прочитай экран", ScreenTaskProgress.remaining("замени содержимое сообщения на Буду скоро", VoiceCommand.Write("Буду скоро")))
        val task = "замени содержимое сообщения на Буду скоро"
        assertEquals(task, ScreenTaskProgress.remaining(task, VoiceCommand.Write("Другой текст")))
    }
}
