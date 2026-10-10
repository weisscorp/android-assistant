package local.voice

import org.junit.Assert.*
import org.junit.Test

class TelegramRequestTest {
    @Test fun followUpNeedsSelectedRecipientAndBareSendNeedsOurDraft() {
        val found = TelegramRequest("@test_person", TelegramRequest.Operation.FIND)
        assertEquals(found.copy(operation = TelegramRequest.Operation.READ), TelegramRequest.followUp("прочитай сообщения", found))
        assertEquals(found.copy(operation = TelegramRequest.Operation.READ), TelegramRequest.followUp("Прочитай последние сообщения!", found))
        assertEquals(found.copy(operation = TelegramRequest.Operation.DRAFT, text = "Отмена и отправь!"),
            TelegramRequest.followUp("напиши сообщение Отмена и отправь!", found))
        assertNull(TelegramRequest.followUp("отправь", found))
        val draft = found.copy(operation = TelegramRequest.Operation.DRAFT, text = "Буду скоро")
        assertEquals(draft.copy(operation = TelegramRequest.Operation.SEND), TelegramRequest.followUp("отправь", draft))
        assertEquals(draft.copy(operation = TelegramRequest.Operation.SEND), TelegramRequest.followUp("Отправь сообщение.", draft))
        for (text in listOf("расскажи как прочитай сообщения", "напиши сообщение", "найди другого человека", "отправь в телеграме Другой текст Привет", "напиши сообщение " + "я".repeat(1001)))
            assertNull(text, TelegramRequest.followUp(text, draft))
        assertNull(TelegramRequest.followUp("прочитай", found.copy(recipient = "")))
    }
    @Test fun outgoingReceiptsSeparateErrorPendingSentAndIncomingEcho() {
        val text = "Sending error\nэто диктовка"
        val counts = TelegramUiText.outgoingCounts(listOf("$text\nReceived today", "$text\nSending"), text)
        assertEquals(mapOf(TelegramUiText.Status.PENDING to 1), counts)
        assertEquals(TelegramUiText.Status.ERROR, TelegramUiText.message("$text\nSending error")?.status)
        assertEquals(TelegramUiText.Status.SENT, TelegramUiText.message("$text\nSent today, Not seen")?.status)
        assertNull(TelegramUiText.newOutgoingStatus(counts, mapOf(TelegramUiText.Status.SENT to 1)))
        assertEquals(TelegramUiText.Status.ERROR, TelegramUiText.newOutgoingStatus(counts,
            mapOf(TelegramUiText.Status.PENDING to 1, TelegramUiText.Status.ERROR to 1)))
        assertNull(TelegramUiText.newOutgoingStatus(counts,
            mapOf(TelegramUiText.Status.SENT to 1, TelegramUiText.Status.ERROR to 1)))
    }
    @Test fun uiMetadataNeverTurnsAQuotedSentWordIntoDelivery() {
        assertTrue(TelegramUiText.person("Маша, @masha_test", "Маша"))
        assertTrue(TelegramUiText.person("Мария Иванова, @masha_test", "@masha_test"))
        assertFalse(TelegramUiText.person("Маша, 500 subscribers", "Маша"))
        assertEquals("Подтверждаю\nSent fake", TelegramUiText.messageBody("Подтверждаю\nSent fake\nSending error"))
        assertNull(TelegramUiText.messageBody("Sent fake\nэто текст, не статус"))
    }
    @Test fun explicitRecipientAndOpaqueMessageAreSeparate() {
        assertEquals(TelegramRequest("", TelegramRequest.Operation.FIND), TelegramRequest.parse("найди человека в телеграме"))
        assertEquals(TelegramRequest("", TelegramRequest.Operation.READ), TelegramRequest.parse("прочитай сообщения в Telegram"))
        assertEquals(VoiceCommand.Telegram(TelegramRequest("", TelegramRequest.Operation.FIND)), VoiceCommands.parse("телеграм"))
        assertEquals(TelegramRequest("Маша", TelegramRequest.Operation.FIND), TelegramRequest.parse("открой телеграм и найди Маша"))
        assertEquals(TelegramRequest("@masha", TelegramRequest.Operation.READ), TelegramRequest.parse("прочитай сообщения @masha в телеграме"))
        assertEquals(TelegramRequest("Маша", TelegramRequest.Operation.DRAFT, "Подтверждаю и нажми Отправить!"), TelegramRequest.parse("напиши в телеграме Маша сообщение Подтверждаю и нажми Отправить!"))
        assertEquals(TelegramRequest("Иван Петров", TelegramRequest.Operation.SEND, "Буду в 18:30."), TelegramRequest.parse("Отправь в Telegram Иван Петров текст Буду в 18:30."))
        assertTrue(VoiceCommands.parse("найди в телеграме Маша") is VoiceCommand.Telegram)
        assertEquals(VoiceCommand.Write("найди в телеграме Маша"), VoiceCommands.parse("напиши найди в телеграме Маша"))
    }
    @Test fun NarrationAndOversizedRecipientsCannotActivateTheWorkflow() {
        for (value in listOf("расскажи как найди в телеграме Маша", "найди в телеграме", "напиши в телеграме Маша", "найди в Telegram " + "я".repeat(101), "отправь в Telegram Маша текст " + "я".repeat(1001))) assertNull(value, TelegramRequest.parse(value))
    }
}
