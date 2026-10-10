package local.voice

import org.junit.Assert.*
import org.junit.Test

class SpokenReplyTest {
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
