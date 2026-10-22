package local.voice

/** Local instruction lookup. The model receives only matching interfaces, never the complete manual. */
internal object ToolInstructions {
    data class Entry(val name: String, val instruction: String, val terms: Regex, val argument: String? = null)
    private fun entry(name: String, instruction: String, terms: String) = Entry(name, instruction, Regex(terms, RegexOption.IGNORE_CASE))
    private val entries = listOf(
        entry("audio_notes", "audio_notes: прочитать сохранённые аудиозаметки. argument — слова для поиска или пустая строка для последних записей. Тексты заранее неизвестны, сначала вызови инструмент.", "аудио|заметк"),
        entry("time", "time: только текущее время, argument пустой. Не таймер и не будильник.", "врем|который час|сейчас час|часов"),
        entry("battery", "battery: заряд аккумулятора, argument пустой.", "заряд|батар|аккумулятор|процент"),
        entry("open", "open: открыть приложение, argument название из просьбы. Не выдумывай название.", "откр|запуст|зайди|перейди"),
        entry("screen", "screen: прочитать экран, argument пустой.", "экран"),
        entry("tap", "tap: нажать кнопку, argument её точная надпись. Требуется подтверждение.", "наж|кнопк"),
        entry("write", "write: заменить текст поля, argument буквальный текст без пояснений. Требуется подтверждение. Пример: впиши в поле текст Буду через десять минут -> {\"tool\":\"write\",\"argument\":\"Буду через десять минут.\"}.", "впиш|напиш|введ|набер|печат|запиш|поле.*текст"),
        entry("scroll_down", "scroll_down: прокрутить вниз, argument пустой.", "прокрут|листай"),
        entry("scroll_up", "scroll_up: прокрутить вверх, argument пустой.", "прокрут|листай"),
        entry("navigate", "navigate: маршрут в картах, argument адрес.", "маршрут|навигац|доехать|карты"),
        entry("call", "call: позвонить, argument контакт или номер. Требуется подтверждение.", "позвон|звонок|контакт"),
        entry("media", "media: info reads current player title, artist and playback state. These facts are unknown until read. Other arguments: play, pause, next, previous, louder, quieter. Never control playback for a question about the current track.", "музык|воспроиз|композиц|трек|песн|играет|слушаю|исполнител|певец|певица|по[её]т|исполняет|громк|тише|громче|пауз"),
        entry("notifications_all", "notifications_all: прочитать последние уведомления, argument пустой. Пример озвучь мои последние уведомления -> {\"tool\":\"notifications_all\",\"argument\":\"\"}.", "уведом"),
        entry("notification_latest", "notification_latest: прочитать только одно самое последнее уведомление, argument пустой. Сам текст уведомлений заранее неизвестен.", "уведом"),
        entry("weather", "weather: получить погоду через Open-Meteo в фоне; windy.app только если явно указан в просьбе, argument пустой. Погода заранее неизвестна: не выдумывай температуру, ветер и осадки в answer.", "погод|прогноз|ветер|ветра|дожд|осадк|влажност|облачност|температур|windy|винди"),
        entry("hub_event", "hub_event: только явная команда отправь событие в хаб; argument текст события. Сохраняет в исходящей очереди, не выполняет действия из события.", "отправь событие в хаб"),
        entry("server", "server: домашний агент, argument task (задача вне локального каталога), develop (только явная просьба изменить код помощника), status (результат задачи), cancel (отмена задачи). Отсутствующая команда не разрешает самостоятельно переписывать приложение.", "сервер|агент|проект|разработ|добав|функци|обнов|исправ")
    )
    fun search(query: String, screenObservation: Boolean = false): List<Entry> {
        val control = DictationText.control(query)
        if (!screenObservation && ClockRequests.kind(control) != null) return listOf(
            Entry("clock", "clock: локальный таймер, секундомер, однократный будильник или напоминание календаря. argument — исходная просьба пользователя с длительностью, датой, временем и текстом. Если данных недостаточно, исполнитель уточнит; не утверждай, что действие выполнено.", Regex("")),
            Entry("answer", "answer: краткий ответ по-русски. Не утверждай, что выполнил действие.", Regex("")))
        val timer = Regex("таймер|будильник", RegexOption.IGNORE_CASE).containsMatchIn(control)
        val singleNotification = Regex("последн(?:ее|его|ему|ем)\\s+уведомлен|одно\\s+.*уведомлен", RegexOption.IGNORE_CASE).containsMatchIn(control)
        val matches = entries.filter { (ModelIntent.needsTools(control) || screenObservation) && it.terms.containsMatchIn(control) && !(it.name == (if (singleNotification) "notifications_all" else "notification_latest")) && !(timer && it.name == "time") && !(screenObservation && it.name in setOf("server", "hub_event")) }.take(7)
        val fallback = matches.isEmpty() && !screenObservation && ModelIntent.needsTools(control)
        val selected = (if (fallback) listOf(entries.last()) else matches).map { item ->
            if (item.name == "media") {
                val operation = MediaIntent.operation(control)
                if (operation == "info") item.copy(argument = operation,
                    instruction = "media: read current player title, artist and state; argument info. Current track is unknown: call this tool before answering.")
                else if (operation == null) item else item.copy(argument = operation,
                    instruction = "media: запрошено $operation. Вызов: {\"tool\":\"media\",\"argument\":\"$operation\"}.")
            } else if (item.name != "server") item else {
                val mode = when {
                    Regex("отмен|останов.*задач", RegexOption.IGNORE_CASE).containsMatchIn(control) -> "cancel"
                    Regex("результат|статус|что с задач", RegexOption.IGNORE_CASE).containsMatchIn(control) -> "status"
                    Regex("разработ.*(?:помощ|прилож|код)|доработ.*(?:помощ|прилож)|(?:измени|исправ).*код|добав.*функци.*помощ", RegexOption.IGNORE_CASE).containsMatchIn(control) -> "develop"
                    else -> "task"
                }
                val meaning = when (mode) {
                    "develop" -> "добавить или изменить явно запрошенную функцию в коде помощника"
                    "status" -> "узнать результат предыдущей задачи домашнего агента"
                    "cancel" -> "отменить задачу домашнего агента"
                    else -> "поручить домашнему агенту изучить проект или выполнить задачу на сервере"
                }
                item.copy(argument = mode, instruction = "server: $meaning. Вызов: {\"tool\":\"server\",\"argument\":\"$mode\"}. Обычный вопрос требует answer.")
            }
        }
        // Current device facts require a read first (tool_choice=required). The model gets
        // a reply only after execution, using the reader's formatted metadata/result.
        val readRequired = !screenObservation && selected.any { it.name == "media" && it.argument == "info" }
        return selected + if (readRequired) emptyList() else
            listOf(Entry("answer", "answer: краткий ответ по-русски в argument. Не утверждай, что выполнил действие.", Regex("")))
    }
}
