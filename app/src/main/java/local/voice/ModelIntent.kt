package local.voice

/** Plain conversation uses a short answer prompt; possible device actions retain the tool planner. */
internal object ModelIntent {
    private val toolWords = Regex("""(?:^|[^\p{L}])(?:откр|запуст|зайди|перейди|прилож|телеграм|telegram|экран|наж|впиш|напиш|введ|ввод|набер|напечат|запиш|отправ|позвон|звон|найд|поищ|поиск|прокрут|листай|маршрут|навига|карт|таймер|будильник|установ|постав|созда|добав|разработ|обнов|исправ|измени|агент|сервер|задач|переключ|удал|включ|выключ|отмен|останов|продолж|пауз|покаж|музык|воспроиз|композиц|трек|уведом|громк|тише|громче|врем|час|заряд|батар|аккумулятор|процент|телефон)""", RegexOption.IGNORE_CASE)
    private val explanation = Regex("""^\s*(?:что такое|кто такой|почему|зачем|как устроен|как работает|расскажи о|объясни)(?=$|[^\p{L}])""", RegexOption.IGNORE_CASE)
    private val playerWords = Regex("(?:^|[^\\p{L}])(?:песн|играет|слушаю|исполнител|певец|певица|по[её]т|исполняет)", RegexOption.IGNORE_CASE)
    fun needsTools(text: String) = WeatherRequest.parse(text) != null || ClockRequests.kind(text) != null ||
        (!explanation.containsMatchIn(text) && (toolWords.containsMatchIn(text) || playerWords.containsMatchIn(text)))
}
