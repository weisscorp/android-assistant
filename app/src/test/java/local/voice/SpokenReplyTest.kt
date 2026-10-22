package local.voice

import org.junit.Assert.*
import org.junit.Test

class SpokenReplyTest {
    @Test fun decimalsAreSpokenAsFractionsAndDisplayIsUnchanged() {
        val raw = "Температура 26.7 градусов Цельсия. Ветер 2,5 метров в секунду, дождь 0,05 миллиметров, вероятность 12.2%."
        val spoken = SpokenReply.text(raw)
        assertEquals("Температура двадцать шесть и семь десятых градуса Цельсия. Ветер два и пять десятых метра в секунду, дождь ноль и пять сотых миллиметра, вероятность двенадцать и две десятых процента.", spoken)
        assertEquals(raw, SpokenReply.answer(raw))
        assertEquals(spoken, SpokenReply.text(spoken))
    }

    @Test fun signedDecimalsAndFractionGenderPreservePrecision() {
        assertEquals("минус три и одна десятая градуса Цельсия", SpokenReply.text("−3,1 °C"))
        assertEquals("минус ноль и двадцать одна сотая", SpokenReply.text("-0.21"))
        assertEquals("один и одиннадцать сотых", SpokenReply.text("1.11"))
        assertEquals("два и ноль десятых", SpokenReply.text("2.0"))
        assertEquals("ноль и одна миллионная", SpokenReply.text("0.000001"))
        assertEquals("двадцать шесть и семь десятых.", SpokenReply.text("26.7."))
    }

    @Test fun identifiersAndAddressesAreNotFractions() {
        for (raw in listOf("192.168.31.149", "версия 2.5", "version 3.1", "v 2.5", "https://example.test/26.7", "file_26.7", "abc26.7", "Пиши test26.7@example.test")) {
            assertEquals(raw, raw, SpokenReply.text(raw))
        }
    }

    @Test fun expansionStillRespectsSpeechLimit() {
        val spoken = SpokenReply.text("26.7 градусов. ".repeat(400))
        assertTrue(spoken.length <= 3000)
        assertTrue(spoken.endsWith("Прочитал только начало."))
    }

    @Test fun telegramReadIsNotSilentlyCutAtOldLimit() {
        val message = "Видимые сообщения в чате Маша. " + "а".repeat(2500)
        assertEquals(message, SpokenReply.text(message))
        assertTrue(SpokenReply.timeoutMillis(message) > 90_000L)
    }

    @Test fun oversizedReplyAnnouncesTruncationAndPreservesUnicode() {
        val raw = "А" + "🙂".repeat(2000)
        val reply = SpokenReply.text(raw)
        assertTrue(reply.length <= 3000)
        assertTrue(reply.endsWith("Прочитал только начало."))
        val prefix = reply.substringBefore(". Ответ слишком длинный.")
        assertEquals(raw.take(prefix.length), prefix)
        assertFalse(prefix.last().isHighSurrogate())
    }

    @Test fun deadlineRemainsBoundedForShortAndLongAnswers() {
        assertEquals(90_000L, SpokenReply.timeoutMillis("Готово"))
        assertEquals(300_000L, SpokenReply.timeoutMillis(SpokenReply.text("а".repeat(10000))))
    }

    @Test fun emptyAndUnspeakableResultsNeverBecomeSilentReplies() {
        for (raw in listOf("", "   ", "\n\t", "...", "—", "\u200B\uFEFF", "🙂")) {
            assertEquals(raw, SpokenReply.UNAVAILABLE, SpokenReply.answer(raw))
            assertEquals(raw, SpokenReply.UNAVAILABLE, SpokenReply.text(raw))
            assertTrue(raw, BrainRouting.failed(raw))
        }
        assertEquals("Не знаю. Уточните источник.", SpokenReply.answer("  Не знаю. Уточните источник.  "))
        assertEquals("42", SpokenReply.text("42"))
    }
}
