package local.voice

import org.junit.Assert.*
import org.junit.Test

class PhoneCommandsTest {
    @Test fun completeLocalControlsAreTyped() {
        val pairs = mapOf("назад" to PhoneAction.BACK, "домой" to PhoneAction.HOME,
            "открой последние приложения" to PhoneAction.RECENTS, "открой шторку" to PhoneAction.NOTIFICATIONS,
            "открой быстрые настройки" to PhoneAction.QUICK_SETTINGS, "закрой шторку" to PhoneAction.DISMISS_SHADE,
            "заблокируй телефон" to PhoneAction.LOCK, "сделай скриншот" to PhoneAction.SCREENSHOT,
            "открой меню питания" to PhoneAction.POWER_MENU, "включи фонарик" to PhoneAction.TORCH_ON,
            "выключи фонарик" to PhoneAction.TORCH_OFF, "громче" to PhoneAction.VOLUME_UP)
        pairs.forEach { (text, action) -> assertEquals(text, VoiceCommand.Phone(PhoneRequest(action)), VoiceCommands.parse(text)) }
        assertEquals(VoiceCommand.Phone(PhoneRequest(PhoneAction.SETTINGS, setting=PhoneSetting.WIFI)), VoiceCommands.parse("открой вай фай"))
    }
    @Test fun missingArgumentsAskInsteadOfInventingAnAction() {
        mapOf("открой" to VoiceSlot.APP, "позвони" to VoiceSlot.CONTACT, "напиши" to VoiceSlot.TEXT,
            "нажми кнопку" to VoiceSlot.BUTTON, "измени громкость" to VoiceSlot.VOLUME, "яркость" to VoiceSlot.BRIGHTNESS)
            .forEach { (text, slot) -> assertEquals(VoiceCommand.Clarify(slot), VoiceCommands.parse(text)) }
    }
    @Test fun numbersAreBoundedAndDoNotExtractDigitsFromArbitrarySpeech() {
        mapOf("0" to 0, "100 процентов" to 100, "двадцать пять" to 25, "пятьдесят процентов" to 50,
            "девяносто девять" to 99, "сто" to 100).forEach { (text, value) -> assertEquals(value, PhoneCommands.percent(text)) }
        for (text in listOf("-1", "101", "сто один", "25 и заблокируй экран", "примерно сорок", "двадцать десять")) assertNull(text, PhoneCommands.percent(text))
        assertEquals(VoiceCommand.Phone(PhoneRequest(PhoneAction.VOLUME, 35)), VoiceCommands.parse("установи громкость на тридцать пять процентов"))
        assertEquals(VoiceCommand.Phone(PhoneRequest(PhoneAction.BRIGHTNESS, 70)), VoiceCommands.parse("яркость семьдесят"))
    }
    @Test fun quotedNegatedAndCombinedControlsDoNotExecuteAsSingleActions() {
        for (text in listOf("не блокируй экран", "как работает фонарик", "назад и позвони маме"))
            assertNull(text, PhoneCommands.parse(text))
        assertEquals(VoiceCommand.Clarify(VoiceSlot.BRIGHTNESS), VoiceCommands.parse("сделай яркость 101"))
        assertEquals(VoiceCommand.Clarify(VoiceSlot.BRIGHTNESS), VoiceCommands.parse("яркость ноль"))
        assertEquals(VoiceCommand.Write("заблокируй телефон"), VoiceCommands.parse("напиши заблокируй телефон"))
        assertEquals(VoiceCommand.Write("громкость пятьдесят"), VoiceCommands.parse("введи громкость пятьдесят"))
    }
    @Test fun choicesAndRepeatHaveTheirOwnLocalRoutes() {
        assertEquals(VoiceCommand.Buttons, VoiceCommands.parse("кнопки на экране"))
        assertEquals(VoiceCommand.Choose(9), VoiceCommands.parse("номер десять"))
        assertEquals(VoiceCommand.Choose(0), VoiceCommands.parse("1"))
        assertEquals(VoiceCommand.Repeat, VoiceCommands.parse("повтори"))
        assertEquals(VoiceCommand.Heard, VoiceCommands.parse("что ты услышал"))
        assertEquals(VoiceCommand.BrainMode(AssistantRequests.Mode.LOCAL), VoiceCommands.parse("работай без интернета"))
    }
}
