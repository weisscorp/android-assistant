package local.voice

import org.junit.Assert.*
import org.junit.Test

class AppNamesTest {
    @Test fun russianSpeechMatchesInstalledLabelsWithoutPickingAPackage() {
        assertEquals(AppNames.canonical("Telegram"), AppNames.canonical("телеграм"))
        assertEquals(AppNames.canonical("YouTube"), AppNames.canonical("Ютуб"))
        assertEquals(AppNames.canonical("Settings"), AppNames.canonical("настройки"))
        assertNotEquals(AppNames.canonical("Telegram X"), AppNames.canonical("телеграм"))
        assertEquals("", AppNames.canonical(" ! "))
    }
}
