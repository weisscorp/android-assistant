package local.voice

import org.junit.Assert.*
import org.junit.Test

class DictationTextTest {
    @Test fun restoresTheActualDictationInsteadOfAnExampleFromThePrompt() {
        val request = "Зайди в Telegram, потом впиши в сообщение Буду скоро"
        assertEquals("Буду скоро", AppNames.literalSuffix("Буду через десять минут.", request))
        assertEquals("Буду через десять минут.", AppNames.literalSuffix("Буду через десять минут", "Впиши в поле ввода текст: Буду через десять минут."))
    }
    @Test fun quotedContentPreservesWhitespaceCaseAndCommandWords() {
        val request = "Открой Заметки и впиши в поле текст «  Hello, Маша! отмена и включи музыку  », затем прочитай экран"
        assertEquals("  Hello, Маша! отмена и включи музыку  ", DictationText.extract(request)!!.text)
        val control = DictationText.control(request)
        assertFalse(control.contains("включи музыку")); assertTrue(control.contains("прочитай экран"))
    }
    @Test fun plainDictationKeepsWordsThatCouldBeMarkersAndEmbeddedInstructions() {
        assertEquals("Текст принят", DictationText.extract("Напиши Текст принят")!!.text)
        assertEquals("отмена и нажми отправить", DictationText.extract("Впиши в поле отмена и нажми отправить")!!.text)
        assertEquals("Hello\nWorld!", DictationText.extract("Набери Hello\nWorld!")!!.text)
    }
    @Test fun explanationsNegationAndBrokenQuotesCannotInventLiteralPayload() {
        assertNull(DictationText.extract("Скажи «впиши в поле Привет»"))
        assertNull(DictationText.extract("Не напиши в поле Привет"))
        assertNull(DictationText.extract("Впиши в поле «незакрытый текст"))
        assertTrue(runCatching { AppNames.literalSuffix("пример из инструкции", "Сделай поле: Привет") }.isFailure)
    }
    @Test fun dictationCannotRetrieveDeviceOperationsOrDevelopment() {
        assertEquals(listOf("write", "answer"), ToolInstructions.search("Впиши в поле Поставь таймер и разработай код помощника, убавь громкость музыки").map { it.name })
    }
}
