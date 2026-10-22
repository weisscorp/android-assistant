package local.voice

import java.util.Locale

sealed interface VoiceCommand {
    data class Phone(val request: PhoneRequest) : VoiceCommand
    data class Clarify(val slot: VoiceSlot) : VoiceCommand
    data object Repeat : VoiceCommand
    data object Heard : VoiceCommand
    data object ControlHelp : VoiceCommand
    data class AudioNotes(val query: String) : VoiceCommand
    data object Stop : VoiceCommand
    data object Help : VoiceCommand
    data object Capabilities : VoiceCommand
    data object People : VoiceCommand
    data object Sources : VoiceCommand
    data class HubEvent(val text: String) : VoiceCommand
    data class Codex(val request: String) : VoiceCommand
    data class HomeMessages(val latest: Boolean) : VoiceCommand
    data class ReadSource(val alias: String) : VoiceCommand
    data class BrainMode(val mode: AssistantRequests.Mode) : VoiceCommand
    data class RememberPerson(val alias: String, val name: String) : VoiceCommand
    data class RememberTelegram(val alias: String, val target: String) : VoiceCommand
    data class ForgetPerson(val alias: String) : VoiceCommand
    data class FindContact(val name: String) : VoiceCommand
    data class Route(val request: NavigationRequest) : VoiceCommand
    data class Music(val request: MusicRequest) : VoiceCommand
    data object Time : VoiceCommand
    data class Clock(val request: ClockRequest, val proposed: Boolean = false) : VoiceCommand
    data object Battery : VoiceCommand
    data class Notifications(val latest: Boolean) : VoiceCommand
    data class Media(val action: PhoneMedia.Action) : VoiceCommand
    data object Buttons : VoiceCommand
    data object Screen : VoiceCommand
    data class Scroll(val forward: Boolean) : VoiceCommand
    data class Open(val app: String) : VoiceCommand
    data class OpenThen(val app: String, val next: String) : VoiceCommand
    data class Tap(val label: String) : VoiceCommand
    data class Write(val text: String) : VoiceCommand
    data class Call(val number: String) : VoiceCommand
    data class Navigate(val destination: String) : VoiceCommand
    data object UpdateCheck : VoiceCommand
    data object UpdateStatus : VoiceCommand
    data object ServerStatus : VoiceCommand
    data object ServerCancel : VoiceCommand
    data class Server(val request: String) : VoiceCommand
    data object Confirm : VoiceCommand
    data class Choose(val index: Int) : VoiceCommand
    data object Cancel : VoiceCommand
    data class ScreenTask(val request: String) : VoiceCommand
    data class Telegram(val request: TelegramRequest) : VoiceCommand
    data class Weather(val request: WeatherRequest) : VoiceCommand
    data class Chat(val text: String) : VoiceCommand
}
object VoiceCommands {
    // Whole-utterance read-only intent: durations, other cities and dictated text
    // must keep their own routes instead of being answered with this phone's time.
    private val timeSpacing = Regex("[\\s,]+")
    private val currentTime = Regex(
        "^(?:пожалуйста )?(?:(?:скажи|скажите|подскажи|подскажите|назови)(?: мне)?(?: пожалуйста)? )?" +
        "(?:время|(?:текущее|точное) время|" +
        "(?:который|какой) (?:(?:сейчас|щас|теперь) )?час|" +
        "(?:какое|каково) (?:(?:сейчас|щас|теперь) )?время|" +
        "сколько (?:(?:сейчас|щас|теперь) )?(?:времени|время|часов))" +
        "(?: (?:сейчас|щас|теперь))?(?: пожалуйста)?$"
    )
    private val trackStep = Regex("^(?:пожалуйста )?(?:(?:включи|переключи|поставь|переключись на) )?(?:(следующий|предыдущий) (?:трек|трэк)|(следующую|предыдущую|следующая|предыдущая) песн(?:ю|я))(?: пожалуйста)?$")
    private val appOpening = Regex("^(?:пожалуйста[\\s,]+)?(?:открой|запусти|зайди\\s+в|перейди\\s+в)\\s+(?:приложение\\s+)?(.+)$", RegexOption.IGNORE_CASE)
    private fun openingBody(raw: String) = appOpening.matchEntire(raw)?.groupValues?.get(1)?.trim()
    private fun open(raw: String): VoiceCommand.Open? {
        val name = openingBody(raw)?.replace(Regex("[\\s,]+пожалуйста[.!?]*$", RegexOption.IGNORE_CASE), "")
            ?.trim()?.trimEnd('.', '!', '?')?.trim() ?: return null
        return name.takeIf { it.isNotBlank() && !it.equals("приложение", ignoreCase = true) && it.length <= 120 }?.let(VoiceCommand::Open)
    }
    private fun trackCommand(value: String): VoiceCommand.Media? {
        val match = trackStep.matchEntire(timeSpacing.replace(value, " ").trim()) ?: return null
        val next = match.groupValues.drop(1).any { it.startsWith("следующ") }
        return VoiceCommand.Media(if (next) PhoneMedia.Action.NEXT else PhoneMedia.Action.PREVIOUS)
    }
    fun parse(text: String): VoiceCommand {
        val raw = text.trim()
        val value = raw.lowercase(Locale.ROOT).trimEnd('.', '!', '?')
        return when {
            value in setOf("стоп", "замолчи", "закончи разговор", "до свидания") -> VoiceCommand.Stop
            PhoneCommands.parse(raw) != null -> PhoneCommands.parse(raw)!!
            value in setOf("помощь", "что ты умеешь", "команды") -> VoiceCommand.Help
            value in setOf("аудиозаметки", "аудио заметки", "мои аудиозаметки", "прочитай аудиозаметки") -> VoiceCommand.AudioNotes("")
            value.startsWith("найди аудиозаметку ") -> VoiceCommand.AudioNotes(raw.substring("найди аудиозаметку ".length).trim())
            value in setOf("мои знания", "знания и источники", "мои источники", "источники геолокации", "сохранённые источники", "сохраненные источники") -> VoiceCommand.Sources
            Regex("^где(?: сейчас)? трансфер .+$").matches(value) -> VoiceCommand.ReadSource(PresenceFacts.query(raw)!!)
            value.startsWith("прочитай источник ") && PresenceFacts.validName(raw.substring("прочитай источник ".length)) -> VoiceCommand.ReadSource(raw.substring("прочитай источник ".length).trim().trimEnd('.', '!', '?'))
            value in setOf("да", "подтверждаю", "выполняй", "да открывай", "да нажми", "подтверждаю действие") -> VoiceCommand.Confirm
            value in setOf("нет", "отмена", "отмени") -> VoiceCommand.Cancel
            choice(value) != null -> VoiceCommand.Choose(choice(value)!!)
            value.startsWith("отправь событие в хаб ") -> VoiceCommand.HubEvent(raw.substring("отправь событие в хаб ".length).trim())
            CodexRequests.matches(raw) -> VoiceCommand.Codex(raw.take(2000))
            currentTime.matches(timeSpacing.replace(value, " ").trim()) -> VoiceCommand.Time
            ClockRequests.parse(raw) != null -> VoiceCommand.Clock(ClockRequests.parse(raw)!!)
            value in setOf("заряд", "батарея", "сколько заряда", "заряд батареи") -> VoiceCommand.Battery
            value in setOf("прочитай", "прочитай сообщения от гермеса", "прочитай сообщения гермеса", "сообщения от гермеса", "сообщения гермеса", "сообщения с сервера") -> VoiceCommand.HomeMessages(false)
            value in setOf("прочитай последнее сообщение от гермеса", "последнее сообщение от гермеса") -> VoiceCommand.HomeMessages(true)
            value in setOf("уведомления", "прочитай уведомления", "новые уведомления", "прочитай новые уведомления") -> VoiceCommand.Notifications(false)
            value in setOf("прочитай последнее уведомление", "последнее уведомление") -> VoiceCommand.Notifications(true)
            value in setOf("пауза музыки", "поставь музыку на паузу", "останови музыку") -> VoiceCommand.Media(PhoneMedia.Action.PAUSE)
            value in setOf("продолжи музыку", "включи музыку") -> VoiceCommand.Media(PhoneMedia.Action.PLAY)
            trackCommand(value) != null -> trackCommand(value)!!
            value in setOf("музыку громче", "сделай музыку громче") -> VoiceCommand.Media(PhoneMedia.Action.LOUDER)
            value in setOf("музыку тише", "сделай музыку тише") -> VoiceCommand.Media(PhoneMedia.Action.QUIETER)
            value in setOf("кнопки на экране", "покажи кнопки", "перечисли кнопки", "какие кнопки на экране") -> VoiceCommand.Buttons
            value in setOf("прочитай экран", "что на экране") -> VoiceCommand.Screen
            value in setOf("прокрути вниз", "листай вниз") -> VoiceCommand.Scroll(true)
            value in setOf("прокрути вверх", "листай вверх") -> VoiceCommand.Scroll(false)
            value.startsWith("выполни задачу ") && raw.length > 15 -> VoiceCommand.ScreenTask(raw.substring(15).trim().take(600))
            TelegramRequest.parse(raw) != null -> VoiceCommand.Telegram(TelegramRequest.parse(raw)!!)
            AssistantRequests.parse(raw) != null -> AssistantRequests.parse(raw)!!
            openThen(raw) != null -> openThen(raw)!!
            open(raw) != null -> open(raw)!!
            value.startsWith("напиши ") && raw.length > 7 -> VoiceCommand.Write(raw.substring(7).trim())
            value.startsWith("введи ") && raw.length > 6 -> VoiceCommand.Write(raw.substring(6).trim())
            value.startsWith("нажми ") && raw.length > 6 -> VoiceCommand.Tap(raw.substring(6).trim())
            value.startsWith("позвони ") && raw.length > 8 -> VoiceCommand.Call(raw.substring(8).trim())
            value.startsWith("маршрут ") && raw.length > 8 -> VoiceCommand.Navigate(raw.substring(8).trim())
            value in setOf("проверь обновления", "обнови приложение", "обнови помощника") -> VoiceCommand.UpdateCheck
            value in setOf("статус обновления", "версия обновления") -> VoiceCommand.UpdateStatus
            value in setOf("статус сервера", "результат сервера", "что с задачей сервера") -> VoiceCommand.ServerStatus
            value in setOf("отмени задачу сервера", "отменить задачу сервера") -> VoiceCommand.ServerCancel
            value.startsWith("сервер ") && raw.length > 7 -> VoiceCommand.Server(raw.substring(7).trim())
            WeatherRequest.parse(raw) != null -> VoiceCommand.Weather(WeatherRequest.parse(raw)!!)
            else -> VoiceCommand.Chat(raw.take(600))
        }
    }
    private fun openThen(raw: String): VoiceCommand.OpenThen? {
        val body = openingBody(raw) ?: return null
        val match = Regex("^(.+?)\\s+(?:и|затем|потом)\\s+((?:напиши|введи|нажми|прочитай экран|прокрути|листай)(?:\\s+.*)?)$", RegexOption.IGNORE_CASE).matchEntire(body) ?: return null
        val app = match.groupValues[1].trim().trimEnd(',')
        val next = match.groupValues[2].trim()
        if (app.isEmpty() || app.length > 120 || next.length > 1000) return null
        return when (parse(next)) {
            is VoiceCommand.Write, is VoiceCommand.Tap, is VoiceCommand.Scroll, VoiceCommand.Screen -> VoiceCommand.OpenThen(app, next)
            else -> null
        }
    }
    private fun choice(value: String): Int? = when (value) {
        "первый", "первая", "первую", "первое", "первое приложение", "вариант один", "номер один" -> 0
        "второй", "вторая", "вторую", "второе", "второе приложение", "вариант два", "номер два" -> 1
        "третий", "третья", "третью", "третье", "третье приложение", "вариант три", "номер три" -> 2
        "четвёртый", "четвертый", "четвёртая", "четвертая", "четвёртое приложение", "четвертое приложение", "вариант четыре", "номер четыре" -> 3
        "пятый", "пятая", "пятое приложение", "вариант пять", "номер пять" -> 4
        "шестой", "шестая", "вариант шесть", "номер шесть" -> 5
        "седьмой", "седьмая", "вариант семь", "номер семь" -> 6
        "восьмой", "восьмая", "вариант восемь", "номер восемь" -> 7
        "девятый", "девятая", "вариант девять", "номер девять" -> 8
        "десятый", "десятая", "вариант десять", "номер десять" -> 9
        else -> Regex("^(?:номер |вариант |кнопка )?([1-9]|10)$").matchEntire(value)?.groupValues?.get(1)?.toInt()?.minus(1)
    }
    fun phoneNumber(value: String): String? {
        // Only individual digit words. Groups such as «двадцать три» are ambiguous and rejected.
        val digits = mapOf("ноль" to "0", "нуль" to "0", "один" to "1", "одна" to "1", "два" to "2", "две" to "2", "три" to "3", "четыре" to "4", "пять" to "5", "шесть" to "6", "семь" to "7", "восемь" to "8", "девять" to "9")
        val raw = if (value.any { it.isLetter() }) {
            val tokens = value.lowercase(Locale.ROOT).trim().split(Regex("\\s+"))
            val converted = tokens.mapIndexed { index, token ->
                when { token == "плюс" && index == 0 -> "+"; token.matches(Regex("[0-9]+")) -> token; else -> digits[token] ?: return null }
            }
            converted.joinToString("")
        } else value
        if (!raw.matches(Regex("[+0-9 ()-]+"))) return null
        val number = raw.replace(Regex("[ ()-]"), "")
        return number.takeIf { it.matches(Regex("\\+?[0-9]{5,15}")) }
    }
}
