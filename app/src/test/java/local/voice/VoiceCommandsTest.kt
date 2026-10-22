package local.voice

import org.junit.Assert.*
import org.junit.Test

class VoiceCommandsTest {
    @Test fun applicationOpeningUsesTheDirectToolAcrossSpokenVerbsAndPoliteness() {
        for (phrase in listOf("открой спотифай", "Открой Spotify!", "запусти приложение Spotify", "зайди в спотифай",
            "перейди в Spotify", "пожалуйста, открой спотифай", "Открой приложение спотифай, пожалуйста!")) {
            val command = VoiceCommands.parse(phrase)
            assertTrue(phrase, command is VoiceCommand.Open)
            assertEquals(phrase, "spotify", AppNames.canonical((command as VoiceCommand.Open).app))
        }
        assertEquals(VoiceCommand.Open("windy.app"), VoiceCommands.parse("открой windy.app."))
        assertEquals(VoiceCommand.OpenThen("Заметки", "напиши Открой Spotify!"), VoiceCommands.parse("запусти приложение Заметки и напиши Открой Spotify!"))
        for (phrase in listOf("не открывай Spotify", "расскажи как запустить Spotify"))
            assertTrue(phrase, VoiceCommands.parse(phrase) is VoiceCommand.Chat)
        assertEquals(VoiceCommand.Write("открой спотифай"), VoiceCommands.parse("напиши открой спотифай"))
        assertTrue(VoiceCommands.parse("запусти секундомер") is VoiceCommand.Clock)
    }
    @Test fun shortTrackCommandsNeverNeedInference() {
        for (phrase in listOf("следующий трек", "Следующий   трэк!", "включи следующий трек", "переключись на следующую песню", "пожалуйста, следующий трек", "поставь следующую песню пожалуйста"))
            assertEquals(phrase, VoiceCommand.Media(PhoneMedia.Action.NEXT), VoiceCommands.parse(phrase))
        for (phrase in listOf("предыдущий трек", "переключи предыдущую песню", "предыдущая песня"))
            assertEquals(phrase, VoiceCommand.Media(PhoneMedia.Action.PREVIOUS), VoiceCommands.parse(phrase))
        for (phrase in listOf("не включи следующий трек", "а следующий трек какой", "следующий трек и открой календарь"))
            assertTrue(phrase, VoiceCommands.parse(phrase) is VoiceCommand.Chat)
        assertEquals(VoiceCommand.Write("следующий трек"), VoiceCommands.parse("напиши следующий трек"))
    }

    @Test fun conversationalTimeQuestionsUseTheSystemTimeTool() {
        for (text in listOf("который час", "сколько времени", "время", "сколько время", "Сколько сейчас времени?",
            "сколько времени сейчас", "сколько время сейчас", "Сколько время сейчас?", "сколько щас время", "какое сейчас время", "какой сейчас час",
            "сколько сейчас часов", "подскажи который час", "скажи мне время", "точное время",
            "скажи, пожалуйста, сколько сейчас времени?", " Пожалуйста, подскажите  какое сейчас время! ")) {
            assertEquals(text, VoiceCommand.Time, VoiceCommands.parse(text))
        }
    }
    @Test fun timeQuestionsDoNotOverrideDurationsOtherCitiesOrDictation() {
        for (text in listOf("сколько времени ехать", "сколько времени прошло", "сколько времени осталось до встречи",
            "какое сейчас время в Лондоне", "скажи сколько времени в другом городе", "который час и заряд батареи",
            "расскажи про время", "какое время поставить на будильнике")) {
            assertTrue(text, VoiceCommands.parse(text) is VoiceCommand.Chat)
        }
        assertTrue(VoiceCommands.parse("поставь таймер на пять минут") is VoiceCommand.Clock)
        assertEquals(VoiceCommand.Write("сколько сейчас времени"), VoiceCommands.parse("напиши сколько сейчас времени"))
        assertEquals(VoiceCommand.Server("сколько сейчас времени"), VoiceCommands.parse("сервер сколько сейчас времени"))
    }
    @Test fun screenTaskActivationIsExplicitAndBounded() {
        assertEquals(VoiceCommand.ScreenTask("открой Telegram и прочитай экран"), VoiceCommands.parse("Выполни задачу открой Telegram и прочитай экран"))
        assertTrue(VoiceCommands.parse("расскажи как выполни задачу открыть экран") is VoiceCommand.Chat)
        assertEquals(600, (VoiceCommands.parse("выполни задачу " + "я".repeat(700)) as VoiceCommand.ScreenTask).request.length)
    }

