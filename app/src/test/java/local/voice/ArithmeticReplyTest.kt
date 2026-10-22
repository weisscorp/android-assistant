package local.voice

import org.junit.Assert.*
import org.junit.Test

class ArithmeticReplyTest {
    @Test fun spokenAndNumericRequestsAreCalculatedExactly() {
        for (text in listOf("сколько будет два плюс два", "Сколько будет 2+2?", "посчитай два плюс два", "два плюс два"))
            assertEquals(text, "4.", ArithmeticReply.answer(text))
        assertEquals("42.", ArithmeticReply.answer("чему равно шесть умножить на семь"))
        assertEquals("-3.", ArithmeticReply.answer("сколько будет два минус пять"))
        assertEquals("3,75.", ArithmeticReply.answer("1,5 + 2.25"))
        assertEquals("110.", ArithmeticReply.answer("сто один плюс девять"))
        assertEquals("0,5.", ArithmeticReply.answer("один разделить на два"))
        assertEquals("Приблизительно 0,33333333.", ArithmeticReply.answer("один разделить на три"))
        assertEquals("На ноль делить нельзя.", ArithmeticReply.answer("два поделить на ноль"))
    }
    @Test fun incompleteDictatedAndUnrelatedTextIsNeverInventedOrCalculated() {
        for (text in listOf("плюс два", "сколько будет плюс два", "напиши два плюс два", "не считай два плюс два",
            "два плюс два и открой Spotify", "почему два плюс два равно четыре", "2+2*3", "2/0 и сотри файл"))
            assertNull(text, ArithmeticReply.answer(text))
    }
}
