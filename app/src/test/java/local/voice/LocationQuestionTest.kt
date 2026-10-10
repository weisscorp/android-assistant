package local.voice

import org.junit.Assert.*
import org.junit.Test

class LocationQuestionTest {
    @Test fun subjectToleratesSpokenWordOrderAndYo() {
        for (text in listOf("Где сейчас мой ребёнок находится?", "где находится мой ребёнок сейчас", "где находится сейчас мой ребёнок")) {
            assertEquals(text, "мой ребёнок", PresenceFacts.query(text))
            assertEquals(text, "мой ребёнок", LocationQuestion.subject(text))
        }
        assertEquals("курьер", PresenceFacts.query("где курьер сейчас находится?"))
        assertEquals("автобус", PresenceFacts.query("где находится автобус"))
    }

    @Test fun personalOrCurrentQuestionsNeedEvidence() {
        for (text in listOf("где моя дочь", "где дети", "где старший сын", "где мой автобус находится", "где Петя сейчас", "сколько метров до моего трансфера сейчас")) {
            assertNotNull(text, LocationQuestion.subject(text))
        }
        assertEquals("Лоцман", LocationQuestion.subject("где Лоцман", listOf("Лоцман")))
    }

    @Test fun geographyExplanationsAndShoppingDoNotBecomePersonalTracking() {
        for (text in listOf("где находится Париж", "где купить велосипед", "где сейчас можно купить велосипед", "почему дети ходят в школу", "как найти ребенка", "что такое геолокация")) {
            assertNull(text, LocationQuestion.subject(text))
        }
    }

    @Test fun dictationDoesNotTurnIntoAnotherCommand() {
        assertNull(LocationQuestion.subject("напиши жене сообщение где сейчас мой ребенок находится"))
        assertNull(PresenceFacts.query("напиши где находится сейчас курьер"))
        assertTrue(VoiceCommands.parse("напиши жене сообщение где сейчас мой ребенок находится") is VoiceCommand.Telegram)
    }

    @Test fun missingAndAmbiguousAnswersContainNoInventedPosition() {
        assertTrue(LocationQuestion.MISSING.contains("Не знаю"))
        assertTrue(LocationQuestion.MISSING.contains("источник"))
        val reply = LocationQuestion.ambiguous(listOf("Курьер", "Автобус"))
        assertTrue(reply.contains("Курьер")); assertTrue(reply.contains("Автобус"))
        assertFalse(reply.contains("сейчас находится"))
    }
}