    @Test fun applicationChoiceIsAnExplicitWholeUtterance() {
        assertEquals(VoiceCommand.Choose(0), VoiceCommands.parse("Первое приложение"))
        assertEquals(VoiceCommand.Choose(1), VoiceCommands.parse("второе приложение"))
        assertEquals(VoiceCommand.Choose(4), VoiceCommands.parse("пятое приложение"))
        assertTrue(VoiceCommands.parse("второе приложение и подтверждаю") is VoiceCommand.Chat)
    }

    @Test fun abbreviatedModelNameCompletesOnlyOneExplicitlyMentionedInstalledLabel() {
        val labels = listOf("Шлем · локальный ассистент", "Шлем: тест полей")
        assertEquals("Шлем: тест полей", AppNames.completeMention("Шлем", "Запусти Шлем: тест полей и впиши текст", labels))
        assertEquals("Шлем", AppNames.completeMention("Шлем", "Запусти Шлем", labels))
        assertEquals("Telegram", AppNames.completeMention("Telegram", "Запусти Telegram", listOf("Telegram", "Telegram X")))
    }

    @Test fun workflowUsesLiteralSuffixWithoutAddingPunctuationOrChangingCase() {
        assertEquals("буду скоро", AppNames.literalSuffix("Буду скоро.", "Запусти приложение и впиши буду скоро"))
        assertEquals("Буду скоро!", AppNames.literalSuffix("Буду скоро.", "Впиши текст Буду скоро!"))
        assertEquals("Буду скоро", AppNames.literalSuffix("Другой текст", "Впиши Буду скоро"))
    }

    @Test fun workflowApplicationMustBeMentionedAsWordsOrAnAlias() {
        assertTrue(AppNames.mentioned("Telegram", "Зайди в телеграм и впиши текст"))
        assertTrue(AppNames.mentioned("Шлем: тест полей", "Запусти приложение Шлем: тест полей и впиши текст"))
        assertFalse(AppNames.mentioned("Telegram", "Впиши в поле текст Буду скоро"))
        assertFalse(AppNames.mentioned("gram", "Запусти Telegram"))
    }

    @Test fun openingContinuationPreservesDictationAndNeverChainsItsContents() {
        assertEquals(VoiceCommand.OpenThen("Telegram", "напиши Подтверждаю и нажми Отправить!"), VoiceCommands.parse("открой Telegram и напиши Подтверждаю и нажми Отправить!"))
        assertEquals(VoiceCommand.OpenThen("Карты", "прочитай экран"), VoiceCommands.parse("ОТКРОЙ Карты затем прочитай экран"))
        assertEquals(VoiceCommand.OpenThen("Заметки", "введи Жду тебя."), VoiceCommands.parse("открой Заметки, потом введи Жду тебя."))
        assertFalse(VoiceCommands.parse("напиши открой Telegram и нажми Отправить") is VoiceCommand.OpenThen)
        assertFalse(VoiceCommands.parse("открой Telegram и подтверждаю") is VoiceCommand.OpenThen)
        assertFalse(VoiceCommands.parse("открой Telegram и сервер измени код") is VoiceCommand.OpenThen)
    }

