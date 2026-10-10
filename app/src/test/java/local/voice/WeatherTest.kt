package local.voice

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

class WeatherTest {
    private val today = LocalDate.of(2026, 10, 6)
    private fun request(text: String) = WeatherRequest.parse(text)!!
    private fun captured(name: String): ScreenTextNode {
        val xml = javaClass.getResourceAsStream("/weather/$name")!!
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml)
        fun node(element: Element): ScreenTextNode {
            val children = (0 until element.childNodes.length).mapNotNull { element.childNodes.item(it) as? Element }.map(::node)
            return ScreenTextNode(element.getAttribute("text"), element.getAttribute("content-desc"),
                element.getAttribute("resource-id"), element.getAttribute("clickable") == "true", children)
        }
        return node(doc.documentElement)
    }
    @Test fun realRedmiHomeNumbersRetainPlaceDatesAndUnits() {
        val forecast = WindyForecast.read(captured("windy-home.xml"), today)!!
        assertEquals("Batumi Beach (New Boulevard)", forecast.place)
        assertEquals("17°", forecast.days[today])
        assertEquals("17°", forecast.days[today.plusDays(1)])
        assertEquals("20°", forecast.days[today.plusDays(2)])
        assertEquals("2 м/с", forecast.cardWind)
        val answer = forecast.reply(request("Какая погода завтра?"), today)
        assertTrue(answer, answer.contains("7 октября: 17 градусов"))
        assertTrue(answer.contains("не текущий замер"))
        assertFalse(answer.contains("Цельсия")) // Home card doesn't expose the configured unit.
        val wind = forecast.reply(request("Какой ветер в Батуми?"), today)
        assertTrue(wind, wind.contains("2  метров в секунду"))
        assertTrue(wind.contains("Время этого значения приложение не сообщает"))
    }
    @Test fun chartDoesNotBecomeMadeUpDailyForecast() {
        assertNull(WindyForecast.read(captured("windy-detail.xml"), today))
        assertNull(WindyForecast.read(captured("windy-home.xml"), today.plusDays(10)))
        val forecast = WindyForecast.read(captured("windy-home.xml"), today)!!
        val wrongPlace = forecast.reply(request("Какая погода в Москве?"), today)
        assertTrue(wrongPlace, wrongPlace.contains("Прогноз для москве не получен"))
        assertFalse(wrongPlace.contains("17 градусов"))
        val rain = forecast.reply(request("Будет дождь завтра?"), today)
        assertTrue(rain.contains("наличие дождя определить не удалось"))
        val tomorrowWind = forecast.reply(request("Какой ветер завтра?"), today)
        assertFalse(tomorrowWind.contains("2  метров"))
        assertTrue(tomorrowWind.contains("не удалось прочитать"))
    }
    @Test fun questionRoutesToLiveToolButLiteralCommandsAndExplanationsStayLiteral() {
        for (text in listOf("Какая погода?", "Погода завтра", "Какой ветер в Батуми?", "Будет дождь?", "Какая температура на улице?", "Погода из windy.app")) {
            assertTrue(text, VoiceCommands.parse(text) is VoiceCommand.Weather)
            assertTrue(text, ModelIntent.needsTools(text))
            assertTrue(ToolInstructions.search(text).any { it.name == "weather" })
        }
        assertEquals(1, request("Погода завтра").day)
        assertEquals(2, request("Погода послезавтра").day)
        assertEquals("батуми", request("Погода в Батуми завтра").place)
        for (text in listOf("Что такое погода", "Почему идет дождь", "Температура аккумулятора")) assertNull(text, WeatherRequest.parse(text))
        assertTrue(VoiceCommands.parse("Напиши Какая погода завтра?") is VoiceCommand.Write)
        assertTrue(VoiceCommands.parse("Открой windy.app") is VoiceCommand.Open)
        for (text in listOf("Погода завтра утром", "Ветер в 18:00", "Погода в пятницу", "Погода 7 октября")) assertNull(text, request(text).day)
    }
    @Test fun duplicateOrUnlabelledValuesFailClosed() {
        val days = listOf(ScreenTextNode("вт, 06"), ScreenTextNode("17°"), ScreenTextNode("ср, 07"), ScreenTextNode("18°"))
        val card = ScreenTextNode(clickable = true, children = listOf(ScreenTextNode("Test Place")) + days)
        val duplicate = ScreenTextNode(children = listOf(card, card.copy(children = listOf(ScreenTextNode("Other Place")) + days)))
        assertNull(WindyForecast.read(duplicate, today))
        val ambiguous = card.copy(children = listOf(ScreenTextNode("Test Place"), ScreenTextNode("Other Place")) + days)
        assertNull(WindyForecast.read(ambiguous, today))
        val twoTemps = card.copy(children = listOf(ScreenTextNode("Test Place"), ScreenTextNode("вт, 06"), ScreenTextNode("17°"), ScreenTextNode("40°"), ScreenTextNode("ср, 07"), ScreenTextNode("18°")))
        assertNull(WindyForecast.read(twoTemps, today)?.days?.get(today))
    }
}
