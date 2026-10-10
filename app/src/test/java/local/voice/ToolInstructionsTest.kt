package local.voice
import org.junit.Assert.*
import org.junit.Test
class ToolInstructionsTest {
    private fun names(text: String) = ToolInstructions.search(text).map { it.name }
    @Test fun onlyRelevantInterfacesAreRetrieved() {
        assertEquals(listOf("battery", "answer"), names("Сколько процентов батареи?"))
        assertEquals(listOf("media", "answer"), names("Убавь громкость музыки"))
        assertEquals(listOf("answer"), names("Почему небо голубое?"))
        assertEquals(listOf("answer"), names("Объясни, что такое домашний сервер"))
    }
    @Test fun localClocksCannotBecomeCurrentTimeOrServerTask() {
        assertEquals(listOf("clock", "answer"), names("Установи таймер на семь минут"))
        assertEquals(listOf("clock", "answer"), names("Запусти секундомер"))
        assertEquals(listOf("clock", "answer"), names("Напомни завтра в десять забрать посылку"))
        assertFalse(names("Поставь будильник через два часа").contains("time"))
    }
    @Test fun openingAndDictationRetrieveBothInterfaces() {
        val names = names("Зайди в Telegram, потом впиши в поле Буду скоро")
        assertTrue(names.containsAll(listOf("open", "write", "answer")))
        assertFalse(names.contains("server"))
    }
    @Test fun serverLookupReturnsOnlyTheRelevantOperation() {
        assertTrue(ToolInstructions.search("Поставь таймер").first().name == "clock")
        assertTrue(ToolInstructions.search("Разработай код помощника для таймера").first().instruction.contains("\"develop\""))
        assertTrue(ToolInstructions.search("Результат задачи агента").first().instruction.contains("\"status\""))
        assertTrue(ToolInstructions.search("Отмени задачу агента").first().instruction.contains("\"cancel\""))
        assertTrue(ToolInstructions.search("Поручи домашнему агенту изучить проект").first().instruction.contains("\"task\""))
    }
    @Test fun screenObservationCannotRetrieveServer() {
        assertFalse(ToolInstructions.search("сервер добавь функцию", true).any { it.name == "server" })
    }
}