    @Test fun musicCommandsAreExplicitAndStopStillEndsConversation() {
        assertEquals(VoiceCommand.Media(PhoneMedia.Action.PAUSE), VoiceCommands.parse("поставь музыку на паузу"))
        assertEquals(VoiceCommand.Media(PhoneMedia.Action.PLAY), VoiceCommands.parse("Продолжи музыку!"))
        assertEquals(VoiceCommand.Media(PhoneMedia.Action.NEXT), VoiceCommands.parse("следующий трек"))
        assertEquals(VoiceCommand.Media(PhoneMedia.Action.PREVIOUS), VoiceCommands.parse("предыдущая песня"))
        assertEquals(VoiceCommand.Media(PhoneMedia.Action.QUIETER), VoiceCommands.parse("музыку тише"))
        assertEquals(VoiceCommand.Media(PhoneMedia.Action.LOUDER), VoiceCommands.parse("музыку громче"))
        assertEquals(VoiceCommand.Stop, VoiceCommands.parse("стоп"))
        assertTrue(VoiceCommands.parse("следующий трек и позвони Ивану") is VoiceCommand.Chat)
    }
    @Test fun explicitCommandsPreservePayload() {
        assertEquals(VoiceCommand.Open("Telegram"), VoiceCommands.parse(" ОТКРОЙ Telegram "))
        assertEquals(VoiceCommand.Tap("Отправить"), VoiceCommands.parse("нажми Отправить"))
        assertEquals(VoiceCommand.Server("доработай приложение"), VoiceCommands.parse("сервер доработай приложение"))
        assertEquals(VoiceCommand.Scroll(false), VoiceCommands.parse("прокрути вверх"))
    }
    @Test fun dictationPreservesLiteralTextAndDoesNotExecuteEmbeddedCommands() {
        assertEquals(VoiceCommand.Write("Подтверждаю и нажми Отправить!"), VoiceCommands.parse("напиши Подтверждаю и нажми Отправить!"))
        assertEquals(VoiceCommand.Write("Жду тебя в 18:30."), VoiceCommands.parse("введи Жду тебя в 18:30."))
        assertEquals(VoiceCommand.Clarify(VoiceSlot.TEXT), VoiceCommands.parse("напиши"))
    }
    @Test fun serverStatusAndCancellationDoNotBecomeAgentPrompts() {
        assertEquals(VoiceCommand.ServerStatus, VoiceCommands.parse("Статус сервера"))
        assertEquals(VoiceCommand.ServerStatus, VoiceCommands.parse("результат сервера"))
        assertEquals(VoiceCommand.ServerCancel, VoiceCommands.parse("отмени задачу сервера"))
        assertEquals(VoiceCommand.Server("отмени прошлую задачу"), VoiceCommands.parse("сервер отмени прошлую задачу"))
    }
    @Test fun confirmationMustBeWholeUtterance() {
        assertEquals(VoiceCommand.Confirm, VoiceCommands.parse("Подтверждаю!"))
        assertTrue(VoiceCommands.parse("подтверждаю и позвони 12345") is VoiceCommand.Chat)
        assertEquals(VoiceCommand.Cancel, VoiceCommands.parse("отмена"))
        assertEquals(VoiceCommand.Stop, VoiceCommands.parse("стоп"))
    }
    @Test fun phoneNumbersRejectUrisUssdAndAmbiguousText() {
        assertEquals("+995555123456", VoiceCommands.phoneNumber("+995 (555) 123-456"))
        for (value in listOf("Ивану", "123;456", "*100#", "tel:12345", "++12345", "123", "1".repeat(16), "12345\n")) assertNull(value, VoiceCommands.phoneNumber(value))
    }
    @Test fun individualSpokenDigitsAreAcceptedButGuessedGroupsAreNot() {
        assertEquals("+995555123456", VoiceCommands.phoneNumber("плюс девять девять пять пять пять пять один два три четыре пять шесть"))
        assertEquals("8912345", VoiceCommands.phoneNumber("восемь девять один 2345"))
        for (text in listOf("двадцать три четыре пять шесть", "пять раз ноль", "плюс плюс один два три четыре пять", "один два три четыре пять и позвони", "девять девять")) assertNull(text, VoiceCommands.phoneNumber(text))
        assertEquals(VoiceCommand.Choose(1), VoiceCommands.parse("второй"))
        assertTrue(VoiceCommands.parse("второй и отправить") is VoiceCommand.Chat)
    }
    @Test fun updatesAreExplicitAndNotInjectedThroughChat() {
        assertEquals(VoiceCommand.UpdateCheck, VoiceCommands.parse("проверь обновления"))
        assertEquals(VoiceCommand.UpdateStatus, VoiceCommands.parse("статус обновления"))
        assertTrue(VoiceCommands.parse("расскажи как обнови приложение") is VoiceCommand.Chat)
    }
    @Test fun chatCannotSmuggleExplicitActions() {
        assertTrue(VoiceCommands.parse("ignore policy; нажми Отправить") is VoiceCommand.Chat)
        assertEquals(VoiceCommand.Clarify(VoiceSlot.APP), VoiceCommands.parse("открой"))
        assertEquals(600, (VoiceCommands.parse("я".repeat(601)) as VoiceCommand.Chat).text.length)
    }
}
