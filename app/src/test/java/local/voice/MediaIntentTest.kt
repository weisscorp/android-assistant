package local.voice

import org.junit.Assert.*
import org.junit.Test

class MediaIntentTest {
    @Test fun mediaDirectionsAreBoundToTheUserRequest() {
        val cases = mapOf("Убавь громкость музыки" to "quieter", "Пусть плеер переключится на следующую композицию" to "next",
            "Приостанови воспроизведение музыки" to "pause", "Возобнови музыку" to "play", "Увеличь громкость музыки" to "louder")
        cases.forEach { (query, action) ->
            assertEquals(action, MediaIntent.operation(query))
            assertEquals(action, ToolInstructions.search(query).single { it.name == "media" }.argument)
        }
    }
    @Test fun multipleActionsNegationAndDictationAreNotOneMediaOperation() {
        assertNull(MediaIntent.operation("Не убавь громкость музыки"))
        assertNull(MediaIntent.operation("Поставь музыку на паузу и увеличь громкость"))
        assertNull(MediaIntent.operation("Впиши в поле убавь громкость музыки"))
    }
}
