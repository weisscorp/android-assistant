package local.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject

/** A truthful inventory shared by the user interface and the server protocol. */
object CapabilityCatalog {
    data class Entry(val name: String, val description: String, val arguments: String, val ready: Boolean, val requirement: String = "", val remote: Boolean = true)
    fun entries(context: Context): List<Entry> {
        fun permission(value: String) = context.checkSelfPermission(value) == PackageManager.PERMISSION_GRANTED
        val screen = PhoneAccessibility.connected != null
        val unlocked = !context.getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked
        return listOf(
            Entry("audio_notes", "Отдельные аудиозаметки: запись, локальная расшифровка, поиск и библиотека хаба. Кнопка Аудиозаметки на главном экране; команда аудиозаметки или найди аудиозаметку и слова", "user voice/text command", true, remote = false),
            Entry("phone_control", "Локальные системные команды: назад, домой, последние приложения, шторка, быстрые настройки, блокировка, скриншот, меню питания, громкость, яркость, фонарик и разделы настроек. Уточнения голосом; команда команды телефона", "user voice/text command", true, "Системные действия: доступ к экрану; яркость: изменение системных настроек; фонарик: камера", remote = false),
            Entry("time", "Текущее время телефона", "{}", true),
            Entry("clock", "Локальные таймер, однократный будильник, секундомер и запись напоминания в календарь. Создание/отмена предложенных сервером часов требует подтверждения на телефоне", "{request: string, например: таймер на десять минут; будильник завтра в семь; напомни завтра в восемь купить хлеб}", true, "Для таймера/будильника: точные будильники и уведомления; для напоминания ещё доступ и выбранный календарь"),
            Entry("battery", "Заряд батареи", "{}", true),
            Entry("codex", "Прямые команды Hub: задачи кодекса, выбери задачу кодекса и название, статус кодекса, прочитай отчёт кодекса, передай кодексу и сообщение. Обходят локальную LLM; выбор хранится в текущей теме", "user voice/text command", BackendClient.endpoint(context).isNotBlank(), "Hub с подключённым нативным Codex", remote = false),
            Entry("open", "Открыть установленное приложение; при нескольких спросить пользователя", "{app: string}", unlocked, "Разблокировка"),
            Entry("call", "Позвонить по контакту/сохранённой связи; номер перечитывается из адресной книги; требуется подтверждение", "{recipient: string}", permission(Manifest.permission.CALL_PHONE) && permission(Manifest.permission.READ_CONTACTS), "Разрешения звонков и контактов"),
            Entry("telegram", "Поиск/чтение/черновик/отправка в официальном Telegram; выбор человека и подтверждение через экран", "{recipient: string, operation: find|read|draft|send, text: string}", screen && unlocked, "Telegram, доступ к экрану, разблокировка"),
            Entry("navigate", "Выбрать навигатор и способ передвижения, открыть поиск/маршрут. Время и сравнение маршрутов автоматически пока не читаются", "{destination: string, mode: car|walk|transit|null}", unlocked && MobileApps.navigators(context).isNotEmpty(), "Установленный навигатор, разблокировка"),
            Entry("music", "Spotify: поиск исполнителя; воспроизведение только если приложение поддерживает Android play-from-search и подтвердило PLAYING", "{artist: string, play: boolean}", unlocked && MobileApps.spotifyInstalled(context), "Spotify, разблокировка; для проверки воспроизведения доступ к уведомлениям"),
            Entry("media", "Название и исполнитель текущего трека (info); пауза/продолжить/следующий/предыдущий трек и громкость существующего проигрывателя", "{action: info|play|pause|next|previous|louder|quieter}", PhoneNotifications.hasAccess(context), "Активный проигрыватель, доступ к уведомлениям"),
            Entry("weather", "Погода Open-Meteo по GPS или городу в фоне, сейчас/завтра/послезавтра, без Hermes и открытия приложения", "{place: string|null, day: 0|1|2}", true, "Интернет; для текущего места разрешение геолокации, иначе назовите город"),
            Entry("saved_sources", "Локальная команда «где <сохранённое название>» или «прочитай источник <название>»: пользовательские чаты/страницы/места/снимки, свежесть и расстояние по прямой. Вызывается напрямую пользователем; сервер не выбирает частный источник", "user voice/text command", SavedSources(context).list().isNotEmpty(), "Знания и источники в Параметрах; для Telegram разблокировка и доступ к экрану", remote = false)
        )
    }
    fun json(context: Context): JSONArray = JSONArray().apply {
        // Only callable remote tools belong to the strict conversation protocol.
        entries(context).filter { it.remote }.forEach { put(JSONObject().put("name", it.name).put("description", it.description)
            .put("arguments", it.arguments).put("available", it.ready).put("requirement", it.requirement)) }
    }
    fun message(context: Context): String = buildString {
        append("На телефоне: таймеры, будильники, секундомер и напоминания в календарь; время и заряд; контакты и ваши связи; Telegram; выбор навигатора и транспорта; поиск в Spotify; управление музыкой; фоновая погода Open-Meteo; windy.app по явной просьбе; чтение экрана с локальным OCR.\n\n")
        entries(context).forEach { append("${if (it.ready) "Доступно" else "Нужна настройка"}: ${it.name} — ${it.description}${if (!it.ready || it.name == "clock") ". ${it.requirement}" else ""}.\n") }
        append("\nHermes: открытые вопросы, сложные рассуждения, поиск в интернете и программирование через настроенные серверные инструменты. Телефон сохраняет распознавание и озвучку. ")
        append(if (BackendClient.endpoint(context).isBlank()) "Сервер пока не настроен." else "В автоматическом режиме сложные запросы и ошибки локальной модели передаются серверу.")
        append("\nПока нет: автоматического сравнения маршрутов, чтения всех значков/графиков любых приложений и надёжного поиска любого исполнителя по русскому произношению без сервера.")
    }
}
