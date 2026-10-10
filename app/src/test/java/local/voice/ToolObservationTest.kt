package local.voice

import org.junit.Assert.*
import org.junit.Test

class ToolObservationTest {
    private val read = ToolObservation("media", "info", "ok",
        mapOf("title" to "Test Song", "artist" to "Test Artist", "state" to "playing"),
        "Сейчас играет «Test Song» — Test Artist.")

    @Test fun groundedAnswerPreservesPlayerProvidedNames() {
        assertEquals("Играет Test Song, исполнитель Test Artist.",
            read.answer("Играет Test Song, исполнитель Test Artist.", false))
    }
    @Test fun fabricatedOmittedAndEnglishOnlyNamesUseReadFacts() {
        for (raw in listOf("Играет выдуманная песня.", "Играет Test Song.", "Now playing Test Song by Test Artist", "…"))
            assertEquals(read.fallback, read.answer(raw, false))
        assertEquals(read.fallback, read.answer("Играет Test Song, исполнитель Test Artist.", true))
    }
    @Test fun readerErrorsAreNotReplacedWithGuesses() {
        val denied = read.copy(status = "permission_required", fields = emptyMap(), fallback = "Нужен доступ к уведомлениям.")
        assertEquals(denied.fallback, denied.answer("Играет новая песня.", false))
    }
    @Test fun modelCannotChangeTheObservedPlaybackState() {
        val paused = read.copy(fields = read.fields + ("state" to "paused"), fallback = "На паузе «Test Song» — Test Artist.")
        assertEquals(paused.fallback, paused.answer("Играет Test Song, исполнитель Test Artist.", false))
        assertEquals("На паузе Test Song, исполнитель Test Artist.", paused.answer("На паузе Test Song, исполнитель Test Artist.", false))
    }
}
