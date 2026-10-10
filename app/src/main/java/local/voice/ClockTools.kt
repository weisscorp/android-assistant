package local.voice

import android.content.Context
import android.os.SystemClock
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Session-bound clarification and confirmation. Both voice and text use the same executor. */
class ClockTools(private val context: Context) {
    private var clarification: Pair<Long,ClockRequest>? = null
    private var confirmation: Pair<Long,()->String>? = null
    fun clear() { clarification=null; confirmation=null }
    fun answer(command: VoiceCommand, raw: String): DeviceTools.Reply? {
        val question=clarification
        if(question != null) {
            clarification=null
            if(command == VoiceCommand.Cancel) return DeviceTools.Reply("Отменено.")
            if(SystemClock.elapsedRealtime()<=question.first && command is VoiceCommand.Chat) {
                val previous=question.second
                val combined=when {
                    previous.kind==ClockKind.TIMER -> "таймер на " + raw.trim().removePrefix("на ")
                    previous.kind==ClockKind.REMINDER && previous.moment != null -> {
                        return offer(previous.copy(title=raw.take(160),problem=null),false)
                    }
                    previous.kind==ClockKind.REMINDER -> "напомни $raw ${previous.title}"
                    else -> "будильник $raw"
                }
                ClockRequests.parse(combined)?.let { return offer(it,false) }
            }
        }
        val pending=confirmation
        confirmation=null
        if(pending != null && command==VoiceCommand.Cancel) return DeviceTools.Reply("Отменено.")
        if(pending != null && command==VoiceCommand.Confirm) return DeviceTools.Reply(
            if(SystemClock.elapsedRealtime()>pending.first) "Подтверждение истекло. Повторите команду."
            else runCatching(pending.second).getOrElse { it.message ?: "Не удалось выполнить команду часов." })
        return null
    }
    private fun confirm(text: String, action: ()->String): DeviceTools.Reply {
        confirmation=SystemClock.elapsedRealtime()+60_000 to action
        return DeviceTools.Reply("$text Скажите подтверждаю или отмена.",actionPending=true)
    }
    fun offer(request: ClockRequest, proposed: Boolean): DeviceTools.Reply {
        clear()
        request.problem?.let {
            if(!it.startsWith("Пока поддерживаю")) clarification=SystemClock.elapsedRealtime()+60_000 to request
            return DeviceTools.Reply(it)
        }
        if(request.kind==ClockKind.STOPWATCH) {
            fun perform(): String {
                val (ms,laps)=PhoneClocks.stopwatch(context,request.operation)
                return when(request.operation) {
                    ClockOperation.START,ClockOperation.RESUME -> "Секундомер запущен. Прошло ${ClockRequests.durationSpeech(ms/1000)}."
                    ClockOperation.PAUSE -> "Секундомер остановлен. Прошло ${ClockRequests.durationSpeech(ms/1000)}."
                    ClockOperation.RESET -> "Секундомер сброшен."
                    ClockOperation.LAP -> "Круг ${laps.size}: ${ClockRequests.durationSpeech(ms/1000)}."
                    else -> "На секундомере ${ClockRequests.durationSpeech(ms/1000)}."
                }
            }
            return if(proposed && request.operation!=ClockOperation.STATUS) confirm("Выполнить команду секундомера?") { perform() } else DeviceTools.Reply(perform())
        }
        if(request.operation==ClockOperation.STATUS) {
            val entries=ClockStore(context).use { it.list(request.kind) }
            if(entries.isEmpty()) return DeviceTools.Reply("Активных ${when(request.kind) { ClockKind.TIMER -> "таймеров"; ClockKind.ALARM -> "будильников"; else -> "напоминаний" }} нет.")
            return DeviceTools.Reply(entries.take(8).joinToString("; ") { e -> if(e.state=="fired") "${e.title}: уже сработал" else
                if(request.kind==ClockKind.TIMER) "${e.title.ifBlank { "Таймер" }}: осталось ${ClockRequests.durationSpeech(maxOf(0,(PhoneClocks.remaining(context,e)+999)/1000))}" else PhoneClocks.description(e) } + ".")
        }
        if(request.operation==ClockOperation.CANCEL) {
            val all=ClockStore(context).use { it.list(request.kind) }
            val matches=when { request.title=="*" -> all; request.title.isBlank() -> all; else -> all.filter { it.title.equals(request.title,true) } }
            if(matches.isEmpty()) return DeviceTools.Reply("Не нашёл такой активный таймер, будильник или напоминание.")
            if(matches.size>1 && request.title!="*") return DeviceTools.Reply("Их несколько. Назовите название или скажите отменить все ${when(request.kind) { ClockKind.TIMER -> "таймеры"; ClockKind.ALARM -> "будильники"; else -> "напоминания" }}.")
            return confirm("Отменить ${matches.joinToString("; ",limit=5) { PhoneClocks.description(it) }}${if(request.kind==ClockKind.REMINDER) " и удалить созданную ассистентом запись календаря" else ""}?") {
                matches.forEach { check(PhoneClocks.cancel(context,it.id,request.kind==ClockKind.REMINDER)) { "Состояние часов изменилось. Повторите команду." } }; "Отменено."
            }
        }
        PhoneClocks.issue(context)?.let { return DeviceTools.Reply(it) }
        val now=ZonedDateTime.now()
        val at=if(request.kind==ClockKind.TIMER) now.toInstant().plusSeconds(request.seconds ?: return DeviceTools.Reply("Назовите длительность таймера.")) else request.moment?.resolve(now)
            ?: return DeviceTools.Reply("Это время уже прошло или неоднозначно при переводе часов. Назовите новую дату и время.")
        val title=request.title.ifBlank { if(request.kind==ClockKind.TIMER) "Таймер" else "Будильник" }
        if(request.kind==ClockKind.REMINDER) {
            if(!ClockCalendar.permitted(context)) return DeviceTools.Reply("Разрешите чтение и запись календаря в Параметрах, Время и напоминания. Напоминание пока не создано.")
            val calendar=runCatching { ClockCalendar.selected(context) }.getOrNull()
                ?: return DeviceTools.Reply("Выберите календарь в Параметрах, Время и напоминания. Можно создать локальный календарь без аккаунта. Напоминание пока не создано.")
            return confirm("Записать в календарь ${calendar.name}: $title, ${DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").format(at.atZone(now.zone))}?") {
                PhoneClocks.issue(context)?.let { error(it) }
                val event=ClockCalendar.add(context,calendar,title,at)
                try { PhoneClocks.create(context,request.kind,title,at,event=event) }
                catch(e: Exception) { runCatching { ClockCalendar.delete(context,event) }; throw e }
                "Напоминание записано в календарь ${calendar.name}. Сообщу ${DateTimeFormatter.ofPattern("dd.MM HH:mm").format(at.atZone(now.zone))}."
            }
        }
        val announcement=if(request.kind==ClockKind.TIMER) "Таймер на ${ClockRequests.durationSpeech(request.seconds!!)}" else "Будильник на ${DateTimeFormatter.ofPattern("dd.MM HH:mm").format(at.atZone(now.zone))}"
        val perform = {
            val deadline = if(request.kind == ClockKind.TIMER) java.time.Instant.now().plusSeconds(request.seconds!!) else at
            PhoneClocks.create(context,request.kind,title,deadline,request.seconds)
            "$announcement установлен${if(title !in setOf("Таймер","Будильник")) ": $title" else ""}."
        }
        return if(proposed || request.kind==ClockKind.ALARM) confirm("Установить ${announcement.lowercase()}?") { perform() }
            else DeviceTools.Reply(runCatching(perform).getOrElse { it.message ?: "Не удалось установить часы." })
    }
}
