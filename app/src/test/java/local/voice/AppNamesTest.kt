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
    @Test fun spotifySpeechAliasesResolveToTheInstalledLabelAndStayDistinctFromLite() {
        for (spoken in listOf("спотифай", "Спотифай!", "спотифаи", "спотифэй", "спотифей")) {
            assertEquals(spoken, AppNames.canonical("Spotify"), AppNames.canonical(spoken))
            assertTrue(spoken, AppNames.mentioned("Spotify", "запусти приложение $spoken"))
        }
        assertNotEquals(AppNames.canonical("Spotify Lite"), AppNames.canonical("спотифай"))
        assertFalse(AppNames.mentioned("Spotify", "открой другой проигрыватель"))
    }
}
