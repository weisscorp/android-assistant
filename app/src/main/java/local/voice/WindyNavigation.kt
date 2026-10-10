package local.voice

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.time.LocalDate
import java.util.Locale

/** App adapter: navigate by observed semantic controls; forecast reading is a separate module. */
internal class WindyNavigation(private val context: Context, private val request: WeatherRequest,
    private val active: () -> Boolean, private val say: (String) -> Unit, private val cache: (String) -> String?,
    private val done: (WindyForecast?, String?, String?) -> Unit,
    private val sourcePackage: String = WindyForecast.PACKAGE) {
    private val main = Handler(Looper.getMainLooper())
    private var cancelled = false
    private var query = ""
    private var key = ""
    private var choices = emptyList<PhoneAccessibility.Element>()
    private var choiceFrame: PhoneAccessibility.ForegroundSnapshot? = null
    private var choiceUntil = 0L
    private var selected = ""
    private fun available() = !cancelled && active()
    fun cancel() { cancelled = true }
    private fun stop(text: String) { if (!available()) return; cancel(); done(null, null, text) }
    private fun ui() = PhoneAccessibility.connected?.takeIf { it.foregroundPackage() == sourcePackage }
    private fun labels() = ui()?.elements().orEmpty()
    private fun tap(element: PhoneAccessibility.Element): Boolean {
        val service = ui() ?: return false
        val frame = service.foregroundSnapshot() ?: return false
        return service.tapElement(element, frame)
    }
    private fun waitFor(message: String, check: () -> Boolean, next: () -> Unit) = waitFor(message, 10_000, check, next)
    private fun waitFor(message: String, timeout: Long, check: () -> Boolean, next: () -> Unit) {
        val until = SystemClock.elapsedRealtime() + timeout
        var previous: PhoneAccessibility.ForegroundSnapshot? = null
        var stable = SystemClock.elapsedRealtime()
        var seen = false
        val poll = object : Runnable {
            override fun run() {
                if (!available()) return
                val frame = PhoneAccessibility.connected?.foregroundSnapshot()
                if (frame?.app == sourcePackage) seen = true
                if (seen && frame?.app != sourcePackage) { stop("Телефон заблокирован или приложение изменилось. Чтение остановлено."); return }
                val now = SystemClock.elapsedRealtime()
                if (frame != previous) { previous = frame; stable = now }
                if (frame?.app == sourcePackage && now - stable >= 400 && check()) next()
                else if (now >= until) stop(message)
                else main.postDelayed(this, 150)
            }
        }
        main.post(poll)
    }
    fun start() {
        if (request.place == null) {
            key = "windy:home"; open()
        } else {
            query = placeQuery(request.place); key = "place:$query"; open()
        }
    }
    private fun open() {
        if (context.getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked) {
            stop(cache(key) ?: "Для первого чтения windy.app для этого места разблокируйте телефон. Сохранённый прогноз доступен только для того же места."); return
        }
        val intent = context.packageManager.getLaunchIntentForPackage(sourcePackage)
        if (intent == null) { stop("windy.app не установлен."); return }
        if (runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) }.isFailure) { stop("Не удалось открыть windy.app."); return }
        waitFor("Не удалось открыть экран windy.app.", { ui() != null }, if (request.place == null) ::homeCard else ::settings)
    }
    private fun homeCard() {
        // The public launcher returns to Main. Use its actual selected spot without opening Search.
        fun card() = ui()?.textTree(sourcePackage)?.let { WindyForecast.read(it, LocalDate.now()) }
        waitFor("Не нашёл погодную карточку на главной странице windy.app.", 20_000, { card() != null }) {
            val forecast = card() ?: run { stop("Погодная карточка изменилась."); return@waitFor }
            cancel(); done(forecast, key, null)
        }
    }
    private var backs = 0
    private var settingsSteps = 0
    private fun settings() {
        if (labels().any { it.text == "Местоположение" }) { location(); return }
        val favoritePrompt = labels().any { it.text == "Добавить спот в избранное?" }
        if (favoritePrompt) {
            val no = labels().singleOrNull { it.text == "Нет" && it.id == "${sourcePackage}:id/negative_button" }
            if (no == null || !tap(no)) { stop("Закройте предложение добавить спот в избранное."); return }
            waitFor("Не удалось закрыть предложение избранного.", { labels().none { it.text == "Добавить спот в избранное?" } }, ::settings)
            return
        }
        val search = labels().any { it.id == "${sourcePackage}:id/search_src_text" }
        val detail = labels().any { it.id in setOf("${sourcePackage}:id/item_share", "${sourcePackage}:id/item_favorite_and_share") } ||
            (labels().any { it.label == "Прогноз" } && labels().any { it.id == "${sourcePackage}:id/more" })
        if (search || detail) {
            val service = ui(); val frame = service?.foregroundSnapshot()
            if (++backs > 3 || frame == null || !service.goBack(frame)) { stop("Не удалось вернуться на главную страницу windy.app."); return }
            waitFor("Главная страница windy.app недоступна.", { ui()?.foregroundSnapshot() != frame }, ::settings)
            return
        }
        val button = labels().filter { it.label == "Настройки" }.minByOrNull { Rect.unflattenFromString(it.bounds)?.top ?: Int.MAX_VALUE }
        val before = ui()?.foregroundSnapshot()
        if (++settingsSteps > 3 || button == null || !tap(button)) { stop("Не нашёл кнопку настроек windy.app. Откройте главную страницу приложения."); return }
        waitFor("Настройки места windy.app недоступны.", {
            ui()?.foregroundSnapshot() != before && labels().any { it.text in setOf("Местоположение", "Настройки") }
        }, ::settings)
    }
    private fun locationValue(): PhoneAccessibility.Element? {
        val elements = labels()
        val label = elements.singleOrNull { it.text == "Местоположение" } ?: return null
        val area = Rect.unflattenFromString(label.bounds) ?: return null
        // Compose exposes the caption and clickable value as siblings, not a row.
        return elements.filter { it.text.isNotBlank() && it.text != label.text &&
            Rect.unflattenFromString(it.bounds)?.let { value -> value.left >= area.right &&
                value.top < area.bottom && value.bottom > area.top && !value.isEmpty } == true }.singleOrNull()
    }
    private fun location() {
        val element = locationValue() ?: labels().singleOrNull { it.text == "Местоположение" }
        if (element == null || !tap(element)) { stop("Не нашёл выбор местоположения windy.app."); return }
        waitFor("Поиск места windy.app недоступен.", { ui()?.fieldTarget()?.hint?.lowercase(Locale.ROOT) in setOf("поиск", "search") }, ::search)
    }
    private fun search() {
        val service = ui() ?: return
        val target = service.fieldTarget() ?: run { stop("Поле поиска windy.app изменилось."); return }
        service.writeSearch(target, query, ::available) { receipt ->
            if (!available()) return@writeSearch
            if (receipt != "Текст записан в поле. Отправка не выполнялась.") { stop(receipt); return@writeSearch }
            waitFor("windy.app не нашёл место для запроса $query.", { candidates().isNotEmpty() }, ::select)
        }
    }
    private fun candidates() = labels().filter { it.id == "${sourcePackage}:id/searchLocationTitle" &&
        placeMatches(query, it.text) }
    private fun select() {
        val found = candidates().distinctBy { it.text }
        val exact = found.filter { placeMatches(query, it.text) && placeMatches(it.text, query) }
        choices = (if (exact.size == 1) exact else found).take(5)
        if (choices.isEmpty()) { stop("Не нашёл подходящее место windy.app."); return }
        choiceFrame = ui()?.foregroundSnapshot()
        if (choices.size == 1) choose(0)
        else {
            choiceUntil = SystemClock.elapsedRealtime() + 60_000
            say("windy.app нашёл несколько мест. " + choices.mapIndexed { i, item -> "${i + 1}: ${WeatherSpeech.place(item.text)}." }.joinToString(" ") + " Скажите первый, второй или отмена.")
        }
    }
    fun answer(command: VoiceCommand): Boolean {
        if (choices.size < 2 || choiceUntil == 0L) return false
        if (command == VoiceCommand.Cancel) { stop("Чтение погоды отменено."); return true }
        if (command !is VoiceCommand.Choose && command != VoiceCommand.Confirm) return false
        if (!available() || SystemClock.elapsedRealtime() > choiceUntil) { stop("Выбор места истёк. Повторите запрос."); return true }
        if (command is VoiceCommand.Choose) choose(command.index) else say("Назовите номер места из списка.")
        return true
    }
    private fun choose(index: Int) {
        val element = choices.getOrNull(index)
        val frame = choiceFrame
        if (element == null) { say("Такого номера нет. Назовите номер из списка."); return }
        if (frame == null || ui()?.tapElement(element, frame) != true) { stop("Результаты поиска изменились. Выбор остановлен."); return }
        choiceUntil = 0L
        waitFor("Не удалось проверить выбранное место windy.app.", { labels().any { it.text == "Местоположение" } }, ::selectedPlace)
    }
    private fun selectedPlace() {
        selected = locationValue()?.text.orEmpty()
        if (selected.isBlank()) { stop("Не удалось прочитать подтверждённое место в windy.app."); return }
        if (!placeMatches(query, selected)) { stop("windy.app выбрал другое место: ${WeatherSpeech.place(selected)}. Прогноз остановлен."); return }
        // Search can add Settings/Spot activities. The public launcher returns to
        // Main without guessing how many Back presses that history needs.
        val intent = context.packageManager.getLaunchIntentForPackage(sourcePackage)
        if (intent == null || runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) }.isFailure) {
            stop("Не удалось открыть главную страницу windy.app."); return
        }
        fun card() = ui()?.textTree(sourcePackage)?.let { WindyForecast.read(it, LocalDate.now()) }
        fun matches(forecast: WindyForecast) = placeMatches(selected, forecast.place) && placeMatches(forecast.place, selected)
        waitFor("Карточка прогноза выбранного места не появилась.", timeout = 20_000, check = { card()?.let(::matches) == true }) {
            val forecast = card()
            if (forecast == null || !matches(forecast)) { stop("Карточка выбранного места изменилась."); return@waitFor }
            cancel(); done(forecast, key, null)
        }
    }
    companion object {
        internal fun placeQuery(place: String): String = place.trim().let {
            if (it.length > 4 && it.last() == 'е') it.dropLast(1) else it
        }
        internal fun placeMatches(query: String, label: String): Boolean {
            fun normalized(text: String) = text.lowercase(Locale.ROOT).replace("батуми", "batumi").replace("тбилиси", "tbilisi")
                .replace("москва", "москв").replace("moscow", "москв").replace(Regex("[^\\p{IsLatin}\\p{IsCyrillic}\\p{N} ]"), " ").trim().split(Regex("\\s+"))
            val wanted = normalized(query)
            val words = normalized(label)
            return wanted.isNotEmpty() && wanted.all { q -> q.length >= 3 && words.any { it.startsWith(q) } }
        }
    }
}
