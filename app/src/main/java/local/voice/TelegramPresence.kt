package local.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.net.URI

/** Reads one selected location card in the existing Telegram UI; no Telegram login or sending. */
internal class TelegramPresence(private val context: Context, private val source: SavedSource,
    private val pkg: String, private val allowed: () -> Boolean, private val done: (PresenceEvidence?, String?) -> Unit,
    private val linkedWebsite: ((String) -> Unit)? = null) {
    private val main = Handler(Looper.getMainLooper())
    private var cancelled = false
    fun cancel() { cancelled = true }
    private fun valid() = !cancelled && allowed() && PhoneAccessibility.connected?.foregroundPackage() == pkg
    companion object {
        fun sourceLinks(labels: List<String>) = labels.flatMap { label ->
            Regex("https://[^\\s<>\"']+").findAll(label).map { it.value.trimEnd('.', ',', ';', ')') }.filter { PresenceHttp.validUrl(it) && !isTelegramLink(it) }.toList()
        }.distinct()
        fun isTelegramLink(value: String): Boolean = runCatching {
            val uri = URI(value)
            if (uri.scheme == "https" && uri.host in setOf("t.me", "telegram.me")) {
                uri.rawQuery == null && uri.fragment == null && uri.userInfo == null &&
                    uri.path.matches(Regex("/(?:[A-Za-z][A-Za-z0-9_]{4,31}(?:/\\d+){0,2}|c/\\d+/\\d+(?:/\\d+)?)")) &&
                    uri.path.substringAfter('/').substringBefore('/') !in setOf("share", "addstickers", "addlist", "joinchat", "login", "proxy")
            } else if (uri.scheme == "tg" && uri.host in setOf("resolve", "privatepost")) {
                val pairs = uri.rawQuery.orEmpty().split('&').map { it.split('=', limit = 2) }
                pairs.all { it.size == 2 && it[0] in setOf("domain", "post", "channel", "thread") && it[1].matches(Regex("[A-Za-z0-9_]+")) } &&
                    if (uri.host == "resolve") pairs.any { it[0] == "domain" && it[1].matches(Regex("[A-Za-z][A-Za-z0-9_]{4,31}")) }
                    else pairs.any { it[0] == "channel" } && pairs.any { it[0] == "post" }
            } else false
        }.getOrDefault(false)
    }
    fun start() {
        if (!valid()) { done(null, "Telegram недоступен. Разблокируйте телефон и включите доступ к экрану."); return }
        val ui = PhoneAccessibility.connected!!
        val frame = ui.foregroundSnapshot() ?: return done(null, "Экран Telegram недоступен.")
        val labels = ui.elements().filter { !it.editable }.map { it.label }.distinct().joinToString("\n")
        val already = PresenceDocument.telegramText(labels, source.participant)
        if (already != null) { done(already, null); return }
        val links=if (source.followLinks) sourceLinks(ui.elements().filter { !it.editable }.mapNotNull { TelegramUiText.message(it.label)?.body }) else emptyList()
        if (links.size == 1 && linkedWebsite != null) { linkedWebsite.invoke(links.single()); return }
        if (links.size > 1) { done(null,"В чате несколько ссылок; нельзя однозначно выбрать источник. Поделитесь нужной ссылкой с Ассистентом и сохраните её отдельно."); return }
        val cards = ui.elements().filter { it.clickable && !it.editable && Regex("трансляция геопозиции|live location|живая геопозиция", RegexOption.IGNORE_CASE).containsMatchIn(it.label) }
        if (cards.isEmpty()) {
            val messages=ui.elements().filter { !it.editable }.mapNotNull { TelegramUiText.message(it.label) }.distinct().takeLast(5)
            if (messages.isNotEmpty()) { done(PresenceEvidence(status="Видимые сообщения в выбранном чате, положение по ним не подтверждено. "+messages.joinToString(". ") { PresenceFacts.forSpeech(it.body).take(250) }.take(1200)),null); return }
            read(frame); return
        }
        if (cards.size != 1 || !ui.tapElement(cards.single(), frame)) { read(frame); return }
        val until = SystemClock.elapsedRealtime() + 5000
        var last: PhoneAccessibility.ForegroundSnapshot? = null
        var stableAt = SystemClock.elapsedRealtime()
        val poll = object : Runnable {
            override fun run() {
                if (!valid()) return
                val now = PhoneAccessibility.connected?.foregroundSnapshot()
                if (now != last) { last = now; stableAt = SystemClock.elapsedRealtime() }
                if (now != null && SystemClock.elapsedRealtime() - stableAt >= 400) read(now)
                else if (SystemClock.elapsedRealtime() >= until) done(null, "Карточка геопозиции не стала доступной.")
                else main.postDelayed(this, 100)
            }
        }; main.post(poll)
    }
    private fun read(frame: PhoneAccessibility.ForegroundSnapshot) {
        val labels = PhoneAccessibility.connected?.elements()?.filter { !it.editable }?.map { it.label }?.distinct()?.joinToString("\n").orEmpty()
        val fact = PresenceDocument.telegramText(labels, source.participant)
        if (fact != null) { done(fact, null); return }
        AppScreenReader.read(context, frame, ::valid) { observation, error ->
            if (!valid()) return@read
            val extracted = observation?.let { PresenceDocument.telegramText(it.visual, source.participant) }
            done(extracted, if (extracted == null) error ?: "Не нашёл однозначную строку участника. Укажите его точное имя. Telegram не отдаёт координаты карты через чтение экрана." else null)
        }
    }
}
