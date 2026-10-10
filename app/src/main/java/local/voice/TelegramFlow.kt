package local.voice

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** Bounded, local-only adapter for the official Telegram accessibility UI. */
internal class TelegramFlow(
    private var request: TelegramRequest,
    private val pkg: String,
    private val speak: (String, (() -> Boolean)?, (() -> Unit)?) -> Unit,
    private val monitor: () -> Unit,
    private val finished: () -> Unit,
    private val locationReady: (() -> Unit)? = null,
) {
    private val trace = InteractionJournal.current?.begin("telegram", InteractionJournal.data("request" to request.toString(), "package" to pkg))
    private val main = Handler(Looper.getMainLooper())
    private val until = SystemClock.elapsedRealtime() + 300_000
    private var cancelled = false
    private var waitingUntil = 0L
    private var waiting: String? = null
    private var choices = emptyList<PhoneAccessibility.Element>()
    private var frame: PhoneAccessibility.ForegroundSnapshot? = null
    private var recipient = ""
    private var username: String? = null
    private var chat: PhoneAccessibility.ForegroundSnapshot? = null
    private var field: PhoneAccessibility.FieldTarget? = null
    private var send: PhoneAccessibility.Element? = null
    private var beforeMessages = emptyMap<TelegramUiText.Status, Int>()
    private var writtenDraft: String? = null
    private var backSteps=0
    val awaitingFollowUp: Boolean get() = !cancelled && waiting == "followup"

    fun cancel() { if (!cancelled) trace?.event("closed", InteractionJournal.data("waiting" to waiting)); cancelled = true; waiting = null; writtenDraft = null }
    private fun available() = !cancelled && SystemClock.elapsedRealtime() <= until &&
        PhoneAccessibility.connected?.foregroundPackage() == pkg
    private fun service() = PhoneAccessibility.connected?.takeIf { available() }
    private fun stop(message: String) {
        if (cancelled) return
        trace?.result(InteractionJournal.data("text" to message, "recipient" to recipient))
        cancel(); finished(); speak(message, null, null)
    }
    private fun elements() = service()?.elements().orEmpty()
    private fun ask(message: String, phase: String) {
        trace?.event("confirmation", InteractionJournal.data("phase" to phase, "text" to message))
        waiting = phase; waitingUntil = SystemClock.elapsedRealtime() + 60_000
        speak(message, { available() }, null)
    }
    private fun waitFor(check: () -> Boolean, done: () -> Unit, failure: String, timedOut: (() -> Unit)? = null) {
        if (!available()) { stop("Телефон заблокирован или приложение изменилось. Действие остановлено."); return }
        waiting = null; monitor()
        val deadline = SystemClock.elapsedRealtime() + 5000
        var previous: PhoneAccessibility.ForegroundSnapshot? = null
        var stable = SystemClock.elapsedRealtime()
        val poll = object : Runnable {
            override fun run() {
                if (cancelled) return
                if (!available()) { stop("Телефон заблокирован или приложение изменилось. Действие остановлено."); return }
                val now = SystemClock.elapsedRealtime()
                val current = service()?.foregroundSnapshot()
                if (current != previous) { previous = current; stable = now }
                if (now - stable >= 400 && check()) done()
                else if (now >= deadline) { if (timedOut != null) timedOut() else stop(failure) }
                else main.postDelayed(this, 100)
            }
        }
        main.post(poll)
    }
    fun start() {
        val ui = service() ?: run { stop("Для Telegram разблокируйте телефон и включите доступ к экрану."); return }
        if (request.operation == TelegramRequest.Operation.LOCATE && !request.recipient.startsWith("@")) {
            recipient=request.recipient
            if (isChat()) { opened(); return }
            recipient=""
        }
        if (request.recipient.isBlank()) {
            ask("Назовите имя человека или его имя пользователя Telegram.", "recipient")
            return
        }
        if (searchField() != null) {
            waitFor({ searchField() != null }, ::enterQuery, "Поле поиска Telegram изменилось."); return
        }
        val search = elements().singleOrNull { it.label.trim().lowercase() in setOf("поиск", "search") && !it.editable }
        val snapshot = ui.foregroundSnapshot()
        if (search == null && snapshot != null && backSteps++ < 3 && ui.goBack(snapshot)) {
            main.postDelayed({ if (available()) start() else stop("Telegram закрылся; откройте список чатов.") },600); return
        }
        if (search == null || snapshot == null || !ui.tapElement(search, snapshot)) {
            stop("Не нашёл однозначную кнопку поиска Telegram. Войдите в аккаунт и откройте список чатов."); return
        }
        waitFor({ searchField() != null }, ::enterQuery, "Telegram не показал доступное поле поиска.")
    }
    private fun searchField(): PhoneAccessibility.FieldTarget? {
        val ui = service() ?: return null
        val target = ui.fieldTarget() ?: return null
        return target.takeIf { it.hint.trim().lowercase() in setOf("поиск", "search") }
    }
    private fun enterQuery() {
        val ui = service() ?: return
        val target = searchField() ?: run { stop("Поле поиска изменилось."); return }
        ui.writeSearch(target, request.recipient, ::available) { result ->
            if (cancelled) return@writeSearch
            if (result != "Текст записан в поле. Отправка не выполнялась.") { stop(result); return@writeSearch }
            waitFor({ candidates().isNotEmpty() }, ::offerPeople,
                "Не нашёл доступных результатов для ${request.recipient}. Назовите точное имя или имя пользователя.")
        }
    }
    private fun candidates() = elements().filter {
        it.clickable && !it.editable && (if (request.operation == TelegramRequest.Operation.LOCATE) TelegramUiText.sourceChat(it.label,request.recipient) else TelegramUiText.person(it.label, request.recipient))
    }
    private fun name(element: PhoneAccessibility.Element) = element.label.substringBefore('\n').substringBefore(',').substringBefore(". ").trim()
    private fun offerPeople() {
        val people = candidates()
        if (people.size > 5 || people.map { it.label }.distinct().size != people.size) {
            stop("Результаты неоднозначны. Назовите точное имя пользователя Telegram."); return
        }
        choices = people; frame = service()?.foregroundSnapshot()
        if (request.operation == TelegramRequest.Operation.LOCATE && choices.size == 1) {
            waiting="person"; waitingUntil=SystemClock.elapsedRealtime()+60_000; answer(VoiceCommand.Choose(0)); return
        }
        ask("Кого выбрать? " + people.mapIndexed { index, person -> "${index + 1}: ${person.label.take(180)}." }.joinToString(" ") +
            " Скажите первый, второй или отмена.", "person")
    }
    fun answer(command: VoiceCommand, raw: String = ""): Boolean {
        if (waiting == "followup") {
            val previous = request.copy(recipient = recipient)
            val draftContext = writtenDraft?.let { previous.copy(operation = TelegramRequest.Operation.DRAFT, text = it) } ?: previous
            val next = TelegramRequest.followUp(raw, draftContext)
            if (next == null && !TelegramRequest.isSendDraft(raw)) return false
            trace?.event("followup_request", InteractionJournal.data("text" to raw, "request" to next?.toString(),
                "status" to if (next == null) "missing_confirmed_draft" else "accepted"))
            if (SystemClock.elapsedRealtime() > waitingUntil || !sameChat()) {
                stop("Выбранный чат Telegram истёк или изменился. Повторите запрос с именем человека."); return true
            }
            if (next == null) {
                followUp("В этом чате нет подтверждённого черновика. Скажите напиши сообщение и текст."); return true
            }
            val existingDraft = writtenDraft != null && next.operation == TelegramRequest.Operation.SEND && next.text == writtenDraft
            request = next
            waiting = null
            if (existingDraft) {
                if (!draftMatches()) {
                    stop("Черновик изменился. Отправка отменена."); return true
                }
                prepareSend()
            } else opened()
            return true
        }
        if (waiting == "recipient" && command is VoiceCommand.Chat) {
            if (!available() || SystemClock.elapsedRealtime() > waitingUntil) {
                stop("Ввод имени Telegram истёк или приложение изменилось. Повторите запрос."); return true
            }
            val name = command.text.trim()
            if (!Regex("^@?[\\p{L}\\p{N}][\\p{L}\\p{N} ._'’-]{0,99}$").matches(name)) {
                ask("Назовите только имя человека или его имя пользователя Telegram.", "recipient"); return true
            }
            request = request.copy(recipient = name)
            waiting = null
            start()
            return true
        }
        if (command != VoiceCommand.Confirm && command !is VoiceCommand.Choose) return false
        if (!available() || SystemClock.elapsedRealtime() > waitingUntil || waiting == null) {
            stop("Выбор Telegram истёк или экран изменился. Повторите запрос."); return true
        }
        when (waiting) {
            "recipient" -> ask("Сначала назовите имя человека или его имя пользователя Telegram.", "recipient")
            "person" -> {
                if (command !is VoiceCommand.Choose) { ask("Назовите номер человека из списка или отмена.", "person"); return true }
                val selected = choices.getOrNull(command.index)
                val expected = frame
                if (selected == null) { ask("Такого номера нет. Назовите номер из списка.", "person"); return true }
                if (expected == null || service()?.tapElement(selected, expected) != true) { stop("Результаты поиска изменились. Выбор отменён."); return true }
                recipient = name(selected)
                username = if (choices.count { name(it) == recipient } > 1)
                    Regex("@[A-Za-z0-9_]{5,32}").find(selected.label)?.value else null
                if (choices.count { name(it) == recipient } > 1 && username == null) {
                    stop("Одинаковые имена без проверяемого имени пользователя. Выбор отменён."); return true
                }
                waitFor(::isChat, ::opened, "Не удалось проверить чат выбранного человека. Действие остановлено.")
            }
            "write" -> if (command == VoiceCommand.Confirm) write() else ask("Скажите подтверждаю или отмена.", "write")
            "send" -> if (command == VoiceCommand.Confirm) send() else ask("Скажите подтверждаю или отмена.", "send")
        }
        return true
    }
    private fun top(element: PhoneAccessibility.Element) = android.graphics.Rect.unflattenFromString(element.bounds)?.top ?: Int.MAX_VALUE
    private fun headerMatches(text: String): Boolean {
        val ui = elements()
        val firstMessage = messages().minOfOrNull(::top) ?: ((ui.mapNotNull {
            android.graphics.Rect.unflattenFromString(it.bounds)?.bottom
        }.maxOrNull() ?: 0) / 4)
        return ui.count { !it.editable && it.text == text && top(it) < firstMessage } == 1
    }
    private fun isChat() = recipient.isNotBlank() && headerMatches(recipient) &&
        (username == null || headerMatches(username!!)) &&
        (request.operation == TelegramRequest.Operation.LOCATE || service()?.fieldTarget()?.hint?.trim()?.lowercase() in setOf("сообщение", "message"))
    private fun sameChat() = available() && isChat() && service()?.foregroundSnapshot() == chat
    private fun messages() = elements().filter { !it.editable && TelegramUiText.messageBody(it.label) != null }
    private fun draftMatches() = elements().filter { it.editable }.singleOrNull()?.text == request.text
    private fun followUp(message: String) {
        if (!available() || !isChat()) { stop("Чат Telegram изменился. Действие остановлено."); return }
        chat = service()?.foregroundSnapshot()
        if (chat == null) { stop("Не удалось проверить выбранный чат Telegram."); return }
        waiting = "followup"; waitingUntil = until
        trace?.result(InteractionJournal.data("text" to message, "recipient" to recipient, "next" to "followup"))
        speak(message, ::sameChat) {
            if (!cancelled) waitingUntil = SystemClock.elapsedRealtime() + 60_000
        }
    }
    private fun opened() {
        if (!isChat()) { stop("Адресат не подтверждён по экрану."); return }
        chat = service()?.foregroundSnapshot()
        when (request.operation) {
            TelegramRequest.Operation.LOCATE -> {
                if (locationReady == null) { stop("Чтение геопозиции не подключено."); return }
                cancel(); finished(); locationReady.invoke()
            }
            TelegramRequest.Operation.FIND -> followUp("Открыт чат: $recipient. Можно сказать прочитай сообщения или напиши сообщение и текст.")
            TelegramRequest.Operation.READ -> {
                val visible = messages().takeLast(12).joinToString(". ") { it.label }
                if (visible.isBlank()) { stop("В чате $recipient нет доступного текста сообщений."); return }
                trace?.event("read_result", InteractionJournal.data("recipient" to recipient, "text" to visible))
                followUp("Видимые сообщения в чате $recipient. $visible")
            }
            else -> {
                field = service()?.fieldTarget()
                if (field == null) { stop("Не нашёл поле сообщения."); return }
                ask("Чат: $recipient. Записать сообщение: ${request.text}? Скажите подтверждаю или отмена.", "write")
            }
        }
    }
    private fun write() {
        trace?.event("step", InteractionJournal.data("name" to "write", "recipient" to recipient, "text" to request.text))
        val target = field
        if (!sameChat() || target == null) { stop("Чат или текст изменились. Ввод отменён."); return }
        waiting = null; monitor()
        service()?.writeChat(target, request.text, { available() && isChat() }) { result ->
            if (cancelled) return@writeChat
            if (result != "Текст записан в поле. Отправка не выполнялась.") { stop(result); return@writeChat }
            if (request.operation == TelegramRequest.Operation.DRAFT) {
                waitFor({ isChat() && draftMatches() }, {
                    writtenDraft = request.text
                    followUp("Черновик в чате $recipient записан. Сообщение не отправлено. Чтобы отправить, скажите отправь.")
                }, "Не удалось проверить записанный черновик. Отправка не выполнялась.")
                return@writeChat
            }
            prepareSend()
        }
    }
    private fun prepareSend() {
        waitFor({ isChat() && draftMatches() && elements().count { it.label.trim().lowercase() in setOf("отправить", "send") && !it.editable } == 1 }, {
            val observed = service()?.foregroundSnapshot()
            val screen = elements()
            val button = screen.singleOrNull { it.label.trim().lowercase() in setOf("отправить", "send") && !it.editable }
            if (observed == null || button == null || service()?.foregroundSnapshot() != observed) {
                stop("Текст записан, но экран или кнопка отправки изменились. Отправка не выполнялась.")
                return@waitFor
            }
            chat = observed
            send = button
            beforeMessages = TelegramUiText.outgoingCounts(screen.filter { !it.editable && TelegramUiText.messageBody(it.label) != null }.map { it.label }, request.text)
            ask("Отправить в чате $recipient сообщение: ${request.text}? Скажите подтверждаю или отмена.", "send")
        }, "Текст записан, но не удалось проверить кнопку отправки. Отправка не выполнялась.")
    }
    private fun send() {
        trace?.event("step", InteractionJournal.data("name" to "send", "recipient" to recipient, "text" to request.text))
        val expected = chat; val button = send
        if (!sameChat() || !draftMatches() || expected == null || button == null || service()?.tapElement(button, expected) != true) {
            stop("Чат, сообщение или кнопка изменились. Отправка отменена."); return
        }
        // Never retry a send: a missing UI receipt is not permission to send again.
        fun receipt() = if (!isChat()) null else TelegramUiText.newOutgoingStatus(beforeMessages,
            TelegramUiText.outgoingCounts(messages().map { it.label }, request.text))
        fun reportReceipt() {
            when (receipt()) {
                TelegramUiText.Status.ERROR -> stop("Telegram сообщает об ошибке отправки в чате $recipient. Повторную отправку не выполняю.")
                TelegramUiText.Status.PENDING -> stop("Telegram ещё отправляет сообщение в чате $recipient. Отправка не подтверждена. Повторную отправку не выполняю.")
                TelegramUiText.Status.SENT -> stop("Telegram пометил новое сообщение в чате $recipient как отправленное. Доставка получателю не проверена.")
                else -> stop("Результат отправки изменился. Не удалось подтвердить новое исходящее сообщение. Повторную отправку не выполняю.")
            }
        }
        val unknown = "Нажатие отправки принято, но новое сообщение по экрану не подтверждено. Повторную отправку не выполняю."
        waitFor({ receipt() in setOf(TelegramUiText.Status.SENT, TelegramUiText.Status.ERROR) }, ::reportReceipt, unknown,
            timedOut = { if (receipt() == TelegramUiText.Status.PENDING) reportReceipt() else stop(unknown) })
    }
}
