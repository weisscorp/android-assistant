package local.voice

import org.junit.Assert.*
import org.junit.Test

class AppSpeechNamesTest {
    @Test fun arbitraryLatinLabelsGetCyrillicVariantsWithoutAnAppList() {
        for (label in listOf("Orbit Reader", "Aurora Notes", "Fast Browser", "Zoom", "Slack")) {
            val names = AppSpeechNames.variants(label)
            assertTrue(label, names.contains(label))
            assertTrue("No Russian spelling for $label: $names", names.any { it.any { c -> c in 'а'..'я' } && it.none { c -> c in 'a'..'z' } })
        }
    }
    @Test fun russianNamesHaveTheFormUsedByOpenCommands() {
        assertTrue(AppSpeechNames.variants("Орбитальная читалка").contains("орбитальную читалку"))
        assertTrue(AppSpeechNames.variants("Камера").contains("камеру"))
        assertEquals("мою библиотеку", AppSpeechNames.opening("Моя библиотека"))
        assertEquals(listOf("Заметки", "заметки"), AppSpeechNames.variants("Заметки"))
    }
    @Test fun uncertainAsrAppNamesAreOnlySuggestions() {
        assertTrue(AppSpeechNames.suggestionScore("патифон", "спотифай") >= .5)
        assertTrue(AppSpeechNames.suggestionScore("спатифай", "спотифай") > .7)
        assertEquals(0.0, AppSpeechNames.suggestionScore("камера", "спотифай"), .001)
        assertEquals(0.0, AppSpeechNames.suggestionScore("да", "спотифай"), .001)
        assertEquals(0.0, AppSpeechNames.suggestionScore("календарь", "спотифай"), .001)
    }
    @Test fun indexedQueryKeysAreWholeMentionsAndBounded() {
        val keys = AppSpeechNames.keys("Зайди в орбитальную читалку, потом прочитай экран!")
        assertTrue(keys.contains("орбитальнуючиталку"))
        assertFalse(keys.contains("италку"))
        assertTrue(AppSpeechNames.keys((1..1000).joinToString(" ") { "слово$it" }).size <= 450)
    }
}
