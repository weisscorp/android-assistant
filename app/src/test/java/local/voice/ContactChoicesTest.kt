package local.voice

import org.junit.Assert.*
import org.junit.Test

class ContactChoicesTest {
    @Test fun matchingPrefersExactNameButKeepsAllNumbers() {
        val entries = listOf(
            ContactChoices.Entry(1, "Иван", "+995 555 111111", "мобильный"),
            ContactChoices.Entry(1, "Иван", "+995555222222", "рабочий"),
            ContactChoices.Entry(2, "Иван Петров", "+995555333333", "мобильный")
        )
        val found = ContactChoices.matches("иван", entries)
        assertEquals(2, found.size)
        assertTrue(found.all { it.id == 1L })
    }
    @Test fun partialNameRequiresWholeWordsAndDoesNotChooseBetweenPeople() {
        val entries = listOf(
            ContactChoices.Entry(1, "Иван Петров", "55511111", ""),
            ContactChoices.Entry(2, "Иван Сидоров", "55522222", ""),
            ContactChoices.Entry(3, "Иванна", "55533333", "")
        )
        assertEquals(2, ContactChoices.matches("Иван", entries).size)
        assertEquals(1L, ContactChoices.matches("Петров Иван", entries).single().id)
        assertTrue(ContactChoices.matches("Ивану", entries).isEmpty())
    }
    @Test fun duplicatesAndServiceCodesDoNotBecomeCallTargets() {
        val entries = listOf(
            ContactChoices.Entry(1, "Алёна", "555 11111", ""),
            ContactChoices.Entry(1, "Алёна", "55511111", ""),
            ContactChoices.Entry(1, "Алёна", "*100#", "")
        )
        assertEquals("55511111", ContactChoices.matches("  Алена  ", entries).single().number)
        assertTrue(ContactChoices.matches("", entries).isEmpty())
    }
}
