package local.voice

import java.util.Locale

enum class VoiceSlot { APP, CONTACT, TEXT, BUTTON, VOLUME, BRIGHTNESS }
enum class PhoneAction { BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS, DISMISS_SHADE, LOCK, SCREENSHOT, POWER_MENU, VOLUME_UP, VOLUME_DOWN, VOLUME, BRIGHTNESS, TORCH_ON, TORCH_OFF, SETTINGS }
enum class PhoneSetting { GENERAL, WIFI, BLUETOOTH, INTERNET, LOCATION, DISPLAY, SOUND, APPS, ACCESSIBILITY, BATTERY }
data class PhoneRequest(val action: PhoneAction, val percent: Int? = null, val setting: PhoneSetting? = null)

/** Whole utterances only: dictated text and compound instructions are not executed as controls. */
internal object PhoneCommands {
    private val units = listOf("ноль", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять", "десять", "одиннадцать", "двенадцать", "тринадцать", "четырнадцать", "пятнадцать", "шестнадцать", "семнадцать", "восемнадцать", "девятнадцать")
    private val tens = mapOf("двадцать" to 20, "тридцать" to 30, "сорок" to 40, "пятьдесят" to 50, "шестьдесят" to 60, "семьдесят" to 70, "восемьдесят" to 80, "девяносто" to 90)
    fun percent(text: String): Int? {
        val value = text.lowercase(Locale.ROOT).trim().replace(Regex("\\s*(?:процент(?:ов|а)?|%)$"), "").trim()
        value.toIntOrNull()?.let { return it.takeIf { n -> n in 0..100 } }
        if (value == "сто") return 100
        units.indexOf(value).takeIf { it >= 0 }?.let { return it }
        tens[value]?.let { return it }
        val parts = value.split(' ')
        if (parts.size != 2) return null
        val ten = tens[parts[0]] ?: return null
        return units.indexOf(parts[1]).takeIf { it in 1..9 }?.let { ten + it }
    }
    fun parse(raw: String): VoiceCommand? {
        val text = raw.lowercase(Locale.ROOT).trim().trimEnd('.', '!', '?').replace(Regex("\\s+"), " ")
            .removePrefix("пожалуйста ").removeSuffix(" пожалуйста").trim()
        val action = when (text) {
            "громче", "сделай громче" -> PhoneAction.VOLUME_UP
            "тише", "сделай тише" -> PhoneAction.VOLUME_DOWN
            "назад", "вернись назад", "верни меня назад", "назад на один экран" -> PhoneAction.BACK
            "домой", "на главный экран", "открой главный экран", "сверни приложение" -> PhoneAction.HOME
            "последние приложения", "открой последние приложения", "покажи последние приложения" -> PhoneAction.RECENTS
            "открой уведомления", "открой шторку", "покажи шторку", "открой панель уведомлений" -> PhoneAction.NOTIFICATIONS
            "быстрые настройки", "открой быстрые настройки" -> PhoneAction.QUICK_SETTINGS
            "закрой шторку", "закрой быстрые настройки" -> PhoneAction.DISMISS_SHADE
            "заблокируй телефон", "заблокируй экран" -> PhoneAction.LOCK
            "сделай скриншот", "сделай снимок экрана", "снимок экрана" -> PhoneAction.SCREENSHOT
            "открой меню питания", "покажи меню выключения" -> PhoneAction.POWER_MENU
            "включи фонарик", "включи фонарь" -> PhoneAction.TORCH_ON
            "выключи фонарик", "выключи фонарь" -> PhoneAction.TORCH_OFF
            else -> null
        }
        if (action != null) return VoiceCommand.Phone(PhoneRequest(action))
        Regex("^(?:нажми|выбери)(?: кнопку| вариант)? (.+)$").matchEntire(text)?.let { match ->
            val index = when (match.groupValues[1]) {
                "первую", "первый" -> 0
                "вторую", "второй" -> 1
                "третью", "третий" -> 2
                "четвертую", "четвёртую", "четвертый", "четвёртый" -> 3
                "пятую", "пятый" -> 4
                else -> match.groupValues[1].toIntOrNull()?.takeIf { it in 1..10 }?.minus(1)
            }
            if (index != null) return VoiceCommand.Choose(index)
        }

        val slot = when (text) {
            "открой", "запусти", "открой приложение", "запусти приложение" -> VoiceSlot.APP
            "позвони", "набери номер" -> VoiceSlot.CONTACT
            "напиши", "введи", "напечатай" -> VoiceSlot.TEXT
            "нажми", "нажми кнопку" -> VoiceSlot.BUTTON
            "громкость", "измени громкость", "установи громкость" -> VoiceSlot.VOLUME
            "яркость", "измени яркость", "установи яркость" -> VoiceSlot.BRIGHTNESS
            else -> null
        }
        if (slot != null) return VoiceCommand.Clarify(slot)
        if (text in setOf("повтори", "повтори ответ", "повтори что сказал")) return VoiceCommand.Repeat
        if (text in setOf("что ты услышал", "что я сказал")) return VoiceCommand.Heard
        if (text in setOf("команды телефона", "как управлять телефоном", "помощь по управлению")) return VoiceCommand.ControlHelp
        for ((word, kind) in listOf("громкость" to PhoneAction.VOLUME, "яркость" to PhoneAction.BRIGHTNESS)) {
            val match = Regex("^(?:(?:сделай|поставь|установи|измени) )?$word(?: (?:на|до))? (.+)$").matchEntire(text) ?: continue
            val value = percent(match.groupValues[1])
            if (value == null || (kind == PhoneAction.BRIGHTNESS && value == 0))
                return VoiceCommand.Clarify(if (kind == PhoneAction.VOLUME) VoiceSlot.VOLUME else VoiceSlot.BRIGHTNESS)
            return VoiceCommand.Phone(PhoneRequest(kind, value))
        }
        val setting = when (text) {
            "открой настройки", "настройки телефона" -> PhoneSetting.GENERAL
            "открой настройки вайфая", "настройки вай фай", "открой вай фай", "открой wi-fi" -> PhoneSetting.WIFI
            "открой настройки блютуза", "настройки блютуз", "открой блютуз", "открой bluetooth" -> PhoneSetting.BLUETOOTH
            "открой настройки интернета", "настройки интернета" -> PhoneSetting.INTERNET
            "открой настройки геолокации", "настройки геолокации" -> PhoneSetting.LOCATION
            "открой настройки экрана", "настройки экрана" -> PhoneSetting.DISPLAY
            "открой настройки звука", "настройки звука" -> PhoneSetting.SOUND
            "открой настройки приложений" -> PhoneSetting.APPS
            "открой специальные возможности" -> PhoneSetting.ACCESSIBILITY
            "открой настройки батареи" -> PhoneSetting.BATTERY
            else -> null
        }
        return setting?.let { VoiceCommand.Phone(PhoneRequest(PhoneAction.SETTINGS, setting = it)) }
    }
    fun prompt(slot: VoiceSlot) = when (slot) {
        VoiceSlot.APP -> "Какое приложение открыть?"
        VoiceSlot.CONTACT -> "Кому позвонить? Назовите контакт или номер."
        VoiceSlot.TEXT -> "Какой текст ввести?"
        VoiceSlot.BUTTON -> "Какую кнопку нажать? Назовите надпись."
        VoiceSlot.VOLUME -> "Какая громкость музыки нужна? Назовите от нуля до ста процентов."
        VoiceSlot.BRIGHTNESS -> "Какая яркость нужна? Назовите от одного до ста процентов."
    }
    const val HELP = "Без интернета могу открыть приложение, вернуться назад или домой, показать последние приложения, открыть уведомления и быстрые настройки, заблокировать телефон, сделать скриншот, изменить громкость и яркость, включить фонарик. Скажите кнопки на экране и выберите номер, прочитай экран, нажми и название кнопки, введи и текст, прокрути вниз или вверх. Если не хватает названия, я спрошу. Скажите да, первый, второй, отмена или повтори. Для сложной задачи по экрану нужна установленная локальная модель. Системные разрешения выдаются один раз в настройках."
}
