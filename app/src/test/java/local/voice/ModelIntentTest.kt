package local.voice
import org.junit.Assert.*
import org.junit.Test
class ModelIntentTest {
    @Test fun plainConversationAvoidsToolPlanner() {
        for (text in listOf("привет", "как тебя зовут", "как дела", "почему небо голубое", "столица Франции", "расскажи анекдот")) assertFalse(text, ModelIntent.needsTools(text))
    }
    @Test fun commandsAndDeviceQuestionsKeepToolPlanner() {
        for (text in listOf("можешь зайти в Telegram", "сколько осталось процентов батареи", "какое сейчас время", "поставь таймер", "озвучь мои уведомления", "убавь громкость", "изучи проект домашним агентом", "впиши в поле Привет", "как открыть приложение", "отмени задачу агента")) assertTrue(text, ModelIntent.needsTools(text))
    }
}
