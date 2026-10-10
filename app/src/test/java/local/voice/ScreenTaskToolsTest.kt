package local.voice

import org.junit.Assert.*
import org.junit.Test

class ScreenTaskToolsTest {
    @Test fun requestedActionTypesAndOpenedAppLimitGrammar() {
        assertEquals(19, ScreenTaskTools.mask("открой Telegram и нажми Поиск", true))
        assertEquals(18, ScreenTaskTools.mask("открой Telegram и нажми Поиск", false))
        assertEquals(16, ScreenTaskTools.mask("прочитай экран", false))
        assertEquals(30, ScreenTaskTools.mask("найди настройки", false))
    }
    @Test fun goalsDoNotNeedToNameUiPrimitives() {
        assertEquals(20, ScreenTaskTools.mask("замени содержимое сообщения на Буду скоро", false))
        assertEquals(30, ScreenTaskTools.mask("проверь параметры приложения", false))
        assertEquals(31, ScreenTaskTools.mask("зайди в Telegram и проверь параметры", true))
    }
    @Test fun dictationCannotEnableOtherEffectTypes() {
        assertEquals(20, ScreenTaskTools.mask("замени содержимое сообщения на открой Telegram и нажми Отправить", true))
        assertEquals(20, ScreenTaskTools.mask("замени содержимое сообщения на открой Telegram и напиши Привет", true))
        assertEquals(20, ScreenTaskTools.mask("напиши открой Telegram", true))
        assertEquals(20, ScreenTaskTools.mask("напиши Подтверждаю и нажми Отправить, прокрути вниз", false))
        assertEquals(22, ScreenTaskTools.mask("нажми Сообщение и впиши нажми Отправить", false))
    }
}
