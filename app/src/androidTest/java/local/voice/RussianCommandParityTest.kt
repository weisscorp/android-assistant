package local.voice

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Parsing only: these checks neither schedule alarms nor send messages or record audio. */
@RunWith(AndroidJUnit4::class)
class RussianCommandParityTest {
    @Test fun russianClocksHandleDurationsNegationAndEmbeddedDictation() {
        assertEquals(600L, ClockRequests.parse("Поставь таймер на десять минут")!!.seconds)
        assertEquals(ClockOperation.CANCEL, ClockRequests.parse("отмени все таймеры")!!.operation)
        assertEquals("*", ClockRequests.parse("отмени все таймеры")!!.title)
        assertNull(ClockRequests.parse("не ставь таймер на пять минут"))
        assertNull(ClockRequests.parse("напиши таймер на пять минут"))
        assertEquals(VoiceCommand.Write("таймер на пять минут"), VoiceCommands.parse("напиши таймер на пять минут"))
    }

    @Test fun sourceQuestionsAndRecipientTypesHaveTheSameUnicodeMeaning() {
        assertEquals("мой курьер", LocationQuestion.subject("где мой курьер"))
        assertNull(LocationQuestion.subject("где купить велосипед"))
        assertFalse(TelegramUiText.person("Команда, группа, 20 участников", "Команда"))
        assertTrue(TelegramUiText.sourceChat("Команда, группа, 20 участников", "Команда"))
        assertTrue(TelegramUiText.person("Ботаник, был недавно", "Ботаник"))
        assertFalse(wordRegex("\\bстоп\\b").containsMatchIn("стопка"))
        assertTrue(wordRegex("\\bстоп\\b").containsMatchIn("стоп!"))
    }
}
