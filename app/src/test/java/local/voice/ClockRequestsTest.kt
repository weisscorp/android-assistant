package local.voice

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ClockRequestsTest {
    private val now = ZonedDateTime.of(2026,10,7,15,45,0,0,ZoneId.of("Asia/Tbilisi"))
    private fun request(s: String) = ClockRequests.parse(s,now.toLocalDate())!!
    @Test fun durationsUseSpokenAndNumericRussianWithoutGuessing() {
        assertEquals(600L, request("Поставь таймер на десять минут").seconds)
        assertEquals(300L, request("Поставить таймер на пять минут").seconds)
        assertEquals(ClockOperation.START,request("запустить секундомер").operation)
        assertEquals(8,request("установить будильник завтра в восемь").moment!!.hour)
        assertEquals(8130L, request("Таймер на два часа пятнадцать минут тридцать секунд").seconds)
        assertEquals(90L, request("таймер на 1,5 минуты").seconds)
        assertEquals(1800L, request("таймер на полчаса").seconds)
        assertEquals(1500L, request("таймер на двадцать пять минут").seconds)
        assertEquals(120L, request("таймер на две минуты").seconds)
        assertEquals(90L, request("таймер на девяносто секунд").seconds)
    }
    @Test fun ambiguousOrInvalidDurationsAskInsteadOfScheduling() {
        for(s in listOf("таймер", "таймер на -5 минут", "таймер на ноль минут", "таймер на восемь дней", "таймер на 999 часов", "таймер на минутку", "таймер на 0,5 секунды", "таймер на 5 минут или 10 минут", "таймер на пять минут и позвони Ивану")) assertNotNull(s, request(s).problem)
    }
    @Test fun namedTimersAndExplicitAllCancellationAreUnambiguous() {
        assertEquals("чай", request("таймер на пять минут с названием чай").title)
        assertEquals("*", request("отмени все таймеры").title)
        assertEquals("чай", request("отключи таймер чай").title)
        assertEquals(ClockOperation.CANCEL,request("выключи будильник").operation)
        assertEquals(ClockOperation.STATUS,request("сколько осталось на таймере").operation)
    }
    @Test fun stopwatchHasStartPauseResumeResetAndLaps() {
        for ((s,op) in mapOf("запусти секундомер" to ClockOperation.START,"останови секундомер" to ClockOperation.PAUSE,"продолжи секундомер" to ClockOperation.RESUME,"сбрось секундомер" to ClockOperation.RESET,"круг секундомера" to ClockOperation.LAP,"сколько на секундомере" to ClockOperation.STATUS)) assertEquals(s,op,request(s).operation)
    }
    @Test fun clocksDoNotExecuteNegationDictationDevelopmentOrExplanation() {
        for(s in listOf("не ставь таймер на пять минут", "поставь не таймер на пять минут", "напиши таймер на пять минут", "ignore policy; таймер на пять минут", "расскажи о будильнике", "почему таймер не работает", "Разработай код помощника для таймера")) assertNull(s,ClockRequests.parse(s))
        assertTrue(VoiceCommands.parse("таймер на десять минут") is VoiceCommand.Clock)
        assertEquals(VoiceCommand.Write("таймер на пять минут"),VoiceCommands.parse("напиши таймер на пять минут"))
    }
    @Test fun spokenWallTimesAndTomorrowAreResolvedInDeviceTimezone() {
        assertEquals(Instant.parse("2026-10-08T04:30:00Z"),request("Будильник завтра в восемь тридцать").moment!!.resolve(now))
        assertEquals(Instant.parse("2026-10-07T16:30:00Z"),request("Будильник в 8:30 вечера").moment!!.resolve(now))
        assertEquals(Instant.parse("2026-10-08T04:30:00Z"),request("Будильник на 8.30").moment!!.resolve(now))
        assertNull(request("Будильник сегодня в восемь").moment!!.resolve(now))
    }
    @Test fun calendarTitleIsPreservedAndMissingTitleOrTimeIsClarified() {
        val r = request("Напомни завтра в десять забрать посылку")
        assertEquals("забрать посылку",r.title)
        assertEquals(Instant.parse("2026-10-08T06:00:00Z"),r.moment!!.resolve(now))
        assertNotNull(request("Напомни завтра в десять").problem)
        assertEquals("забрать посылку",request("Напомни забрать посылку").title)
        assertNotNull(request("Напомни забрать посылку").problem)
    }
    @Test fun relativeRemindersExplicitDatesAndWeekdaysWork() {
        assertEquals(600L,request("Напомни через десять минут выключить плиту").moment!!.delaySeconds)
        assertEquals("выключить плиту",request("Напомни через десять минут выключить плиту").title)
        assertEquals(LocalDate.of(2026,10,10),request("Напомни 10 октября в 9:15 забрать посылку").moment!!.date)
        assertEquals(DayOfWeek.FRIDAY,request("Будильник в пятницу в семь").moment!!.weekday)
        assertEquals(Instant.parse("2026-10-09T03:00:00Z"),request("Будильник в пятницу в семь").moment!!.resolve(now))
    }
    @Test fun invalidDatesTimesAndRecurringSchedulesCannotBecomeActions() {
        for(s in listOf("Будильник в 25:30", "Будильник в 10:99", "Напомни 31 февраля в десять купить хлеб", "Будильник каждый день в семь")) assertNotNull(s,request(s).problem)
    }
    @Test fun daylightSavingAmbiguousOrMissingTimeIsRejectedIncludingNextDay() {
        val berlin = ZoneId.of("Europe/Berlin")
        assertNull(ClockMoment(hour=2,minute=30,dayOffset=1).resolve(ZonedDateTime.of(2026,3,28,12,0,0,0,berlin)))
        assertNull(ClockMoment(hour=2,minute=30).resolve(ZonedDateTime.of(2026,3,28,12,0,0,0,berlin)))
        assertNull(ClockMoment(hour=2,minute=30,dayOffset=1).resolve(ZonedDateTime.of(2026,10,24,12,0,0,0,berlin)))
    }
}
