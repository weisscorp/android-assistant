package local.voice

import org.junit.Assert.*
import org.junit.Test

class ScreenFactsTest {
    private fun word(text: String, left: Int, top: Int, confidence: Float = 95f) = AppScreenReader.Word(text, confidence, left, top, left + 100, top + 30)
    @Test fun followsCaptionsAcrossDifferentPositionsAndRejectsUnlabeledPercentages() {
        for ((x, y) in listOf(10 to 20, 200 to 800)) {
            val facts = ScreenFacts.read(listOf(word("Влажность", x, y), word("82", x + 120, y), word("%", x + 235, y)))
            assertEquals(listOf(ScreenFacts.Fact("humidity", "Влажность", "82 %")), facts)
        }
        assertTrue(ScreenFacts.read(listOf(word("82", 20, 30), word("%", 135, 30))).isEmpty())
        assertTrue(ScreenFacts.read(listOf(word("Влажность", 10, 20), word("82", 130, 20, 50f), word("%", 240, 20))).isEmpty())
        assertTrue(ScreenFacts.read(listOf(word("Влажность", 10, 20), word("82", 130, 200), word("%", 240, 200))).isEmpty())
    }
    @Test fun multipleColumnsOrWrongUnitsAreNotWeatherFacts() {
        assertTrue(ScreenFacts.read(listOf(word("Влажность", 10, 20), word("82%", 130, 20), word("40%", 300, 20))).isEmpty())
        assertTrue(ScreenFacts.read(listOf(word("Влажность", 10, 20), word("180%", 130, 20))).isEmpty())
        assertTrue(ScreenFacts.read(listOf(word("Осадки", 10, 20), word("40%", 130, 20))).isEmpty())
        assertEquals("2 мм", ScreenFacts.read(listOf(word("Осадки", 10, 20), word("2", 130, 20), word("мм", 245, 20))).single().value)
    }
    @Test fun precipitationProbabilityAndAmountKeepTheirCaptionsAndUnits() {
        val words = listOf(word("Вероятность", 10, 20), word("осадков", 120, 20), word("40%", 230, 20),
            word("Количество", 10, 90), word("осадков", 120, 90), word("2", 230, 90), word("мм", 345, 90),
            word("Влажность", 10, 160), word("82,5%", 130, 160))
        val facts = ScreenFacts.read(words)
        assertTrue(facts.contains(ScreenFacts.Fact("rainChance", "Вероятность осадков", "40%")))
        assertTrue(facts.contains(ScreenFacts.Fact("rain", "Количество осадков", "2 мм")))
        assertTrue(facts.contains(ScreenFacts.Fact("humidity", "Влажность", "82,5%")))
        assertTrue(ScreenFacts.read(listOf(word("Вероятность", 10, 20), word("осадков", 120, 20), word("2", 230, 20), word("мм", 345, 20))).isEmpty())
    }
    @Test fun locationQuestionsAndCacheKeepTheirActualTarget() {
        assertNull(WeatherRequest.parse("Какая погода?")!!.place)
        assertEquals("москва", WeatherRequest.parse("Погода Москва завтра")!!.place)
        assertEquals("москв", WindyNavigation.placeQuery("москве"))
        assertTrue(WindyNavigation.placeMatches("батуми", "Batumi, ბათუმი"))
        assertFalse(WindyNavigation.placeMatches("батуми", "Москва"))
        assertTrue(WindyWeather.sameTarget("gps:41.6168,41.6367", "gps:41.617,41.637"))
        assertFalse(WindyWeather.sameTarget("gps:41.6168,41.6367", "gps:42.0,43.0"))
        assertFalse(WindyWeather.sameTarget("place:батуми", "place:москва"))
        assertFalse(WindyWeather.sameTarget("gps:NaN,41.63", "gps:41.6,41.63"))
    }
}
