package local.voice

import org.junit.Assert.*
import org.junit.Test

class WordRegexTest {
    @Test fun standaloneWordsWorkInRussianAndMixedScriptsWithoutMatchingPrefixes() {
        val command = wordRegex("\\bстоп\\b", RegexOption.IGNORE_CASE)
        assertTrue(command.containsMatchIn("СТОП!"))
        assertTrue(command.containsMatchIn("пожалуйста, стоп"))
        for (text in listOf("стопка", "автостоп", "xстоп", "стоп_1", "стоп\u0301")) {
            assertFalse(text, command.containsMatchIn(text))
        }
        assertTrue(wordRegex("\\bbot\\b").containsMatchIn("a bot."))
        assertFalse(wordRegex("\\bbot\\b").containsMatchIn("ботbot"))
    }

    @Test fun russianGroupsCannotBecomeMessageRecipientsButSimilarNamesCan() {
        assertFalse(TelegramUiText.person("Команда, группа, 20 участников", "Команда"))
        assertFalse(TelegramUiText.person("Контакт, бот", "Контакт"))
        assertTrue(TelegramUiText.sourceChat("Команда, группа, 20 участников", "Команда"))
        assertTrue(TelegramUiText.person("Ботаник, был недавно", "Ботаник"))
    }
}
