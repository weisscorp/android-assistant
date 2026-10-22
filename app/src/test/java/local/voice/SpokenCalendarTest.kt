package local.voice

import org.junit.Assert.*
import org.junit.Test

class SpokenCalendarTest {
    @Test fun alarmDatesAndDottedTimesAreCalendarSpeechNotFractions() {
        val raw = "Вы хотите установить будильник на 15.00 18 сентября?"
        assertEquals("Вы хотите установить будильник на пятнадцать часов восемнадцатого сентября?",SpokenReply.text(raw))
        assertEquals(raw,SpokenReply.answer(raw))
        assertEquals("Будильник на восемнадцатое сентября пятнадцать часов.",SpokenReply.text("Будильник на 18.09 15:00."))
        assertEquals("Встреча двадцать первого октября две тысячи двадцать шестого года в девять часов пять минут.",SpokenReply.text("Встреча 21.10.2026 в 09:05."))
        assertEquals("Будильник на пятнадцать часов восемнадцатого сентября",SpokenReply.text("Будильник на 15:00 18.09"))
        assertEquals("Сегодня восемнадцатое сентября.",SpokenReply.text("Сегодня 18 сентября."))
        assertEquals("Сегодня одиннадцатое октября две тысячи двадцать шестого года.",SpokenReply.text("Сегодня 11.10.2026."))
    }
    @Test fun timeUnitsUseGenderAndCaseAndKeepSeconds() {
        assertEquals("Сейчас один час одна минута",SpokenReply.text("Сейчас 01:01"))
        assertEquals("Будильник на двадцать один час одну минуту",SpokenReply.text("Будильник на 21:01"))
        assertEquals("в два часа две минуты",SpokenReply.text("в 02:02"))
        assertEquals("в пятнадцать часов ноль минут восемнадцать секунд",SpokenReply.text("в 15:00:18"))
        assertEquals("ноль часов",SpokenReply.text("00:00"))
    }
    @Test fun decimalMeasurementsAndIdentifiersRemainDistinctFromTime() {
        assertEquals("Увеличь на пятнадцать и ноль сотых процента",SpokenReply.text("Увеличь на 15.00 процентов"))
        assertEquals("Умножь на пятнадцать и ноль сотых",SpokenReply.text("Умножь на 15.00"))
        assertEquals("Результат пятнадцать и ноль сотых",SpokenReply.text("Результат 15.00"))
        for (raw in listOf("192.168.31.149","https://test.example/18.09.2026/15.00","версия 15.00","v 15.00","alarm_15.00","test15.00@example.test"))
            assertEquals(raw,raw,SpokenReply.text(raw))
        val spoken = SpokenReply.text("на 15.00 18 сентября")
        assertEquals(spoken,SpokenReply.text(spoken))
    }
    @Test fun impossibleDatesAreNeverInventedAndYearOrdinalsAreDeclined() {
        assertEquals("тридцать первого декабря двухтысячного года",SpokenReply.text("31 декабря 2000 года"))
        assertEquals("первого января тысяча девятьсот девяносто девятого года",SpokenReply.text("1 января 1999 года"))
        assertEquals("29.02.2025",SpokenReply.text("29.02.2025"))
    }
}
